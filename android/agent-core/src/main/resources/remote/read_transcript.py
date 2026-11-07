#!/usr/bin/env python3
# Copyright 2026 rmAgentMa contributors
# SPDX-License-Identifier: Apache-2.0

import argparse
import ast
import base64
import contextlib
import hashlib
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

MAX_LINE = 4 * 1024 * 1024
CHUNK = 8192
TIMEOUT = 30
MAX_PAGE_BYTES = 1024 * 1024


class HistoryError(Exception):
    pass


class Tally:
    """A record a source could not decode is skipped, but never silently: the page names how many."""

    def __init__(self):
        self.errors = 0


def emit(value):
    line = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    print(line, flush=True)
    return len(line.encode("utf-8")) + 1


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False,
                                    separators=(",", ":")).encode()).hexdigest()


def identity(args):
    return digest([args.host_id, args.agent, args.session_id, args.cwd])


def decode_cursor(value, owner, stamp):
    if not value:
        return 0
    if len(value) > 2048:
        raise HistoryError("invalid_cursor")
    try:
        data = json.loads(base64.b64decode(value, altchars=b"-_", validate=True))
        if data["owner"] != owner:
            raise HistoryError("cursor_identity_mismatch")
        if data["stamp"] != stamp:
            raise HistoryError("stale_cursor")
        position = data["position"]
        if isinstance(position, bool) or not isinstance(position, int) or not 0 <= position <= 10_000_000:
            raise ValueError()
        return position
    except HistoryError:
        raise
    except (ValueError, TypeError, KeyError):
        raise HistoryError("invalid_cursor") from None


def encode_cursor(owner, stamp, position):
    return base64.urlsafe_b64encode(json.dumps({"owner": owner, "stamp": stamp,
                                              "position": position},
                                             separators=(",", ":")).encode()).decode()


def safe_child(path, root):
    if path.is_symlink():
        raise HistoryError("symlink_rejected")
    resolved = path.resolve(strict=True)
    if not resolved.is_relative_to(root):
        raise HistoryError("path_outside_root")
    return resolved


@contextlib.contextmanager
def decompressed(path):
    try:
        from compression import zstd
    except ImportError:
        zstd = None
    if zstd is not None:
        try:
            with zstd.open(path, "rb") as binary:
                with io.TextIOWrapper(binary, encoding="utf-8") as stream:
                    yield stream
        except (zstd.ZstdError, EOFError):
            raise HistoryError("invalid_zstd") from None
        return
    executable = shutil.which("zstd")
    if not executable:
        raise HistoryError("zstd_missing")
    process = subprocess.Popen([executable, "-dc", "--", str(path)], stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    timer = threading.Timer(TIMEOUT, process.kill)
    timer.daemon = True
    timer.start()
    try:
        class Stream(io.TextIOWrapper):
            eof = False

            def readline(self, size=-1):
                line = super().readline(size)
                if not line:
                    self.eof = True
                return line

        with Stream(process.stdout, encoding="utf-8") as stream:
            yield stream
            if stream.eof and process.wait(timeout=TIMEOUT) != 0:
                raise HistoryError("invalid_zstd")
    finally:
        timer.cancel()
        if process.poll() is None:
            process.kill()
        process.wait()


def json_lines(stream):
    while True:
        line = stream.readline(MAX_LINE + 1)
        if not line:
            return
        if len(line.encode("utf-8")) > MAX_LINE:
            raise HistoryError("input_line_limit")
        if line.strip():
            data = json.loads(line)
            if not isinstance(data, dict):
                raise HistoryError("invalid_event")
            yield data


def header(stream, args):
    value = next(json_lines(stream), None)
    if not value or value.get("type") != "session":
        raise HistoryError("invalid_header")
    if value.get("id") != args.session_id or value.get("cwd") != args.cwd:
        raise HistoryError("session_identity_mismatch")
    return value


def dsh_path(args):
    sid = args.session_id
    if sid in (".", "..") or "/" in sid or "\\" in sid:
        raise HistoryError("invalid_session_id")
    root = (Path(os.environ.get("DSH_HOME", str(Path.home() / ".dsh"))) / "sessions").resolve(strict=True)
    matches = []
    mismatch = False
    for project in root.iterdir():
        if project.is_symlink() or not project.is_dir():
            continue
        directory = project / sid
        if not directory.exists():
            continue
        directory = safe_child(directory, root)
        if not directory.is_dir():
            continue
        candidates = []
        for path in directory.iterdir():
            match = re.fullmatch(r"session\.v(\d+)\.jsonl\.zstd", path.name)
            if match:
                path = safe_child(path, root)
                if path.is_file():
                    candidates.append((int(match[1]), path))
        if not candidates:
            continue
        path = max(candidates)[1]
        with decompressed(path) as stream:
            try:
                header(stream, args)
            except HistoryError as exc:
                if str(exc) != "session_identity_mismatch":
                    raise
                mismatch = True
                continue
        matches.append(path)
    if len(matches) > 1:
        raise HistoryError("ambiguous_session")
    if not matches:
        raise HistoryError("session_identity_mismatch" if mismatch else "session_not_found")
    return matches[0]


def text(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, list):
        for item in value:
            yield from text(item)
    elif isinstance(value, dict):
        if value.get("type") not in (None, "text"):
            return
        for key in ("text", "content", "message"):
            if key in value:
                yield from text(value[key])
                break


def pieces(kind, values, event_id="", title="", status=""):
    for value in values:
        if not value:
            continue
        for offset in range(0, len(value), CHUNK):
            yield {"type": kind, "text": value[offset:offset + CHUNK], "id": str(event_id)[:1024],
                   "title": str(title)[:1024], "status": str(status)[:128],
                   "historical": True, "continuation": offset > 0}


def dsh_events(stream):
    for event in json_lines(stream):
        kind = event.get("type", "")
        data = event.get("data")
        if kind == "assistant/message" and isinstance(data, dict) and isinstance(data.get("message"), dict):
            message = data["message"]
            content = message.get("content")
            blocks = content if isinstance(content, list) else [content]
            for block in blocks:
                if isinstance(block, dict) and block.get("type") == "reasoning":
                    yield from pieces("thinking", [block.get("text", "")], event.get("seq", ""))
                else:
                    yield from pieces("content", text(block), event.get("seq", ""))
        elif kind in ("user/message", "assistant/message"):
            yield from pieces("user" if kind == "user/message" else "content", text(data), event.get("seq", ""))
        elif isinstance(kind, str) and kind.startswith("tool/"):
            yield from pieces("tool" if kind == "tool/call" else "tool_update",
                              [json.dumps(data, ensure_ascii=False, separators=(",", ":"))],
                              event.get("seq", ""), kind, "historical")
        elif isinstance(kind, str) and ("permission" in kind or "approval" in kind):
            yield from pieces("content", ["Historical authorization event: " + kind],
                              event.get("seq", ""))


@contextlib.contextmanager
def dsh_source(args, tally):
    path = dsh_path(args)
    before = path.stat()
    stamp = digest([str(path), before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns])
    with decompressed(path) as stream:
        header(stream, args)
        yield stamp, dsh_events(stream)
        after = path.stat()
        if (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
            raise HistoryError("source_changed")


def repr_object(value):
    """kimi stores these fields as JSON in some builds and as a Python repr in others; either form is accepted."""
    if isinstance(value, dict):
        return value
    if not isinstance(value, str):
        return None
    try:
        parsed = json.loads(value)
    except ValueError:
        parsed = None
    if not isinstance(parsed, dict):
        try:
            parsed = ast.literal_eval(value)
        except (ValueError, SyntaxError, RecursionError, MemoryError):
            return None
    return parsed if isinstance(parsed, dict) else None


def kimi_blocks(message):
    content = message.get("content")
    if isinstance(content, str):
        return [{"type": "text", "text": content}]
    if isinstance(content, dict):
        return [content]
    if isinstance(content, list):
        return [block for block in content if isinstance(block, dict)]
    return []


def origin_kind(value):
    origin = value.get("origin") if isinstance(value, dict) else None
    return origin.get("kind") if isinstance(origin, dict) else None


def kimi_events(stream, tally):
    """Each fact comes from the one record kind that carries it: a prompt from the context, a step's streamed text
    from its parts, and a tool's output from its appended message. Those kinds repeat each other, so reading every
    kind for every fact would show the same history twice."""
    users = set()
    for record in json_lines(stream):
        kind = record.get("type")
        if kind == "context.append_message":
            message = repr_object(record.get("message"))
            if message is None:
                tally.errors += 1
                continue
            if message.get("role") != "user" or origin_kind(message) != "user":
                continue
            identity = message.get("id")
            if isinstance(identity, str):
                users.add(identity)
            for block in kimi_blocks(message):
                yield from pieces("user", text(block), identity if isinstance(identity, str) else "")
        elif kind == "context.append_loop_event":
            event = repr_object(record.get("event"))
            if event is None:
                tally.errors += 1
                continue
            if event.get("type") != "content.part":
                continue
            part = repr_object(event.get("part"))
            if part is None:
                tally.errors += 1
                continue
            if part.get("type") == "text":
                yield from pieces("content", [part.get("text", "")], event.get("uuid", ""))
            elif part.get("type") == "think":
                yield from pieces("thinking", [part.get("think") or part.get("text") or ""], event.get("uuid", ""))
        elif kind == "agent.message.appended":
            wrapper = repr_object(record.get("message"))
            if wrapper is None:
                tally.errors += 1
                continue
            message = wrapper if "role" in wrapper else repr_object(wrapper.get("message"))
            meta = wrapper.get("meta") if isinstance(wrapper.get("meta"), dict) else {}
            if message is None:
                tally.errors += 1
                continue
            if message.get("role") == "tool":
                for block in kimi_blocks(message):
                    yield from pieces("tool", text(block), message.get("toolCallId", ""), "", "historical")
            elif message.get("role") == "user" and origin_kind(meta) == "user":
                # A prompt the context never recorded is still the user's own turn, so it is shown once here.
                identity = meta.get("promptId") or meta.get("userMessageId")
                if identity not in users:
                    if isinstance(identity, str):
                        users.add(identity)
                    for block in kimi_blocks(message):
                        yield from pieces("user", text(block), identity if isinstance(identity, str) else "")


def kimi_path(args):
    sid = args.session_id
    if sid in (".", "..") or "/" in sid or "\\" in sid:
        raise HistoryError("invalid_session_id")
    base = Path(os.environ.get("KIMI_CODE_HOME", str(Path.home() / ".kimi-code")))
    if not base.is_dir():
        raise HistoryError("session_not_found")
    root = base.resolve(strict=True)
    candidates = []
    index = root / "session_index.jsonl"
    if index.is_file() and not index.is_symlink():
        with index.open(encoding="utf-8") as stream:
            while True:
                line = stream.readline(MAX_LINE + 1)
                if not line:
                    break
                if len(line.encode("utf-8")) > MAX_LINE:
                    raise HistoryError("input_line_limit")
                if not line.strip():
                    continue
                try:
                    entry = json.loads(line)
                except ValueError:
                    continue
                if isinstance(entry, dict) and entry.get("sessionId") == sid \
                        and isinstance(entry.get("sessionDir"), str) and os.path.isabs(entry["sessionDir"]):
                    candidates.append(Path(entry["sessionDir"]))
    # A session the index does not name is still one the scanner listed from its own state file.
    sessions = root / "sessions"
    if sessions.is_dir() and not sessions.is_symlink():
        for project in sessions.iterdir():
            if project.is_symlink() or not project.is_dir():
                continue
            candidates.append(project / sid)
    directories = set()
    mismatch = False
    for directory in candidates:
        try:
            directory = safe_child(directory, root)
        except OSError:
            continue
        state_path = directory / "state.json"
        if state_path.is_symlink() or not state_path.is_file():
            continue
        with state_path.open(encoding="utf-8") as stream:
            state = stream.read(MAX_LINE + 1)
        if len(state.encode("utf-8")) > MAX_LINE:
            raise HistoryError("input_line_limit")
        try:
            state = json.loads(state)
        except ValueError:
            continue
        wire = directory / "agents/main/wire.jsonl"
        if not isinstance(state, dict) or state.get("id") != sid or state.get("cwd") != args.cwd:
            mismatch = True
            continue
        if wire.is_symlink() or not wire.is_file():
            continue
        directories.add(safe_child(wire, root))
    if len(directories) > 1:
        raise HistoryError("ambiguous_session")
    if not directories:
        raise HistoryError("session_identity_mismatch" if mismatch else "session_not_found")
    return directories.pop()


@contextlib.contextmanager
def kimi_source(args, tally):
    path = kimi_path(args)
    before = path.stat()
    stamp = digest([str(path), before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns])
    with path.open("rb") as binary, io.TextIOWrapper(binary, encoding="utf-8") as stream:
        yield stamp, kimi_events(stream, tally)
        after = path.stat()
        if (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
            raise HistoryError("source_changed")


def columns(db, table):
    return {row[1] for row in db.execute('PRAGMA table_info("' + table + '")')}


def ordering(cols):
    if "sequence" not in cols:
        raise HistoryError("unsupported_schema")
    return "(sequence IS NULL), sequence, " + ("time_created, " if "time_created" in cols else "") + "rowid"


def bounded_json(value):
    if not isinstance(value, str) or len(value.encode("utf-8")) > MAX_LINE:
        raise HistoryError("input_line_limit")
    value = json.loads(value)
    if not isinstance(value, dict):
        raise HistoryError("invalid_event")
    return value


def sqlite_events(db, args):
    message_order = ordering(columns(db, "message"))
    part_order = ordering(columns(db, "part"))
    selected_data = "CASE WHEN length(CAST(data AS BLOB)) <= ? THEN data ELSE NULL END AS data"
    messages = db.execute("SELECT id, " + selected_data + " FROM message WHERE session_id = ? ORDER BY " + message_order,
                          (MAX_LINE, args.session_id))
    for message in messages:
        data = bounded_json(message["data"])
        role = data.get("role")
        parts = db.execute("SELECT id, " + selected_data + " FROM part WHERE session_id = ? AND message_id = ? ORDER BY " + part_order,
                           (MAX_LINE, args.session_id, message["id"]))
        found = False
        for part in parts:
            found = True
            item = bounded_json(part["data"])
            if item.get("type") == "text" and role in ("user", "assistant"):
                yield from pieces("user" if role == "user" else "content", text(item), part["id"])
            elif item.get("type") == "reasoning" and role == "assistant":
                yield from pieces("thinking", [item.get("text", "")], part["id"])
            elif item.get("type") == "tool":
                yield from pieces("tool", [json.dumps(item, ensure_ascii=False, separators=(",", ":"))],
                                  part["id"], "Historical tool", "historical")
        if not found and role in ("user", "assistant"):
            yield from pieces("user" if role == "user" else "content", text(data), message["id"])


@contextlib.contextmanager
def sqlite_source(args, tally):
    root = (Path.home() / ".zcode/cli/db").resolve(strict=True)
    path = safe_child(root / "db.sqlite", root)
    with contextlib.closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=5)) as db:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA query_only = ON")
        if hasattr(db, "setlimit"):
            db.setlimit(sqlite3.SQLITE_LIMIT_LENGTH, MAX_LINE)
        db.execute("BEGIN")
        row = db.execute("SELECT id, directory, time_updated FROM session WHERE id = ? AND directory = ?",
                         (args.session_id, args.cwd)).fetchone()
        if row is None:
            raise HistoryError("session_identity_mismatch")
        stats = db.execute("SELECT count(*), max(rowid) FROM message WHERE session_id = ?",
                           (args.session_id,)).fetchone()
        parts = db.execute("SELECT count(*), max(rowid) FROM part WHERE session_id = ?",
                           (args.session_id,)).fetchone()
        files = []
        for source in (path, Path(str(path) + "-wal")):
            if source.exists():
                stat = source.stat()
                files.append([source.name, stat.st_ino, stat.st_size, stat.st_mtime_ns])
        stamp = digest([files, row["time_updated"], list(stats), list(parts)])
        yield stamp, sqlite_events(db, args)


def opencode_events(db, args, tally):
    """opencode has no sequence column, so one stable (time_created, id) order is the cursor's whole basis."""
    selected_data = "CASE WHEN length(CAST(data AS BLOB)) <= ? THEN data ELSE NULL END AS data"
    messages = db.execute("SELECT id, " + selected_data + " FROM message WHERE session_id = ? "
                          "ORDER BY time_created, id", (MAX_LINE, args.session_id))
    for message in messages:
        try:
            data = bounded_json(message["data"])
        except (HistoryError, ValueError):
            tally.errors += 1
            continue
        role = data.get("role")
        if role not in ("user", "assistant"):
            continue
        parts = db.execute("SELECT id, " + selected_data + " FROM part WHERE session_id = ? AND message_id = ? "
                           "ORDER BY time_created, id", (MAX_LINE, args.session_id, message["id"]))
        found = False
        for part in parts:
            found = True
            try:
                item = bounded_json(part["data"])
            except (HistoryError, ValueError):
                tally.errors += 1
                continue
            kind = item.get("type")
            if kind == "text":
                yield from pieces("user" if role == "user" else "content", text(item), part["id"])
            elif kind == "reasoning" and role == "assistant":
                yield from pieces("thinking", [item.get("text", "")], part["id"])
            elif kind == "tool":
                yield from pieces("tool", [json.dumps(item, ensure_ascii=False, separators=(",", ":"))],
                                  part["id"], "Historical tool", "historical")
        if not found:
            yield from pieces("user" if role == "user" else "content", text(data), message["id"])


@contextlib.contextmanager
def opencode_source(args, tally):
    data_home = Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local/share")))
    if not data_home.is_dir():
        raise HistoryError("session_not_found")
    root = data_home.resolve(strict=True)
    path = safe_child(root / "opencode/opencode.db", root)
    with contextlib.closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=5)) as db:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA query_only = ON")
        # The per-connection length limit is not set here: SQLite refuses to even measure a longer value, which would
        # turn one oversized row into a failed read instead of the one skipped row this source reports.
        db.execute("BEGIN")
        for table, required in (("session", {"id", "directory", "time_updated"}),
                                ("message", {"id", "session_id", "time_created", "data"}),
                                ("part", {"id", "message_id", "session_id", "time_created", "data"})):
            if not required <= columns(db, table):
                raise HistoryError("unsupported_schema")
        row = db.execute("SELECT id, directory, time_updated FROM session WHERE id = ? AND directory = ?",
                         (args.session_id, args.cwd)).fetchone()
        if row is None:
            raise HistoryError("session_identity_mismatch")
        files = []
        for source in (path, Path(str(path) + "-wal")):
            if source.exists():
                stat = source.stat()
                files.append([source.name, stat.st_ino, stat.st_size, stat.st_mtime_ns])
        stamp = digest([files, row["time_updated"]])
        yield stamp, opencode_events(db, args, tally)


SOURCES = {"dsh": dsh_source, "zcode": sqlite_source, "kimi": kimi_source, "opencode": opencode_source}


def page(args):
    owner = identity(args)
    tally = Tally()
    next_cursor = ""
    count = 0
    byte_count = 0
    with SOURCES[args.agent](args, tally) as (stamp, events):
        start = decode_cursor(args.cursor, owner, stamp)
        for position, event in enumerate(events):
            if position < start:
                continue
            row = {"record": "event", "hostId": args.host_id, "agent": args.agent,
                   "sessionId": args.session_id, "cwd": args.cwd, **event}
            size = len(json.dumps(row, ensure_ascii=False, separators=(",", ":")).encode()) + 1
            if count >= args.limit or byte_count + size > MAX_PAGE_BYTES:
                next_cursor = encode_cursor(owner, stamp, position)
                break
            byte_count += emit(row)
            count += 1
    summary = {"record": "page", "nextCursor": next_cursor, "count": count}
    if tally.errors:
        summary["errors"] = tally.errors
    emit(summary)


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--host-id", type=int, required=True)
    parser.add_argument("--agent", choices=tuple(SOURCES), required=True)
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--cwd", required=True)
    parser.add_argument("--cursor", default="")
    parser.add_argument("--limit", type=int, default=100)
    args = parser.parse_args(argv)
    try:
        if not args.session_id or not os.path.isabs(args.cwd) or not 1 <= args.limit <= 200 or any(
                ord(c) < 32 or ord(c) == 127 for c in args.session_id + args.cwd) or any(
                len(value.encode("utf-8")) > 4096 for value in (args.session_id, args.cwd)):
            raise HistoryError("invalid_arguments")
        page(args)
        return 0
    except HistoryError as exc:
        emit({"record": "error", "error": str(exc)})
    except (OSError, ValueError, TypeError, sqlite3.Error, subprocess.SubprocessError, RecursionError):
        emit({"record": "error", "error": "history_unreadable"})
    return 1


if __name__ == "__main__":
    sys.exit(main())
