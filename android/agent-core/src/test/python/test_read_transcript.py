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
spec = importlib.util.spec_from_file_location("read_transcript", REMOTE / "read_transcript.py")
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


class TranscriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.env = dict(os.environ, HOME=str(self.home), DSH_HOME=str(self.home / ".dsh"))
        self.db = None
        self.addCleanup(lambda: self.db.close() if self.db else None)

    def read(self, agent="dsh", sid="same", cwd="/project", limit=100, cursor=None, host=7):
        args = [sys.executable, str(REMOTE / "read_transcript.py"), "--host-id", str(host),
                "--agent", agent, "--session-id", sid, "--cwd", cwd, "--limit", str(limit)]
        if cursor is not None:
            args += ["--cursor", cursor]
        result = subprocess.run(args, env=self.env, capture_output=True, timeout=40)
        self.assertEqual(result.stderr, b"")
        rows = [json.loads(line) for line in result.stdout.splitlines()]
        return result.returncode, rows

    def compress(self, data):
        executable = shutil.which("zstd")
        if executable:
            return subprocess.run([executable, "-q", "-c"], input=data,
                                  capture_output=True, check=True).stdout
        try:
            from compression import zstd
        except ImportError:
            self.skipTest("zstd or Python 3.14 required")
        return zstd.compress(data)

    def dsh(self, events=None, generation=10, sid="same", cwd="/project", project="--project--"):
        path = self.home / ".dsh/sessions" / project / sid / ("session.v%d.jsonl.zstd" % generation)
        path.parent.mkdir(parents=True, exist_ok=True)
        values = [{"type": "session", "id": sid, "cwd": cwd, "version": 4}, *(events or [])]
        path.write_bytes(b"".join(self.compress((json.dumps(value) + "\n").encode()) for value in values))
        return path

    def events(self, texts):
        return [{"type": "user/message" if i % 2 == 0 else "assistant/message", "seq": i,
                 "data": {"content": value}} for i, value in enumerate(texts)]

    def database(self):
        path = self.home / ".zcode/cli/db/db.sqlite"
        path.parent.mkdir(parents=True)
        self.db = sqlite3.connect(path)
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            PRAGMA wal_autocheckpoint=0;
            CREATE TABLE session (id TEXT PRIMARY KEY, directory TEXT, time_updated INTEGER);
            CREATE TABLE message (id TEXT PRIMARY KEY, session_id TEXT, data TEXT,
                                  sequence INTEGER, time_created INTEGER);
            CREATE TABLE part (id TEXT PRIMARY KEY, session_id TEXT, message_id TEXT,
                               data TEXT, sequence INTEGER, time_created INTEGER);
            CREATE INDEX message_session ON message(session_id);
            CREATE INDEX part_session ON part(session_id, message_id);
            INSERT INTO session VALUES ('same','/project',1);
            INSERT INTO session VALUES ('other','/other',2);
        """)
        self.db.commit()
        return path

    def message(self, mid, seq, body, sid="same", role="assistant", kind="text"):
        self.db.execute("INSERT INTO message VALUES (?,?,?,?,1)",
                        (mid, sid, json.dumps({"role": role}), seq))
        self.db.execute("INSERT INTO part VALUES (?,?,?,?,?,1)",
                        (mid, sid, mid, json.dumps({"type": kind, "text": body}), seq))
        self.db.commit()

    def test_highest_generation_multiframe_readonly_and_exact_page_boundaries(self):
        path = self.dsh(self.events(["one", "two", "three", "four"]))
        self.dsh(self.events(["OLD"]), generation=9)
        (path.parent / "session.v99.jsonl.zstd.tmp").write_bytes(b"invalid")
        before = path.read_bytes()
        code, first = self.read(limit=2)
        self.assertEqual(code, 0)
        self.assertEqual([r["text"] for r in first[:-1]], ["one", "two"])
        self.assertTrue(first[-1]["nextCursor"])
        code, second = self.read(limit=2, cursor=first[-1]["nextCursor"])
        self.assertEqual(code, 0)
        self.assertEqual([r["text"] for r in second[:-1]], ["three", "four"])
        self.assertEqual(second[-1], {"record": "page", "nextCursor": "", "count": 2})
        self.assertEqual(path.read_bytes(), before)
        self.assertEqual({r["hostId"] for r in first[:-1]}, {7})

    def test_cwd_id_host_and_cursor_isolation(self):
        self.dsh(self.events(["secret", "two"]))
        self.dsh(self.events(["other cwd"]), cwd="/other", project="--other--")
        for cwd in ("/project/", "/missing", "relative"):
            code, rows = self.read(cwd=cwd)
            self.assertEqual(code, 1)
            self.assertTrue(all(row["record"] == "error" for row in rows))
        code, rows = self.read(cwd="/other")
        self.assertEqual(code, 0)
        self.assertEqual(rows[0]["text"], "other cwd")
        _, first = self.read(limit=1)
        for kwargs in ({"cwd": "/other"}, {"host": 8}):
            code, rows = self.read(cursor=first[-1]["nextCursor"], **kwargs)
            self.assertEqual(code, 1)
            self.assertEqual(rows[0]["error"], "cursor_identity_mismatch")
        for sid in ("../same", "/etc/passwd", "..", "same/../same"):
            self.assertEqual(self.read(sid=sid)[0], 1)

    def test_dsh_symlinks_and_ambiguous_header_rejected(self):
        path = self.dsh(self.events(["private"]))
        original = path.parent / "backup"
        path.rename(original)
        path.symlink_to(original)
        code, rows = self.read()
        self.assertEqual(code, 1)
        self.assertEqual(rows[0]["error"], "symlink_rejected")
        path.unlink()
        original.rename(path)
        self.dsh(self.events(["duplicate"]), project="duplicate")
        code, rows = self.read()
        self.assertEqual(code, 1)
        self.assertEqual(rows[0]["error"], "ambiguous_session")

    def test_full_body_chunked_without_loss_and_history_never_approves(self):
        body = "汉字😀\n" * 6000
        self.dsh([*self.events([body]), {"type": "permission/request", "data": {"options": ["allow"]}},
                  {"type": "tool/call", "seq": 2, "data": {"name": "read", "arguments": {"path": "a"}}}])
        texts = []
        cursor = None
        all_rows = []
        while True:
            code, rows = self.read(limit=1, cursor=cursor)
            self.assertEqual(code, 0)
            all_rows.extend(rows[:-1])
            texts.extend(row["text"] for row in rows[:-1] if row.get("id") == "0")
            cursor = rows[-1]["nextCursor"]
            if not cursor:
                break
        self.assertEqual("".join(texts), body)
        self.assertTrue(all(row["type"] in ("user", "content", "tool") for row in all_rows))
        self.assertTrue(all("options" not in row and "raw" not in row for row in all_rows))
        self.assertEqual(all_rows[-1]["status"], "historical")

    def test_verified_dsh_v4_nested_message_and_reasoning_schema(self):
        self.dsh([{"type": "assistant/message", "seq": 8, "data": {
            "message": {"role": "assistant", "id": "m", "content": [
                {"type": "reasoning", "text": "thought"},
                {"type": "text", "text": "answer"},
                {"type": "tool-call", "id": "t", "name": "read", "arguments": {}}]}}},
            {"type": "tool/call", "seq": 9, "data": {"callId": "t", "name": "read", "arguments": {}}},
            {"type": "tool/result", "seq": 10, "data": {"message": {
                "role": "tool", "toolCallId": "t", "content": [{"type": "text", "text": "output"}]}}}])
        code, rows = self.read()
        self.assertEqual(code, 0)
        self.assertEqual([row["type"] for row in rows[:-1]], ["thinking", "content", "tool", "tool_update"])
        self.assertEqual([row["text"] for row in rows[:2]], ["thought", "answer"])
        self.assertTrue(all("options" not in row for row in rows))

    def test_verified_zcode_reasoning_part_is_historical_thinking(self):
        self.database()
        self.message("m", 1, "thought", kind="reasoning")
        code, rows = self.read(agent="zcode")
        self.assertEqual(code, 0)
        self.assertEqual(rows[0]["type"], "thinking")
        self.assertEqual(rows[0]["text"], "thought")

    def test_source_change_invalidates_cursor_and_bad_cursor_errors(self):
        path = self.dsh(self.events(["one", "two"]))
        _, first = self.read(limit=1)
        path.write_bytes(path.read_bytes() + self.compress(b'{"type":"user/message","data":"three"}\n'))
        code, rows = self.read(cursor=first[-1]["nextCursor"])
        self.assertEqual((code, rows[0]["error"]), (1, "stale_cursor"))
        for value in ("invalid", "x" * 2049):
            self.assertEqual(self.read(cursor=value)[0], 1)

    def test_sqlite_ro_live_wal_sequence_and_selected_session_only(self):
        path = self.database()
        self.message("z", 2, "second")
        self.message("a", 1, "first", role="user")
        self.message("null", None, "last")
        self.message("bad", 0, "ignored", sid="other")
        self.db.execute("UPDATE part SET data='INVALID' WHERE session_id='other'")
        self.db.commit()
        before = path.read_bytes()
        wal = Path(str(path) + "-wal").read_bytes()
        code, rows = self.read(agent="zcode", limit=2)
        self.assertEqual(code, 0)
        self.assertEqual([row["text"] for row in rows[:-1]], ["first", "second"])
        code, end = self.read(agent="zcode", cursor=rows[-1]["nextCursor"])
        self.assertEqual(code, 0)
        self.assertEqual(end[0]["text"], "last")
        self.assertEqual(path.read_bytes(), before)
        self.assertEqual(Path(str(path) + "-wal").read_bytes(), wal)
        self.assertEqual(self.db.execute("SELECT count(*) FROM message").fetchone()[0], 4)
        self.assertEqual(self.read(agent="zcode", cwd="/other")[0], 1)
        self.assertEqual(self.read(agent="zcode", sid="' OR 1=1 --")[0], 1)

    def test_sqlite_parts_sequence_null_and_tool_are_facts(self):
        self.database()
        self.message("m", 1, "second", kind="tool")
        for pid, seq, content in (("p3", None, "last"), ("p1", 0, "first")):
            self.db.execute("INSERT INTO part VALUES (?,?,?,?,?,1)",
                            (pid, "same", "m", json.dumps({"type": "text", "text": content}), seq))
        self.db.commit()
        code, rows = self.read(agent="zcode")
        self.assertEqual(code, 0)
        self.assertEqual([row["type"] for row in rows[:-1]], ["content", "tool", "content"])
        self.assertEqual(rows[0]["text"], "first")
        self.assertEqual(rows[2]["text"], "last")

    def test_invalid_json_compression_truncation_and_missing_zstd(self):
        path = self.dsh()
        path.write_bytes(self.compress(b'{"type":"session","id":"same","cwd":"/project"}\n{\n'))
        self.assertEqual(self.read()[0], 1)
        path.write_bytes(b"bad compression")
        self.assertEqual(self.read()[0], 1)
        valid = self.dsh(self.events(["reply"]))
        valid.write_bytes(valid.read_bytes()[:-4])
        self.assertEqual(self.read()[0], 1)
        try:
            from compression import zstd
        except ImportError:
            with mock.patch.object(reader.shutil, "which", return_value=None):
                with self.assertRaisesRegex(reader.HistoryError, "zstd_missing"):
                    with reader.decompressed(path):
                        pass

    def test_python_zstd_multiframe_fallback(self):
        try:
            from compression import zstd
        except ImportError:
            self.skipTest("Python 3.14 unavailable")
        path = self.dsh(self.events(["one", "two"]))
        with mock.patch.object(reader.shutil, "which", return_value=None):
            with reader.decompressed(path) as stream:
                self.assertEqual(len(list(reader.json_lines(stream))), 3)

    def test_zstd_cli_multiframe_and_truncated_stream(self):
        if not shutil.which("zstd"):
            self.skipTest("zstd CLI unavailable")
        path = self.dsh(self.events(["one", "two"]))
        with mock.patch.dict(sys.modules, {"compression": None, "compression.zstd": None}):
            with reader.decompressed(path) as stream:
                self.assertEqual(len(list(reader.json_lines(stream))), 3)
            path.write_bytes(path.read_bytes()[:-4])
            with self.assertRaisesRegex(reader.HistoryError, "invalid_zstd"):
                with reader.decompressed(path) as stream:
                    list(reader.json_lines(stream))

    def test_page_byte_bound_and_exact_cursor_continuation(self):
        self.dsh(self.events(["汉" * 18000]))
        args = type("Args", (), {"host_id": 7, "agent": "dsh", "session_id": "same",
                                  "cwd": "/project", "limit": 200, "cursor": ""})()
        output = io.StringIO()
        with mock.patch.dict(os.environ, self.env), mock.patch.object(reader, "MAX_PAGE_BYTES", 26000), mock.patch("sys.stdout", output):
            reader.page(args)
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertLessEqual(sum(len((json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n").encode())
                                 for row in rows[:-1]), 26000)
        self.assertTrue(rows[-1]["nextCursor"])
        code, end = self.read(cursor=rows[-1]["nextCursor"])
        self.assertEqual(code, 0)
        self.assertEqual("".join(row["text"] for row in rows[:-1] + end[:-1]), "汉" * 18000)

    def test_stream_line_bound_and_no_whole_file_reads(self):
        class Stream(io.StringIO):
            def read(self, *args):
                raise AssertionError("whole-file read")
        self.assertEqual(list(reader.json_lines(Stream('{"a":1}\n'))), [{"a": 1}])
        with mock.patch.object(reader, "MAX_LINE", 10):
            with self.assertRaisesRegex(reader.HistoryError, "input_line_limit"):
                list(reader.json_lines(Stream('"' + "a" * 11 + '"\n')))

    def test_sqlite_symlink_and_missing_schema_errors(self):
        path = self.database()
        original = path.parent / "elsewhere.sqlite"
        path.rename(original)
        path.symlink_to(original)
        code, rows = self.read(agent="zcode")
        self.assertEqual((code, rows[0]["error"]), (1, "symlink_rejected"))
        path.unlink()
        original.rename(path)
        self.db.execute("ALTER TABLE part RENAME COLUMN sequence TO invalid")
        self.db.commit()
        code, rows = self.read(agent="zcode")
        self.assertEqual((code, rows[0]["error"]), (1, "unsupported_schema"))


if __name__ == "__main__":
    unittest.main()
