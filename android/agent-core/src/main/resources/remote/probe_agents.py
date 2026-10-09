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


def emit(value):
    print(json.dumps(value, ensure_ascii=False, separators=(",", ":")), flush=True)


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


def run(executable, arguments, initialize=False, zcode=False, environment=None):
    process = subprocess.Popen([executable, *arguments], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               start_new_session=True, env=environment)
    try:
        if initialize or zcode:
            request = ({"id": 1, "method": "runtime/capabilities", "params": {}} if zcode else
                       {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
                           "protocolVersion": 1, "clientCapabilities": {},
                           "clientInfo": {"name": "rmAgentMa-probe", "version": "1"}}})
            process.stdin.write((json.dumps(request) + "\n").encode())
            process.stdin.flush()
        else:
            process.stdin.close()
            process.stdin = None
        result = read_output(process, initialize, zcode)
        if not initialize and not zcode and process.wait(timeout=1) != 0:
            raise ValueError("version_failed")
        return result
    finally:
        stop(process)


def zcode_environment(executable):
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
    environment = os.environ.copy()
    environment.update(ZCODE_SERVER_RUNTIME_ROOT=str(runtime),
                       ZCODE_BUILTIN_PROVIDER_CONFIG_FILE=str(bundled),
                       ZCODE_PERSONAL_PROVIDER_CONFIG_FILE=str(personal))
    return environment


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
        version = run(executable, ["--version"]).decode("utf-8", errors="strict").strip()
        result["version"] = version[:1024]
    except Exception:
        result["error"] = "version_unverified"
    if kind == "zcode":
        try:
            environment = zcode_environment(executable)
            if not result["version"]:
                raise ValueError("version_unverified")
            capabilities = run(executable, ["app-server", "--cwd", os.path.expanduser("~")],
                               zcode=True, environment=environment)
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
        response = run(executable, ["--profile", "acp"] if kind == "dsh" else ["acp"], True)
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
