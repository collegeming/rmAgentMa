#!/usr/bin/env python3
# Copyright 2026 rmAgentMa contributors
# SPDX-License-Identifier: Apache-2.0

import argparse
import json
import os
import select
import shutil
import signal
import subprocess
import time

LIMIT = 64 * 1024
TIMEOUT = 8

# The app starts every agent by running one command string through a POSIX shell, so readiness can
# only be claimed from that same launch: the probe repeats the app's command instead of starting the
# binary itself. A failure that lives in the shell layer (an unusable HOME, an unreachable working
# directory, a missing runtime file the launcher points at) then fails the probe as it fails a
# conversation. The paths below mirror ZcodeLaunchConfig; keep them in step with it.
ZCODE_BUILTIN_PROVIDER_CONFIG = "~/.zcode/v2/runtime/provider/bundled/zcode-builtin.json"
ZCODE_PERSONAL_PROVIDER_CONFIG = "~/.zcode/v2/provider_config.json"


def emit(value):
    print(json.dumps(value, ensure_ascii=False, separators=(",", ":")), flush=True)


def quote(value):
    # Same POSIX single-quoting as ShellCommands.quote, so the probed command is the app's command.
    return "'" + value.replace("'", "'\"'\"'") + "'"


def home_path(value):
    # Home-relative paths stay "$HOME"-relative, exactly as ZcodeLaunchConfig.path writes them, so a
    # channel whose HOME is unset or unusable fails the probe and the app's launch in the same place.
    return '"$HOME"/' + quote(value[2:]) if value.startswith("~/") else quote(value)


def zcode_launch(executable, cwd):
    return "exec env ZCODE_BUILTIN_PROVIDER_CONFIG_FILE={} ZCODE_PERSONAL_PROVIDER_CONFIG_FILE={} " \
           "{} app-server --cwd {} --mode build".format(
               home_path(ZCODE_BUILTIN_PROVIDER_CONFIG), home_path(ZCODE_PERSONAL_PROVIDER_CONFIG),
               home_path(executable), quote(cwd))


def acp_launch(executable, cwd, dsh=False):
    return "cd -- {} && exec {} {}".format(quote(cwd), quote(executable), "--profile acp" if dsh else "acp")


def shell(payload):
    return ["/bin/sh", "-c", payload]


def stop(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()
    if process.stdout:
        process.stdout.close()
    if process.stdin:
        process.stdin.close()


def read_output(process, response=False, zcode=False):
    output = bytearray()
    total = 0
    ready = False
    capabilities = None
    deadline = time.monotonic() + TIMEOUT
    while time.monotonic() < deadline:
        if not select.select([process.stdout], [], [], max(0, deadline - time.monotonic()))[0]:
            break
        chunk = os.read(process.stdout.fileno(), 4096)
        if not chunk:
            if zcode:
                raise ValueError("zcode_no_response")
            if response:
                raise ValueError("initialize_no_response")
            return bytes(output)
        output.extend(chunk)
        total += len(chunk)
        if total > LIMIT:
            raise ValueError("output_limit")
        if response or zcode:
            while b"\n" in output:
                line, _, rest = output.partition(b"\n")
                output = bytearray(rest)
                if not line.strip():
                    continue
                value = json.loads(line)
                if not isinstance(value, dict):
                    raise ValueError("invalid_capabilities")
                if zcode and value.get("method") == "startup/storageState":
                    params = value.get("params", {})
                    if not isinstance(params, dict):
                        raise ValueError("zcode_startup_failed")
                    if params.get("databaseKind") == "session":
                        if params.get("phase") == "ready":
                            ready = True
                        elif params.get("phase") in ("failed", "error"):
                            raise ValueError("zcode_startup_failed")
                if value.get("id") == 1:
                    if "error" in value:
                        raise ValueError("zcode_capabilities_rejected" if zcode else "initialize_rejected")
                    result = value.get("result")
                    if not isinstance(result, dict):
                        raise ValueError("invalid_capabilities")
                    if not zcode:
                        return result
                    if not isinstance(result.get("independentPlanState"), bool):
                        raise ValueError("invalid_capabilities")
                    capabilities = result
                if zcode and ready and capabilities is not None:
                    return capabilities
    raise ValueError("zcode_ready_timeout" if zcode else "initialize_timeout" if response else "version_timeout")


def run(command, initialize=False, zcode=False):
    process = subprocess.Popen(command, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               start_new_session=True)
    try:
        if initialize or zcode:
            request = ({"id": 1, "method": "runtime/capabilities", "params": {}} if zcode else
                       {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
                           "protocolVersion": 1, "clientCapabilities": {},
                           "clientInfo": {"name": "rmAgentMa-probe", "version": "1"}}})
            try:
                process.stdin.write((json.dumps(request) + "\n").encode())
                process.stdin.flush()
            except OSError:
                # The agent exited before it read the request: a startup failure, not a slow answer.
                raise ValueError("zcode_no_response" if zcode else "initialize_no_response")
        else:
            process.stdin.close()
            process.stdin = None
        result = read_output(process, initialize, zcode)
        if not initialize and not zcode and process.wait(timeout=1) != 0:
            raise ValueError("version_failed")
        return result
    finally:
        stop(process)


def require_zcode_layout(executable):
    from pathlib import Path
    home = Path.home()
    expected = home / ".zcode/server/agents/glm/zcode-agent"
    if Path(executable).absolute() != expected:
        raise ValueError("zcode_layout_unverified")
    runtime = home / ".zcode/server"
    bundled = home / ".zcode/v2/runtime/provider/bundled/zcode-builtin.json"
    personal = home / ".zcode/v2/provider_config.json"
    if not all(path.is_file() for path in (runtime / "node", expected.parent / "zcode.cjs", bundled, personal)):
        raise ValueError("zcode_runtime_files_missing")
    if not os.access(runtime / "node", os.X_OK):
        raise ValueError("zcode_runtime_files_missing")


def probe(kind, custom=""):
    executable = custom or shutil.which(kind) or ""
    if kind == "zcode" and not executable:
        executable = os.path.join(os.path.expanduser("~"), ".zcode/server/agents/glm/zcode-agent")
    result = {"kind": kind, "executable": executable, "available": False,
              "version": "", "capabilities": {}, "error": ""}
    if not executable or not os.path.isabs(executable) or not os.path.isfile(executable) or not os.access(executable, os.X_OK):
        result["error"] = "executable_missing"
        return result
    try:
        version = run([executable, "--version"]).decode("utf-8", errors="strict").strip()
        result["version"] = version[:1024]
    except Exception:
        result["error"] = "version_unverified"
    cwd = os.path.expanduser("~")
    if kind == "zcode":
        try:
            require_zcode_layout(executable)
            capabilities = run(shell(zcode_launch(executable, cwd)), zcode=True)
            result["capabilities"] = {"acp": False, "zcodeProtocol": True, "startupReady": True,
                                      "independentPlanState": capabilities["independentPlanState"]}
            result["available"] = True
            result["error"] = ""
        except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as exc:
            known = {"zcode_layout_unverified", "zcode_runtime_files_missing", "version_unverified",
                     "zcode_ready_timeout", "zcode_no_response", "zcode_startup_failed",
                     "zcode_capabilities_rejected", "invalid_capabilities", "output_limit"}
            result["error"] = str(exc) if str(exc) in known else "zcode_probe_failed"
        return result
    try:
        response = run(shell(acp_launch(executable, cwd, kind == "dsh")), True)
        if response.get("protocolVersion") != 1:
            raise ValueError("unsupported_protocol_version")
        capabilities = response.get("agentCapabilities")
        if not isinstance(capabilities, dict):
            raise ValueError("invalid_capabilities")
        sessions = capabilities.get("sessionCapabilities", {})
        if not isinstance(sessions, dict):
            raise ValueError("invalid_capabilities")
        result["capabilities"] = {"acp": True, "list": isinstance(sessions.get("list"), dict),
                                  "resume": isinstance(sessions.get("resume"), dict),
                                  "close": isinstance(sessions.get("close"), dict),
                                  "load": capabilities.get("loadSession") is True}
        result["available"] = True
        result["error"] = ""
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as exc:
        known = {"initialize_no_response", "initialize_rejected", "initialize_timeout",
                 "unsupported_protocol_version", "invalid_capabilities", "output_limit"}
        result["error"] = str(exc) if str(exc) in known else "initialize_failed"
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--zcode-executable", default="")
    args = parser.parse_args()
    if args.zcode_executable and (not os.path.isabs(args.zcode_executable) or
                                  any(ord(c) < 32 or ord(c) == 127 for c in args.zcode_executable)):
        emit({"level": "error", "error": "invalid_executable"})
        return
    for kind in ("kimi", "opencode", "omp", "dsh", "zcode"):
        emit(probe(kind, args.zcode_executable if kind == "zcode" else ""))


if __name__ == "__main__":
    main()
