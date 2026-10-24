# Copyright 2026 rmAgentMa contributors
# SPDX-License-Identifier: Apache-2.0

import base64
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

REMOTE = Path(__file__).resolve().parents[2] / "main/resources/remote"
SCRIPT = REMOTE / "search_transcripts.py"
spec = importlib.util.spec_from_file_location("search_transcripts", SCRIPT)
searcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(searcher)


class SearchTranscriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def transcript(self, name, lines, directory=None):
        path = (directory or self.root) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("".join(line + "\n" for line in lines), encoding="utf-8")
        return path

    def scope(self, directory, files, agent="dsh", session="same"):
        return {"agent": agent, "sessionId": session, "directory": str(directory), "files": list(files)}

    def arguments(self, query, scopes, **options):
        arguments = ["--query-b64", base64.b64encode(query.encode()).decode()]
        for scope in scopes:
            arguments += ["--scope-b64", base64.b64encode(json.dumps(scope).encode()).decode()]
        for key, value in options.items():
            arguments += ["--" + key.replace("_", "-"), str(value)]
        return arguments

    def run_search(self, query, scopes, **options):
        command = [sys.executable, str(SCRIPT)] + self.arguments(query, scopes, **options)
        result = subprocess.run(command, capture_output=True, timeout=40)
        self.assertEqual(b"", result.stderr)
        return result.returncode, [json.loads(line) for line in result.stdout.splitlines()]

    def run_in_process(self, arguments, denied=None):
        """Run one request here, where a candidate can be made unreadable on purpose instead of by permission."""
        real_open = open

        def guarded(path, *args, **kwargs):
            if denied is not None and str(path).endswith(denied):
                raise PermissionError("denied for the test")
            return real_open(path, *args, **kwargs)

        output = io.StringIO()
        with mock.patch("builtins.open", side_effect=guarded), contextlib.redirect_stdout(output):
            code = searcher.main(arguments)
        return code, [json.loads(line) for line in output.getvalue().splitlines()]

    def compress(self, data):
        executable = shutil.which("zstd")
        if executable:
            return subprocess.run([executable, "-q", "-c"], input=data, capture_output=True, check=True).stdout
        try:
            from compression import zstd
        except ImportError:
            self.skipTest("zstd or Python 3.14 required")
        return zstd.compress(data)

    def test_chinese_and_ascii_hits_report_anchors_character_offsets_and_bounded_snippets(self):
        path = self.transcript("session.v10.jsonl", [
            '{"type":"user/message","seq":0,"data":{"content":"hello world"}}',
            '{"type":"assistant/message","seq":1,"data":{"content":"项目会议记录：中文关键词在这里"}}',
        ])
        before = path.read_bytes()
        code, rows = self.run_search("关键词", [self.scope(self.root, ["session.v10.jsonl"])], max_snippet=20)
        self.assertEqual(0, code)
        summary = rows[-1]
        self.assertEqual({"record": "search", "scopes": 1, "files": 1, "read": 1, "matches": 1,
                          "truncated": False, "unreadable": 0, "timedOut": False},
                         {key: value for key, value in summary.items() if key != "bytes"})
        self.assertEqual(1, len(rows) - 1)
        hit = rows[0]
        self.assertEqual("dsh:same", hit["sessionKey"])
        self.assertEqual("dsh", hit["agent"])
        self.assertEqual("same", hit["sessionId"])
        self.assertEqual("session.v10.jsonl", hit["file"])
        self.assertEqual(2, hit["line"])
        self.assertLessEqual(len(hit["snippet"]), 20)
        self.assertIn("关键词", hit["snippet"])
        self.assertTrue(hit["clipped"])
        # The offset counts characters of the stored line, not bytes, so it points at the match itself.
        line = path.read_text(encoding="utf-8").splitlines()[1]
        self.assertEqual("关键词", line[hit["offset"]:hit["offset"] + 3])
        self.assertEqual(before, path.read_bytes())

    def test_matching_ignores_case_and_snippets_carry_no_control_characters(self):
        line = "escape \u001b[31mMeeting\tinside\u001b[0m one line"
        path = self.transcript("chat.jsonl", [line])
        code, rows = self.run_search("MEETING", [self.scope(self.root, ["chat.jsonl"])], max_snippet=40)
        self.assertEqual(0, code)
        hit = rows[0]
        self.assertIn("Meeting", hit["snippet"])
        self.assertNotIn("\t", hit["snippet"])
        self.assertNotIn("\u001b", hit["snippet"])
        self.assertEqual("escape  [31mMeeting inside [0m one line", hit["snippet"])
        self.assertLessEqual(len(hit["snippet"]), 40)
        self.assertEqual(1, rows[-1]["matches"])
        self.assertFalse(rows[-1]["truncated"])
        self.assertEqual((line + "\n").encode(), path.read_bytes())

    def test_a_query_that_is_not_there_is_no_hit_and_still_no_error(self):
        self.transcript("chat.jsonl", ["one line about the parser", "another line"])
        empty = self.root / "empty.jsonl"
        empty.write_bytes(b"")
        for files in (["chat.jsonl"], ["empty.jsonl"], ["chat.jsonl", "empty.jsonl"]):
            code, rows = self.run_search("zzz", [self.scope(self.root, files)])
            self.assertEqual(0, code)
            self.assertEqual(["search"], [row["record"] for row in rows])
            self.assertEqual(0, rows[-1]["matches"])
            self.assertEqual(len(files), rows[-1]["read"])
            self.assertFalse(rows[-1]["truncated"])
            self.assertEqual(0, rows[-1]["unreadable"])

    def test_every_limit_stops_the_scan_and_says_so(self):
        self.transcript("first.jsonl", ["hello one", "hello two", "hello three"])
        self.transcript("second.jsonl", ["hello four"])
        both = self.scope(self.root, ["first.jsonl", "second.jsonl"])
        code, rows = self.run_search("hello", [both], max_matches=1)
        self.assertEqual(0, code)
        self.assertEqual(1, rows[-1]["matches"])
        self.assertTrue(rows[-1]["truncated"])
        self.assertEqual(1, len(rows) - 1)
        code, rows = self.run_search("hello", [both], max_files=1)
        self.assertEqual(0, code)
        self.assertEqual(2, rows[-1]["files"])
        self.assertEqual(1, rows[-1]["read"])
        self.assertTrue(rows[-1]["truncated"])
        self.transcript("big.jsonl", ["padding hello " + "x" * 120 for _ in range(60)])
        code, rows = self.run_search("hello", [self.scope(self.root, ["big.jsonl"])], max_bytes=1024, max_matches=100)
        self.assertEqual(0, code)
        self.assertTrue(rows[-1]["truncated"])
        self.assertGreaterEqual(rows[-1]["bytes"], 1024)
        self.assertLess(rows[-1]["matches"], 60)

    def test_unreadable_candidates_are_counted_without_stopping_the_search(self):
        self.transcript("readable.jsonl", ["hello from the readable file"])
        (self.root / "folder").mkdir()
        (self.root / "link.jsonl").symlink_to(self.root / "readable.jsonl")
        scope = self.scope(self.root, ["readable.jsonl", "missing.jsonl", "folder", "link.jsonl"])
        code, rows = self.run_search("hello", [scope])
        self.assertEqual(0, code)
        self.assertEqual(1, rows[-1]["matches"])
        self.assertEqual(1, rows[-1]["read"])
        self.assertEqual(3, rows[-1]["unreadable"])
        self.assertFalse(rows[-1]["truncated"])
        self.transcript("denied.jsonl", ["hello from a file nobody may open"])
        code, rows = self.run_in_process(
            self.arguments("hello", [self.scope(self.root, ["readable.jsonl", "denied.jsonl"])]),
            denied="denied.jsonl",
        )
        self.assertEqual(0, code)
        self.assertEqual(1, rows[-1]["matches"])
        self.assertEqual(1, rows[-1]["read"])
        self.assertEqual(1, rows[-1]["unreadable"])

    def test_requests_this_script_refuses_answer_with_one_error_row(self):
        self.transcript("chat.jsonl", ["hello"])
        file = ["chat.jsonl"]
        for query, category, scopes in (
            ("", "search_query_empty", [self.scope(self.root, file)]),
            ("x" * 300, "search_query_invalid", [self.scope(self.root, file)]),
            ("hello", "search_scope_invalid", [self.scope("relative/directory", file)]),
            ("hello", "search_scope_invalid", [self.scope(self.root, ["../escape.jsonl"])]),
            ("hello", "search_scope_invalid", [self.scope(self.root, [""])]),
            ("hello", "search_scope_invalid", []),
        ):
            code, rows = self.run_search(query, scopes)
            self.assertEqual(1, code)
            self.assertEqual([{"record": "error", "level": "error", "error": category}], rows)
        for scope in (base64.b64encode(b"not json").decode(), base64.b64encode(b"[]").decode()):
            command = [sys.executable, str(SCRIPT), "--query-b64", base64.b64encode(b"hello").decode(),
                       "--scope-b64", scope]
            result = subprocess.run(command, capture_output=True, timeout=40)
            self.assertEqual(b"", result.stderr)
            self.assertEqual(1, result.returncode)
            self.assertEqual({"record": "error", "level": "error", "error": "search_scope_invalid"},
                             json.loads(result.stdout))
        code, rows = self.run_search("hello", [self.scope(self.root, file)], timeout=0)
        self.assertEqual(0, code)
        self.assertEqual(["search"], [row["record"] for row in rows])
        self.assertEqual(1, rows[-1]["matches"])
        self.assertFalse(rows[-1]["timedOut"])

    def test_one_request_spans_sessions_and_leaves_every_candidate_untouched(self):
        first = self.transcript("sessions/one/session.v1.jsonl", ["first session mentions 检索 here"])
        second = self.transcript("sessions/two/session.v1.jsonl", ["second session mentions 检索 too"])
        before = {path: (path.read_bytes(), path.stat().st_mtime_ns) for path in (first, second)}
        code, rows = self.run_search("检索", [
            self.scope(first.parent, [first.name], session="one"),
            self.scope(second.parent, [second.name], session="two"),
        ])
        self.assertEqual(0, code)
        self.assertEqual(2, rows[-1]["scopes"])
        self.assertEqual(2, rows[-1]["matches"])
        self.assertEqual({"dsh:one", "dsh:two"}, {row["sessionKey"] for row in rows if row["record"] == "match"})
        self.assertEqual({str(first), str(second)},
                         {os.path.join(row["directory"], row["file"]) for row in rows if row["record"] == "match"})
        for path, (data, stamp) in before.items():
            self.assertEqual(data, path.read_bytes())
            self.assertEqual(stamp, path.stat().st_mtime_ns)

    def test_compressed_transcripts_are_read_when_the_host_can_decompress_them(self):
        path = self.root / "session.v1.jsonl.zstd"
        path.write_bytes(self.compress('{"type":"user/message","seq":0,"data":{"content":"压缩的中文 hello"}}\n'
                                       .encode()))
        before = path.read_bytes()
        code, rows = self.run_search("中文", [self.scope(self.root, [path.name])])
        self.assertEqual(0, code)
        self.assertEqual(1, rows[-1]["matches"])
        self.assertEqual(1, rows[-1]["read"])
        self.assertEqual(1, rows[0]["line"])
        self.assertIn("中文", rows[0]["snippet"])
        self.assertEqual(before, path.read_bytes())

    def test_a_compressed_candidate_with_no_decompressor_is_unreadable_instead_of_empty(self):
        path = self.root / "session.v1.jsonl.zstd"
        path.write_bytes(b"not a zstd stream at all")
        with mock.patch.dict(sys.modules, {"compression": None, "compression.zstd": None}), \
                mock.patch("shutil.which", return_value=None):
            code, rows = self.run_in_process(self.arguments("hello", [self.scope(self.root, [path.name])]))
        self.assertEqual(0, code)
        self.assertEqual(0, rows[-1]["matches"])
        self.assertEqual(0, rows[-1]["read"])
        self.assertEqual(1, rows[-1]["unreadable"])


if __name__ == "__main__":
    unittest.main()
