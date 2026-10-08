#!/usr/bin/env bash
# Copyright 2026 rmAgentMa contributors
# SPDX-License-Identifier: Apache-2.0
# Self-contained stdin transport. Keep the embedded source identical to scan_sessions.py.
if ! command -v python3 >/dev/null 2>&1; then
    printf '%s\n' '{"level":"error","agent":"scanner","message":"python3 is required; no all-CLI fallback is implemented"}'
    exit 127
fi
exec python3 - "$@" <<'RMAGENTMA_PYTHON'
#!/usr/bin/env python3
# Copyright 2026 rmAgentMa contributors
# SPDX-License-Identifier: Apache-2.0
"""Read remote session metadata; stdout is NDJSON, including isolated errors."""

import argparse
import contextlib
import datetime
import io
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import threading

AGENTS = ("kimi", "opencode", "zcode", "dsh")
TIMEOUT = 15
MAX_LINE = 32 * 1024 * 1024


def emit(value):
    print(json.dumps(value, ensure_ascii=False, separators=(",", ":")), flush=True)


def error(agent, message):
    emit({"level": "error", "agent": agent, "message": message})


def millis(value, fallback=0):
    if value is None or value == "":
        return fallback
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return int(value if abs(value) >= 100_000_000_000 else value * 1000)
    if isinstance(value, str):
        try:
            return millis(float(value), fallback)
        except ValueError:
            parsed = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=datetime.timezone.utc)
            return int(parsed.timestamp() * 1000)
    raise ValueError("invalid timestamp")


def text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(filter(None, (text(item) for item in value)))
    if isinstance(value, dict):
        if value.get("type") in ("tool", "tool_use", "tool_result", "reasoning"):
            return ""
        for key in ("text", "content", "message"):
            if key in value:
                return text(value[key])
    return ""


def record(agent, sid, title, cwd, created, updated, preview="", count=None):
    if not isinstance(sid, str) or not sid:
        raise ValueError("missing session id")
    if not isinstance(cwd, str) or not os.path.isabs(cwd):
        raise ValueError("session cwd must be an absolute path")
    result = {"agent": agent, "sessionId": sid, "title": str(title or ""),
              "cwd": cwd, "createdAt": millis(created),
              "updatedAt": millis(updated, millis(created)), "preview": preview}
    if isinstance(count, int) and not isinstance(count, bool) and count >= 0:
        result["messageCount"] = count
    return result


def json_lines(stream):
    while True:
        line = stream.readline(MAX_LINE + 1)
        if not line:
            return
        if len(line) > MAX_LINE:
            raise ValueError("JSONL line exceeds 32 MiB")
        if line.strip():
            yield json.loads(line)


def pick(obj, *keys, default=None):
    return next((obj[key] for key in keys if obj.get(key) is not None), default)


def kimi_index(root):
    index = {}
    path = root / "session_index.jsonl"
    if path.is_file():
        with path.open(encoding="utf-8") as stream:
            while True:
                line = stream.readline(MAX_LINE + 1)
                if not line:
                    break
                if len(line) > MAX_LINE:
                    raise ValueError("session index line exceeds limit")
                try:
                    entry = json.loads(line)
                    sid = entry["sessionId"]
                    cwd = entry["workDir"]
                    if not isinstance(sid, str) or not isinstance(cwd, str):
                        raise ValueError("invalid session index entry")
                    index[sid] = cwd
                    index[sid.removeprefix("session_")] = cwd
                except (ValueError, TypeError, KeyError):
                    error("kimi", "invalid session index entry; skipped")
    return index


def kill_process_group(process):
    import signal
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def cli_json(command):
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, start_new_session=True)
    timer = threading.Timer(TIMEOUT, kill_process_group, args=(process,))
    timer.daemon = True
    timer.start()
    try:
        output = process.stdout.read(MAX_LINE + 1)
        if len(output) > MAX_LINE:
            raise ValueError("CLI response exceeds 32 MiB")
        if process.wait(timeout=TIMEOUT) != 0:
            raise ValueError("CLI failed or timed out")
        return json.loads(output)
    finally:
        timer.cancel()
        kill_process_group(process)
        process.stdout.close()
        process.wait()


def kimi_records(limit, query=""):
    root = Path(os.environ.get("KIMI_CODE_HOME", str(Path.home() / ".kimi-code")))
    index = {}
    try:
        index = kimi_index(root)
    except (OSError, ValueError, TypeError, AttributeError) as exc:
        error("kimi", "session index unreadable: " + type(exc).__name__)
    executable = shutil.which("kimi")
    if executable:
        try:
            rows = cli_json([executable, "session", "list", "--all", "--json",
                             "--limit", str(limit)])
            if isinstance(rows, dict):
                rows = rows["sessions"]
            if not isinstance(rows, list):
                raise ValueError("CLI did not return a session list")
            results = []
            for row in rows:
                sid = pick(row, "sessionId", "id", "session_id")
                results.append(record("kimi", sid, row.get("title"),
                                      pick(row, "cwd", "workDir", "work_dir", default=index.get(sid)),
                                      pick(row, "createdAt", "created_at"),
                                      pick(row, "updatedAt", "updated_at"),
                                      text(row.get("preview", "")), row.get("messageCount")))
            yield from results
            if not query:
                return
        except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError,
                AttributeError) as exc:
            error("kimi", "CLI failed; reading local metadata: " + type(exc).__name__)
    for path in sorted(root.glob("sessions/*/*/state.json")):
        try:
            with path.open(encoding="utf-8") as stream:
                state = json.load(stream)
            sid = pick(state, "sessionId", "id", "session_id", default=path.parent.name)
            yield record("kimi", sid, state.get("title"),
                         pick(state, "cwd", "workDir", "work_dir",
                              default=index.get(sid, index.get(path.parent.name))),
                         pick(state, "createdAt", "created_at", default=path.stat().st_mtime),
                         pick(state, "updatedAt", "updated_at", default=path.stat().st_mtime),
                         text(state.get("preview", "")), state.get("messageCount"))
        except (OSError, ValueError, TypeError, AttributeError) as exc:
            error("kimi", "session metadata unreadable: " + type(exc).__name__)


def columns(db, table):
    return {row[1] for row in db.execute('PRAGMA table_info("' + table + '")')}


def ordering(cols, agent, reverse=False):
    direction = " DESC" if reverse else " ASC"
    terms = []
    if agent == "zcode":
        if "sequence" not in cols:
            raise ValueError("zcode sequence column missing")
        terms.extend(["(sequence IS NULL)" + direction, "sequence" + direction])
    if "time_created" in cols:
        terms.append("time_created" + direction)
    terms.append(("rowid" if agent == "zcode" else "id") + direction)
    return ", ".join(terms)


def sqlite_preview(db, agent, sid, message_cols, part_cols):
    messages = db.execute("SELECT id, data FROM message WHERE session_id = ? ORDER BY "
                          + ordering(message_cols, agent, True), (sid,))
    for message in messages:
        data = json.loads(message["data"])
        if data.get("role") != "user":
            continue
        pieces = []
        parts = db.execute("SELECT data FROM part WHERE session_id = ? AND message_id = ? ORDER BY "
                           + ordering(part_cols, agent), (sid, message["id"]))
        for part in parts:
            item = json.loads(part["data"])
            if item.get("type") == "text":
                value = text(item)
                if value:
                    pieces.append(value)
        preview = "\n".join(pieces) or text(data)
        if preview:
            return preview
    return ""


def matches(row, query):
    return not query or any(query in str(row[key]).casefold()
                            for key in ("sessionId", "title", "cwd", "preview"))


def sqlite_records(agent, limit, query):
    if agent == "opencode":
        data_home = Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local/share")))
        path = data_home / "opencode/opencode.db"
    else:
        path = Path.home() / ".zcode/cli/db/db.sqlite"
    if not path.is_file():
        return
    with contextlib.closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True,
                                           timeout=5)) as db:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA query_only = ON")
        db.execute("BEGIN")
        message_cols = columns(db, "message")
        part_cols = columns(db, "part")
        rows = db.execute("SELECT id, title, directory, time_created, time_updated FROM session "
                          "ORDER BY time_updated DESC, id DESC")
        matched = 0
        for row in rows:
            try:
                result = record(agent, row["id"], row["title"], row["directory"],
                                row["time_created"], row["time_updated"])
                result["preview"] = sqlite_preview(db, agent, row["id"], message_cols, part_cols)
                if matches(result, query):
                    yield result
                    matched += 1
                    if matched >= limit:
                        return
            except (ValueError, TypeError, AttributeError, sqlite3.Error) as exc:
                error(agent, "session data unreadable: " + type(exc).__name__)


@contextlib.contextmanager
def decompressed(path):
    executable = shutil.which("zstd")
    if not executable:
        try:
            from compression import zstd
        except ImportError as exc:
            raise RuntimeError("dsh needs zstd or Python 3.14+ compression.zstd") from exc
        try:
            with zstd.open(path, "rb") as binary:
                with io.TextIOWrapper(binary, encoding="utf-8") as stream:
                    yield stream
        except zstd.ZstdError as exc:
            raise ValueError("invalid zstd stream") from exc
        return
    process = subprocess.Popen([executable, "-dc", "--", str(path)], stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    timer = threading.Timer(TIMEOUT, process.kill)
    timer.daemon = True
    timer.start()
    try:
        with io.TextIOWrapper(process.stdout, encoding="utf-8") as stream:
            yield stream
        if process.wait(timeout=TIMEOUT) != 0:
            raise ValueError("zstd failed or timed out")
    finally:
        timer.cancel()
        if process.poll() is None:
            process.kill()
        process.wait()


def dsh_records():
    root = Path(os.environ.get("DSH_HOME", str(Path.home() / ".dsh"))) / "sessions"
    for directory in sorted(root.glob("*/*")):
        if not directory.is_dir():
            continue
        candidates = []
        for path in directory.iterdir():
            match = re.fullmatch(r"session\.v(\d+)\.jsonl\.zstd", path.name)
            if match and path.is_file():
                candidates.append((int(match[1]), path))
        if not candidates:
            continue
        path = max(candidates)[1]
        try:
            with decompressed(path) as stream:
                events = json_lines(stream)
                header = next(events)
                if header.get("type") != "session":
                    raise ValueError("missing dsh session header")
                created = header.get("createdAt")
                updated = millis(pick(header, "updatedAt", default=created))
                preview = ""
                count = 0
                for event in events:
                    updated = max(updated, millis(event.get("time"), updated))
                    if event.get("type") in ("user/message", "assistant/message"):
                        count += 1
                    if event.get("type") == "user/message":
                        value = text(event.get("data"))
                        if value:
                            preview = value
                result = record("dsh", header.get("id"), header.get("title"), header.get("cwd"),
                                created, updated, preview, count)
            yield result
        except (OSError, ValueError, TypeError, AttributeError, OverflowError,
                RuntimeError, StopIteration, subprocess.SubprocessError) as exc:
            error("dsh", str(exc) if isinstance(exc, RuntimeError)
                  else "session file unreadable: " + type(exc).__name__)


class Parser(argparse.ArgumentParser):
    def error(self, message):
        error("scanner", message)
        raise SystemExit(2)


def limit_value(value):
    limit = int(value)
    if not 1 <= limit <= 1000:
        raise argparse.ArgumentTypeError("limit must be between 1 and 1000")
    return limit


def main(argv=None):
    parser = Parser(description=__doc__)
    parser.add_argument("--agent", action="append", nargs="+", choices=AGENTS)
    parser.add_argument("--limit", type=limit_value, default=200)
    parser.add_argument("--query", default="")
    args = parser.parse_args(argv)
    agents = list(dict.fromkeys(item for group in args.agent for item in group)) if args.agent else AGENTS
    query = args.query.casefold()
    for agent in agents:
        try:
            source = (kimi_records(1000 if query else args.limit, query) if agent == "kimi" else
                      dsh_records() if agent == "dsh" else sqlite_records(agent, args.limit, query))
            rows = {}
            for row in source:
                if not matches(row, query):
                    continue
                key = row["sessionId"]
                if key not in rows or row["updatedAt"] > rows[key]["updatedAt"]:
                    rows[key] = row
                if len(rows) > args.limit:
                    oldest = min(rows, key=lambda sid: (rows[sid]["updatedAt"], sid))
                    del rows[oldest]
            for row in sorted(rows.values(), key=lambda item: (item["updatedAt"], item["sessionId"]),
                              reverse=True):
                emit(row)
        except Exception as exc:
            error(agent, "scan failed: " + type(exc).__name__)
    return 0


if __name__ == "__main__":
    sys.exit(main())
RMAGENTMA_PYTHON
