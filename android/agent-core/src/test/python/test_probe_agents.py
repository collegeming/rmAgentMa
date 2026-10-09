# Copyright 2026 rmAgentMa contributors
# SPDX-License-Identifier: Apache-2.0

import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[2] / "main/resources/remote/probe_agents.py"
spec = importlib.util.spec_from_file_location("probe_agents", SCRIPT)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ProbeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def cli(self, name, response=None, silent=False):
        path = self.root / name
        path.write_text("#!" + sys.executable + "\nimport json,sys,time\n"
                        "if sys.argv[1:] == ['--version']:\n"
                        " print('0.2.1-alpha.1')\n"
                        "else:\n"
                        " assert sys.argv[1:]==" + repr(["--profile", "acp"] if name == "dsh" else ["acp"]) + "\n"
                        " value=json.loads(sys.stdin.readline())\n"
                        " assert value['method']=='initialize' and value['id']==1\n"
                        + (" time.sleep(10)\n" if silent else
                           " print(json.dumps({'id':1,'jsonrpc':'2.0','result':" + repr(response) + "}), flush=True)\n"
                           " time.sleep(10)\n"))
        path.chmod(0o755)
        return str(path)

    def test_dsh_verified_resume_not_load_no_prompt(self):
        executable = self.cli("dsh", {"protocolVersion": 1, "agentCapabilities": {
            "sessionCapabilities": {"close": {}, "list": {}, "resume": {}}}})
        with mock.patch.object(probe.shutil, "which", return_value=executable):
            row = probe.probe("dsh")
        self.assertTrue(row["available"])
        self.assertEqual(row["version"], "0.2.1-alpha.1")
        self.assertEqual(row["capabilities"], {"acp": True, "close": True, "list": True,
                                               "resume": True, "load": False})

    def test_absolute_custom_zcode_never_claims_interactive(self):
        executable = self.cli("custom zcode ' $(touch NO)")
        row = probe.probe("zcode", executable)
        self.assertEqual(row["executable"], executable)
        self.assertFalse(row["available"])
        self.assertEqual(row["error"], "zcode_layout_unverified")
        self.assertEqual(row["version"], "0.2.1-alpha.1")
        self.assertFalse((self.root / "NO").exists())

    def test_missing_not_executable_invalid_protocol_and_timeout(self):
        with mock.patch.object(probe.shutil, "which", return_value=None):
            self.assertEqual(probe.probe("kimi")["error"], "executable_missing")
        executable = self.cli("kimi", {"protocolVersion": 99, "agentCapabilities": {}})
        with mock.patch.object(probe.shutil, "which", return_value=executable):
            self.assertEqual(probe.probe("kimi")["error"], "unsupported_protocol_version")
        executable = self.cli("omp", silent=True)
        with mock.patch.object(probe.shutil, "which", return_value=executable), mock.patch.object(probe, "TIMEOUT", 0.1):
            self.assertEqual(probe.probe("omp")["error"], "initialize_timeout")
        Path(executable).chmod(0o644)
        self.assertEqual(probe.probe("zcode", executable)["error"], "executable_missing")

    def zcode_runtime(self, ready=True, response=True, failed=False):
        runtime = self.root / ".zcode/server"
        executable = runtime / "agents/glm/zcode-agent"
        executable.parent.mkdir(parents=True, exist_ok=True)
        if not (runtime / "node").exists():
            (runtime / "node").symlink_to(sys.executable)
        (executable.parent / "zcode.cjs").write_bytes(b"fixture")
        bundled = self.root / ".zcode/v2/runtime/provider/bundled/zcode-builtin.json"
        bundled.parent.mkdir(parents=True, exist_ok=True)
        bundled.write_bytes(b"DO NOT READ - NOT JSON")
        (self.root / ".zcode/v2/provider_config.json").write_bytes(b"DO NOT READ - NOT JSON")
        executable.write_text("#!" + sys.executable + "\nimport json,sys,os,time\n"
                              "if sys.argv[1:]==['--version']:\n print('0.16.9')\n"
                              "else:\n"
                              " assert sys.argv[1:]==['app-server','--cwd',os.environ['HOME']]\n"
                              " assert os.environ['ZCODE_BUILTIN_PROVIDER_CONFIG_FILE']==os.environ['HOME']+'/.zcode/v2/runtime/provider/bundled/zcode-builtin.json'\n"
                              " assert os.environ['ZCODE_PERSONAL_PROVIDER_CONFIG_FILE']==os.environ['HOME']+'/.zcode/v2/provider_config.json'\n"
                              " value=json.loads(sys.stdin.readline())\n"
                              " assert value=={'id':1,'method':'runtime/capabilities','params':{}}\n"
                              + (" print(json.dumps({'id':1,'result':{'independentPlanState':True}}),flush=True)\n" if response else "")
                              + (" print(json.dumps({'method':'startup/storageState','params':{'databaseKind':'session','phase':'" + ("failed" if failed else "ready") + "'}}),flush=True)\n" if ready else "")
                              + " time.sleep(10)\n")
        executable.chmod(0o755)
        return str(executable)

    def test_zcode_standard_fallback_requires_ready_and_actual_capabilities(self):
        executable = self.zcode_runtime()
        with mock.patch.dict(os.environ, {"HOME": str(self.root)}), mock.patch.object(probe.shutil, "which", return_value=None):
            row = probe.probe("zcode")
        self.assertTrue(row["available"])
        self.assertEqual(row["executable"], executable)
        self.assertEqual(row["version"], "0.16.9")
        self.assertEqual(row["capabilities"], {"acp": False, "zcodeProtocol": True,
                                               "startupReady": True, "independentPlanState": True})
        self.assertEqual(row["error"], "")

    def test_zcode_standard_runtime_missing_env_and_readiness_fail_closed(self):
        executable = self.zcode_runtime(ready=False)
        with mock.patch.dict(os.environ, {"HOME": str(self.root)}), mock.patch.object(probe, "TIMEOUT", 1):
            self.assertEqual(probe.probe("zcode", executable)["error"], "zcode_ready_timeout")
            self.zcode_runtime(response=False)
            self.assertEqual(probe.probe("zcode", executable)["error"], "zcode_ready_timeout")
            self.zcode_runtime(failed=True)
            self.assertEqual(probe.probe("zcode", executable)["error"], "zcode_startup_failed")
            (self.root / ".zcode/v2/provider_config.json").unlink()
            self.assertEqual(probe.probe("zcode", executable)["error"], "zcode_runtime_files_missing")

    def test_zcode_path_does_not_fall_through_to_guessed_runtime(self):
        self.zcode_runtime()
        unrelated = self.cli("unrelated")
        with mock.patch.dict(os.environ, {"HOME": str(self.root)}), mock.patch.object(probe.shutil, "which", return_value=unrelated):
            row = probe.probe("zcode")
        self.assertFalse(row["available"])
        self.assertEqual(row["executable"], unrelated)
        self.assertEqual(row["error"], "zcode_layout_unverified")

    def test_version_output_bound_and_capabilities_validation(self):
        executable = self.cli("opencode", {"protocolVersion": 1, "agentCapabilities": {"sessionCapabilities": []}})
        with mock.patch.object(probe.shutil, "which", return_value=executable):
            self.assertEqual(probe.probe("opencode")["error"], "invalid_capabilities")
        with mock.patch.object(probe, "LIMIT", 2):
            with self.assertRaisesRegex(ValueError, "output_limit"):
                probe.run(executable, ["--version"])


if __name__ == "__main__":
    unittest.main()
