# Copyright 2026 rmAgentMa contributors
# SPDX-License-Identifier: Apache-2.0

import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

REMOTE = Path(__file__).resolve().parents[2] / "main/resources/remote"
SCRIPT = REMOTE / "scan_sessions.py"
SHELL = REMOTE / "scan_sessions.sh"
spec = importlib.util.spec_from_file_location("scan_sessions", SCRIPT)
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)
NOW = 1_790_000_000_000


class ScannerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.bin = self.home / "bin"
        self.bin.mkdir()
        os.symlink(sys.executable, self.bin / "python3")
        zstd = shutil.which("zstd")
        if zstd:
            os.symlink(zstd, self.bin / "zstd")
        self.env = {"HOME": str(self.home), "PATH": str(self.bin), "LC_ALL": "C.UTF-8"}
        self.connections = []
        self.addCleanup(lambda: [db.close() for db in self.connections])

    def scan(self, *args, shell=False):
        command = ["/bin/bash", "-s", "--", *args] if shell else [sys.executable, str(SCRIPT), *args]
        result = subprocess.run(command, input=SHELL.read_bytes() if shell else None,
                                env=self.env, capture_output=True, timeout=30)
        self.assertEqual(result.stderr, b"")
        return result.returncode, [json.loads(line) for line in result.stdout.splitlines()]

    def write_json(self, path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data), encoding="utf-8")

    def kimi(self, sid="same", cwd="/project", title="Kimi title", updated=NOW):
        root = Path(self.env.get("KIMI_CODE_HOME", self.home / ".kimi-code"))
        self.write_json(root / "sessions/wd" / ("session_" + sid) / "state.json",
                        {"id": sid, "title": title, "createdAt": NOW / 1000,
                         "updatedAt": updated, "cwd": cwd})
        return root

    def database(self, agent):
        path = (Path(self.env.get("XDG_DATA_HOME", self.home / ".local/share")) / "opencode/opencode.db"
                if agent == "opencode" else self.home / ".zcode/cli/db/db.sqlite")
        path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(path)
        self.connections.append(db)
        db.executescript("""
            PRAGMA journal_mode=WAL;
            PRAGMA wal_autocheckpoint=0;
            CREATE TABLE session (id TEXT PRIMARY KEY, title TEXT, directory TEXT,
                                  time_created INTEGER, time_updated INTEGER);
            CREATE TABLE message (id TEXT PRIMARY KEY, session_id TEXT, data TEXT,
                                  time_created INTEGER, sequence INTEGER);
            CREATE TABLE part (id TEXT PRIMARY KEY, session_id TEXT, message_id TEXT,
                               data TEXT, time_created INTEGER, sequence INTEGER);
            CREATE INDEX message_session ON message(session_id);
            CREATE INDEX part_message ON part(message_id);
            CREATE INDEX session_updated ON session(time_updated);
        """)
        db.execute("INSERT INTO session VALUES (?, ?, ?, ?, ?)",
                   ("same", agent + " title", "/project", NOW, NOW))
        db.commit()
        self.assertTrue(Path(str(path) + "-wal").is_file())
        return db, path

    def message(self, db, mid, sid="same", seq=1, time=NOW, role="user"):
        db.execute("INSERT INTO message VALUES (?, ?, ?, ?, ?)",
                   (mid, sid, json.dumps({"role": role}), time, seq))

    def part(self, db, pid, mid, content, sid="same", seq=1, time=NOW, kind="text"):
        db.execute("INSERT INTO part VALUES (?, ?, ?, ?, ?, ?)",
                   (pid, sid, mid, json.dumps({"type": kind, "text": content}), time, seq))

    def compress(self, data):
        executable = shutil.which("zstd")
        if executable:
            return subprocess.run([executable, "-q", "-c"], input=data,
                                  capture_output=True, check=True).stdout
        try:
            from compression import zstd
        except ImportError:
            self.skipTest("zstd or Python 3.14 required for compressed fixture")
        return zstd.compress(data)

    def dsh(self, sid="same", generation=10, lines=None):
        root = Path(self.env.get("DSH_HOME", self.home / ".dsh"))
        path = root / "sessions/--project--" / sid / ("session.v%d.jsonl.zstd" % generation)
        path.parent.mkdir(parents=True, exist_ok=True)
        header = {"type": "session", "id": sid, "cwd": "/project", "title": "DSH title",
                  "createdAt": NOW - 1000}
        events = lines if lines is not None else [
            {"type": "user/message", "time": NOW / 1000, "data": {"content": "first"}},
            {"type": "assistant/message", "time": NOW / 1000 + 1, "data": {"content": "reply"}},
            {"type": "user/message", "time": NOW / 1000 + 2,
             "data": {"content": [{"type": "text", "text": "last complete"},
                                  {"type": "text", "text": "second paragraph"}]}},
        ]
        path.write_bytes(b"".join(self.compress((json.dumps(event) + "\n").encode())
                                  for event in [header, *events]))
        return path

    def test_default_agents_and_same_id_are_not_merged(self):
        self.kimi()
        for agent in ("opencode", "zcode"):
            db, _ = self.database(agent)
            self.message(db, "m")
            self.part(db, "p", "m", agent + " preview")
            db.commit()
        self.dsh()
        code, rows = self.scan()
        self.assertEqual(code, 0)
        self.assertEqual({row["agent"] for row in rows}, set(scanner.AGENTS))
        self.assertEqual(len(rows), 4)
        self.assertTrue(all(row["sessionId"] == "same" for row in rows))
        self.assertTrue(all(row["cwd"].startswith("/") for row in rows))
        self.assertTrue(all(isinstance(row["updatedAt"], int) and row["updatedAt"] >= NOW
                            for row in rows))

    def test_sqlite_reads_live_wal_without_modifying_data(self):
        db, path = self.database("opencode")
        self.message(db, "m")
        self.part(db, "p", "m", "in WAL")
        db.commit()
        before = path.read_bytes()
        code, rows = self.scan("--agent", "opencode")
        self.assertEqual(code, 0)
        self.assertEqual(rows[0]["preview"], "in WAL")
        self.assertEqual(path.read_bytes(), before)
        self.assertEqual(db.execute("SELECT count(*) FROM session").fetchone()[0], 1)

    def test_sqlite_latest_full_user_text_not_tool_or_empty(self):
        db, _ = self.database("opencode")
        self.message(db, "m1", time=NOW)
        self.part(db, "p1", "m1", "full first", time=NOW)
        self.part(db, "p2", "m1", "full second", time=NOW + 1)
        self.message(db, "m2", time=NOW + 2, role="assistant")
        self.part(db, "p3", "m2", "assistant")
        self.message(db, "m3", time=NOW + 3)
        self.part(db, "p4", "m3", "tool", kind="tool")
        db.commit()
        _, rows = self.scan("--agent", "opencode")
        self.assertEqual(rows[0]["preview"], "full first\nfull second")

    def test_zcode_sequence_null_time_rowid_ordering(self):
        db, _ = self.database("zcode")
        self.message(db, "zzz", seq=90, time=NOW + 100)
        self.part(db, "old", "zzz", "not last")
        self.message(db, "a", seq=None, time=NOW)
        self.part(db, "a0", "a", "earlier null message")
        self.message(db, "b", seq=None, time=NOW)
        self.part(db, "z", "b", "seq 1", seq=1, time=NOW + 50)
        self.part(db, "y", "b", "null early", seq=None, time=NOW)
        self.part(db, "x", "b", "null later row", seq=None, time=NOW)
        self.part(db, "w", "b", "seq 0", seq=0, time=NOW + 100)
        db.commit()
        _, rows = self.scan("--agent", "zcode")
        self.assertEqual(rows[0]["preview"], "seq 0\nseq 1\nnull early\nnull later row")

    def test_sqlite_does_not_fetch_unselected_parts(self):
        db, _ = self.database("opencode")
        db.execute("INSERT INTO session VALUES ('older','old','/old',?,?)", (NOW, NOW - 1))
        self.message(db, "m", sid="older")
        db.execute("INSERT INTO part VALUES ('bad','older','m','NOT JSON',?,1)", (NOW,))
        db.commit()
        _, rows = self.scan("--agent", "opencode", "--limit", "1")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["sessionId"], "same")

    def test_isolation_bad_database_bad_session_and_other_agents(self):
        path = self.home / ".local/share/opencode/opencode.db"
        path.parent.mkdir(parents=True)
        path.write_bytes(b"invalid database")
        self.kimi()
        self.dsh()
        bad = self.dsh("bad")
        bad.write_bytes(b"bad compression")
        _, rows = self.scan()
        self.assertEqual({row["agent"] for row in rows if row.get("level") == "error"},
                         {"opencode", "dsh"})
        self.assertEqual({row["agent"] for row in rows if "sessionId" in row}, {"kimi", "dsh"})

    def test_sqlite_bad_row_does_not_hide_other_sessions(self):
        db, _ = self.database("zcode")
        db.execute("INSERT INTO session VALUES ('bad','bad','relative',?,?)", (NOW, NOW + 1))
        db.commit()
        _, rows = self.scan("--agent", "zcode")
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]["level"], "error")
        self.assertEqual(rows[1]["sessionId"], "same")

    def test_kimi_cli_contract_and_index_cwd(self):
        root = self.kimi()
        (root / "session_index.jsonl").write_text(
            json.dumps({"sessionId": "session_cli", "workDir": "/cli"}) + "\n")
        cli = self.bin / "kimi"
        cli.write_text("#!" + sys.executable + "\nimport json,sys\n"
                       "assert sys.argv[1:]==['session','list','--all','--json','--limit','3']\n"
                       "print(json.dumps([{'id':'cli','title':'CLI','createdAt':1790000000,"
                       "'updatedAt':'2026-09-22T00:00:00Z'}]))\n")
        cli.chmod(0o755)
        _, rows = self.scan("--agent", "kimi", "--limit", "3")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["cwd"], "/cli")
        self.assertEqual(rows[0]["sessionId"], "cli")
        self.assertEqual(rows[0]["createdAt"], NOW)

    def test_kimi_cli_failure_falls_back_to_state_and_index(self):
        root = self.kimi(cwd=None)
        (root / "session_index.jsonl").write_text(
            json.dumps({"sessionId": "session_same", "workDir": "/indexed"}) + "\n")
        cli = self.bin / "kimi"
        cli.write_text("#!/bin/sh\nexit 1\n")
        cli.chmod(0o755)
        _, rows = self.scan("--agent", "kimi")
        self.assertEqual(rows[0]["level"], "error")
        self.assertEqual(rows[1]["cwd"], "/indexed")

    def test_cli_timeout_and_output_bound(self):
        with mock.patch.object(scanner, "TIMEOUT", 0.1):
            with self.assertRaises(ValueError):
                scanner.cli_json([sys.executable, "-c", "import time; time.sleep(5)"])
            with self.assertRaises(ValueError):
                scanner.cli_json([sys.executable, "-c",
                                  "import os,time; os.fork(); time.sleep(5)"])
        with mock.patch.object(scanner, "MAX_LINE", 20):
            with self.assertRaises(ValueError):
                scanner.cli_json([sys.executable, "-c", "print('a'*100)"])

    def test_custom_roots_and_independent_databases(self):
        self.env.update(KIMI_CODE_HOME=str(self.home / "custom kimi"),
                        DSH_HOME=str(self.home / "custom dsh"),
                        XDG_DATA_HOME=str(self.home / "custom data"))
        self.kimi()
        self.dsh()
        self.database("opencode")
        _, rows = self.scan()
        self.assertEqual({row["agent"] for row in rows}, {"kimi", "opencode", "dsh"})

    def test_dsh_highest_numeric_generation_multiframe_and_ignored_tmp(self):
        path = self.dsh()
        old = self.dsh(generation=9, lines=[])
        self.assertTrue(old.is_file())
        (path.parent / "session.v99.jsonl.zstd.tmp").write_bytes(b"bad")
        (path.parent / "session.lock").write_bytes(b"bad")
        _, rows = self.scan("--agent", "dsh")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["preview"], "last complete\nsecond paragraph")
        self.assertEqual(rows[0]["messageCount"], 3)
        self.assertEqual(rows[0]["updatedAt"], NOW + 2000)

    def test_dsh_python_zstd_fallback_multiframe(self):
        try:
            from compression import zstd
        except ImportError:
            self.skipTest("Python 3.14 compression.zstd not available")
        self.dsh()
        broken = self.dsh("bad")
        broken.write_bytes(b"invalid zstd")
        (self.bin / "zstd").unlink(missing_ok=True)
        _, rows = self.scan("--agent", "dsh")
        self.assertEqual(rows[0]["level"], "error")
        self.assertEqual(rows[1]["preview"], "last complete\nsecond paragraph")

    def test_dsh_broken_json_and_truncated_frame_are_isolated(self):
        valid = self.dsh()
        broken = self.dsh("badjson")
        broken.write_bytes(self.compress(b"{\n"))
        truncated = self.dsh("truncated")
        truncated.write_bytes(valid.read_bytes()[:-4])
        _, rows = self.scan("--agent", "dsh")
        self.assertEqual(len([row for row in rows if row.get("level") == "error"]), 2)
        self.assertEqual([row["sessionId"] for row in rows if "sessionId" in row], ["same"])

    def test_json_lines_streaming_and_line_bound(self):
        class Lines(io.StringIO):
            def read(self, *args):
                raise AssertionError("whole-file read is forbidden")
        self.assertEqual(list(scanner.json_lines(Lines('{"a":1}\n{"b":2}\n'))),
                         [{"a": 1}, {"b": 2}])
        with mock.patch.object(scanner, "MAX_LINE", 10):
            with self.assertRaises(ValueError):
                list(scanner.json_lines(Lines('"' + "a" * 20 + '"\n')))

    def test_query_is_literal_case_insensitive_and_limit_applied_after_filter(self):
        db, _ = self.database("opencode")
        for i in range(5):
            db.execute("INSERT INTO session VALUES (?, ?, '/query', ?, ?)",
                       (str(i), "O'Reilly %_" if i < 2 else "other", NOW, NOW + i + 1))
        db.commit()
        _, rows = self.scan("--agent", "opencode", "--query", "o'REILLY %_", "--limit", "1")
        self.assertEqual([row["sessionId"] for row in rows], ["1"])
        _, rows = self.scan("--agent", "opencode", "--query", "' OR 1=1 --")
        self.assertEqual(rows, [])
        self.assertEqual(db.execute("SELECT count(*) FROM session").fetchone()[0], 6)

    def test_query_matches_preview_and_cwd(self):
        self.dsh()
        _, rows = self.scan("--agent", "dsh", "--query", "SECOND PARAGRAPH")
        self.assertEqual(len(rows), 1)
        _, rows = self.scan("--agent", "dsh", "--query", "/project")
        self.assertEqual(len(rows), 1)

    def test_limits_and_unknown_agent_are_json_errors(self):
        for value in ("0", "1001", "-1", "1;touch /tmp/should-not-exist", "no", "1.5"):
            code, rows = self.scan("--limit", value)
            self.assertEqual(code, 2)
            self.assertEqual(rows[0]["level"], "error")
        code, rows = self.scan("--agent", "omp")
        self.assertEqual(code, 2)
        self.assertEqual(rows[0]["agent"], "scanner")
        for value in ("1", "1000"):
            self.assertEqual(self.scan("--limit", value), (0, []))

    def test_kimi_bad_index_entry_and_relative_cwd_are_isolated(self):
        root = self.kimi(cwd=None)
        self.kimi("bad", cwd="relative")
        (root / "session_index.jsonl").write_text(
            "broken\n" + json.dumps({"sessionId": "session_same", "workDir": "/indexed"}) + "\n")
        _, rows = self.scan("--agent", "kimi")
        self.assertEqual(len([row for row in rows if row.get("level") == "error"]), 2)
        self.assertEqual([row["cwd"] for row in rows if "sessionId" in row], ["/indexed"])

    def test_sqlite_parameterized_session_id_and_large_complete_preview(self):
        db, _ = self.database("opencode")
        sid = "' OR 1=1; DROP TABLE part; --"
        db.execute("INSERT INTO session VALUES (?, 'quoted', '/project', ?, ?)",
                   (sid, NOW, NOW + 1))
        self.message(db, "quoted", sid=sid)
        content = "large complete text " * 10000
        self.part(db, "quoted", "quoted", content, sid=sid)
        db.commit()
        _, rows = self.scan("--agent", "opencode", "--limit", "1")
        self.assertEqual(rows[0]["sessionId"], sid)
        self.assertEqual(rows[0]["preview"], content)
        self.assertEqual(db.execute("SELECT count(*) FROM part").fetchone()[0], 1)

    def test_multiple_agent_syntax_and_deduplication(self):
        self.kimi()
        self.database("zcode")
        _, rows = self.scan("--agent", "kimi", "zcode", "--agent", "kimi")
        self.assertEqual([row["agent"] for row in rows], ["kimi", "zcode"])

    def test_shell_stdin_is_self_contained_and_payload_matches(self):
        payload = SHELL.read_text().split("<<'RMAGENTMA_PYTHON'\n", 1)[1].rsplit(
            "RMAGENTMA_PYTHON\n", 1)[0]
        self.assertEqual(payload.encode(), SCRIPT.read_bytes())
        self.kimi(title="literal $(touch nope) ' quoted")
        expected = self.scan("--agent", "kimi", "--query", "$(touch nope)")
        self.assertEqual(self.scan("--agent", "kimi", "--query", "$(touch nope)", shell=True), expected)
        self.assertFalse((self.home / "nope").exists())

    @unittest.skipUnless(os.environ.get("RMAGENTMA_SSH_TEST") == "1",
                         "set RMAGENTMA_SSH_TEST=1 to run local OpenSSH integration")
    def test_local_openssh_stdin_scanner(self):
        import hashlib
        import pwd
        import shlex
        import socket
        import time

        if os.geteuid() == 0:
            self.skipTest("integration must run as a non-root user")
        tools = {name: shutil.which(name) for name in ("sshd", "ssh", "ssh-keygen")}
        if not all(tools.values()):
            self.skipTest("local OpenSSH server, client and ssh-keygen are required")
        workspace = SCRIPT.parents[6]
        with tempfile.TemporaryDirectory(prefix=".scanner-ssh-", dir=workspace) as temporary:
            fixture = Path(temporary)
            self.home = fixture / "home"
            self.home.mkdir(mode=0o700)
            self.bin = self.home / "bin"
            self.bin.mkdir()
            os.symlink(sys.executable, self.bin / "python3")
            zstd = shutil.which("zstd")
            if zstd:
                os.symlink(zstd, self.bin / "zstd")
            self.env = {"HOME": str(self.home), "PATH": str(self.bin), "LC_ALL": "C.UTF-8"}
            self.kimi()
            for agent in ("opencode", "zcode"):
                db, _ = self.database(agent)
                self.message(db, "m")
                self.part(db, "p", "m", agent + " SSH preview")
                db.commit()
                db.close()
            self.dsh()
            storage_roots = [self.home / name for name in
                             (".kimi-code", ".local/share", ".zcode", ".dsh")]

            def storage_hashes():
                return {str(path.relative_to(self.home)): hashlib.sha256(path.read_bytes()).hexdigest()
                        for root in storage_roots for path in root.rglob("*") if path.is_file()}

            before = storage_hashes()
            for name in ("host", "client"):
                subprocess.run([tools["ssh-keygen"], "-q", "-t", "ed25519", "-N", "",
                                "-f", str(fixture / name)], env=self.env, capture_output=True,
                               timeout=10, check=True)
            authorized = fixture / "authorized_keys"
            authorized.write_bytes((fixture / "client.pub").read_bytes())
            authorized.chmod(0o600)
            with socket.socket() as reservation:
                reservation.bind(("127.0.0.1", 0))
                port = reservation.getsockname()[1]
            self.assertGreater(port, 1024)
            host_public = (fixture / "host.pub").read_text().split()
            known_hosts = fixture / "known_hosts"
            known_hosts.write_text("[127.0.0.1]:%d %s %s\n" %
                                  (port, host_public[0], host_public[1]))
            account = pwd.getpwuid(os.geteuid()).pw_name
            config = fixture / "sshd_config"
            config.write_text("\n".join([
                "Port %d" % port,
                "ListenAddress 127.0.0.1",
                'HostKey "%s"' % (fixture / "host"),
                'PidFile "%s"' % (fixture / "sshd.pid"),
                'AuthorizedKeysFile "%s"' % authorized,
                "AllowUsers " + account,
                "AuthenticationMethods publickey",
                "PubkeyAuthentication yes",
                "PasswordAuthentication no",
                "KbdInteractiveAuthentication no",
                "PermitRootLogin no",
                "UsePAM no",
                "StrictModes yes",
                "AllowAgentForwarding no",
                "AllowTcpForwarding no",
                "X11Forwarding no",
                "PermitTunnel no",
                "PermitTTY no",
                "PermitUserRC no",
                "PermitUserEnvironment no",
                "PrintMotd no",
                'SetEnv HOME="%s" PATH="%s" XDG_CONFIG_HOME="%s"' %
                (self.home, self.bin, self.home / ".config"),
                "LogLevel VERBOSE",
                "",
            ]))
            checked = subprocess.run([tools["sshd"], "-t", "-f", str(config)],
                                     env=self.env, capture_output=True, timeout=10)
            if checked.returncode:
                self.skipTest("non-root sshd configuration rejected: " + checked.stderr.decode())
            log_path = fixture / "sshd.log"
            process = None
            with log_path.open("wb") as log:
                try:
                    process = subprocess.Popen([tools["sshd"], "-D", "-e", "-f", str(config)],
                                               env=self.env, stdin=subprocess.DEVNULL,
                                               stdout=log, stderr=log)
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline:
                        if process.poll() is not None:
                            self.skipTest("non-root sshd startup failed: " + log_path.read_text())
                        try:
                            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                                break
                        except OSError:
                            time.sleep(0.05)
                    else:
                        self.fail("local sshd did not listen: " + log_path.read_text())
                    command = [tools["ssh"], "-T", "-F", "/dev/null", "-p", str(port),
                               "-i", str(fixture / "client"),
                               "-o", "IdentitiesOnly=yes", "-o", "IdentityAgent=none",
                               "-o", "PreferredAuthentications=publickey",
                               "-o", "PasswordAuthentication=no", "-o", "BatchMode=yes",
                               "-o", "StrictHostKeyChecking=yes",
                               "-o", "UserKnownHostsFile=" + str(known_hosts),
                               "-o", "GlobalKnownHostsFile=/dev/null",
                               "-o", "ConnectTimeout=5", account + "@127.0.0.1"]
                    environment = ["HOME=" + str(self.home), "PATH=" + str(self.bin),
                                   "XDG_CONFIG_HOME=" + str(self.home / ".config"),
                                   "LC_ALL=C.UTF-8"]
                    remote = shlex.join(["/usr/bin/env", "-i", *environment, "/bin/bash", "-c",
                                         "printf '%s\\n' scanner-stderr-marker >&2; "
                                         "exec /bin/bash -s -- --agent kimi opencode zcode dsh --limit 5"])
                    for attempt in range(2):
                        result = subprocess.run([*command, remote], input=SHELL.read_bytes(),
                                                env=self.env, capture_output=True, timeout=30)
                        server_log = log_path.read_text()
                        if result.returncode == 255 and any(reason in server_log for reason in (
                                "Authentication refused: bad ownership or modes",
                                "User not allowed because account is locked",
                                "privilege separation", "Operation not permitted")):
                            self.skipTest("non-root OpenSSH security restriction (not relaxed): "
                                          + result.stderr.decode() + "\n" + server_log)
                        self.assertEqual(result.returncode, 0,
                                         result.stderr.decode() + "\n" + server_log)
                        self.assertEqual(result.stderr, b"scanner-stderr-marker\n")
                        rows = [json.loads(line) for line in result.stdout.splitlines()]
                        self.assertEqual(len(rows), 4)
                        self.assertEqual({row["agent"] for row in rows}, set(scanner.AGENTS))
                        self.assertTrue(all(row["sessionId"] == "same" for row in rows))
                        self.assertTrue(all(row["cwd"] == "/project" for row in rows))
                        by_agent = {row["agent"]: row for row in rows}
                        self.assertEqual(by_agent["opencode"]["preview"], "opencode SSH preview")
                        self.assertEqual(by_agent["zcode"]["preview"], "zcode SSH preview")
                        self.assertEqual(by_agent["dsh"]["preview"], "last complete\nsecond paragraph")
                        self.assertEqual(storage_hashes(), before, "storage changed on exec %d" % attempt)
                finally:
                    if process is not None and process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=5)

    def test_shell_missing_python_reports_json_error(self):
        (self.bin / "python3").unlink()
        code, rows = self.scan(shell=True)
        self.assertEqual(code, 127)
        self.assertEqual(rows[0]["level"], "error")
        self.assertIn("python3 is required", rows[0]["message"])


if __name__ == "__main__":
    unittest.main()
