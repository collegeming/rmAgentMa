#!/usr/bin/env python3
# Copyright 2026 rmAgentMa contributors
# SPDX-License-Identifier: Apache-2.0
r"""Search named transcript files read-only for one query; stdout is NDJSON.

One `match` row per matching line - the file and its 1-based line number are the message anchor - then exactly one
`search` row whose counters say how much was read, what a limit cut off and what could not be read at all; a request
this script refuses answers with one `error` row instead. Nothing here writes, and no candidate leaves its
directory: a symlink, a missing file or a name that would escape counts as unreadable rather than being followed.

Only the candidate files the caller names are read, so a history that is not a file (kimi, opencode and zcode keep
theirs in sqlite) is out of reach by design rather than guessed at; a `.zstd` file is read through the stdlib
`compression.zstd` module or the `zstd` command when the host has one. Matching compares case-folded text by Unicode
character, so a Chinese query and a mixed-case ASCII query are searched the same way and no snippet splits a
character. A line is matched as the file stores it, which for dsh means raw UTF-8 rather than `\uXXXX` escapes. The
query and each scope arrive base64-encoded because a query is arbitrary user text while argv decoding follows the
host's locale.
"""

import argparse
import base64
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

MAX_FILES = 200
MAX_BYTES = 8 * 1024 * 1024
MAX_MATCHES = 200
MAX_SNIPPET = 400
MAX_QUERY = 256
MAX_TIMEOUT = 120
MAX_LINE = 1_000_000
MAX_NAME = 512
MAX_SESSION = 1024
MAX_DIRECTORY = 4096
MAX_AGENT = 64


class SearchError(Exception):
    pass


def emit(value):
    print(json.dumps(value, ensure_ascii=False, separators=(",", ":")), flush=True)


def fail(category):
    emit({"record": "error", "level": "error", "error": category})


def control(value):
    return any(ord(char) < 32 or ord(char) == 127 for char in value)


def printable(text):
    """A snippet is shown as one line, so a control character becomes a space instead of ending that line."""
    return "".join(" " if ord(char) < 32 or ord(char) == 127 else char for char in text)


def folded(text):
    """Case-fold text with a folded-index -> character-index map, so a match keeps whole characters."""
    pieces = []
    offsets = []
    for index, char in enumerate(text):
        piece = char.casefold()
        pieces.append(piece)
        offsets += [index] * len(piece)
    return "".join(pieces), offsets


def first_match(text, needle):
    """The first match as (start, end) in characters of the original text, or None when there is none."""
    haystack, offsets = folded(text)
    index = haystack.find(needle)
    if index < 0:
        return None
    return offsets[index], offsets[index + len(needle) - 1] + 1


def snippet(text, start, end, limit):
    """A window of at most `limit` characters around the match, plus whether the line continued beyond it."""
    begin = max(0, start - max(0, (limit - (end - start)) // 2))
    finish = min(len(text), begin + limit)
    return printable(text[begin:finish]), begin > 0 or finish < len(text)


def candidate_name(name):
    """A candidate is one file inside its directory: no separator, no parent, nothing a path could escape with."""
    return bool(name) and len(name) <= MAX_NAME and name not in (".", "..") and not control(name) \
        and "/" not in name and "\\" not in name


def resolved(directory):
    try:
        root = Path(directory).resolve(strict=True)
        return root if root.is_dir() else None
    except OSError:
        return None


@contextlib.contextmanager
def transcript(path):
    """Plain UTF-8, or zstd as dsh writes its sessions, since the reader this search belongs to reads those too."""
    if not path.name.endswith(".zstd"):
        with open(path, "r", encoding="utf-8", errors="replace", newline="") as stream:
            yield stream
        return
    try:
        from compression import zstd
    except ImportError:
        zstd = None
    if zstd is not None:
        try:
            with zstd.open(path, "rb") as binary:
                with io.TextIOWrapper(binary, encoding="utf-8", errors="replace") as stream:
                    yield stream
        except (zstd.ZstdError, EOFError):
            # A stream this host cannot decompress is a file nobody read, not a file without hits.
            raise OSError("invalid zstd stream") from None
        return
    executable = shutil.which("zstd")
    if not executable:
        raise OSError("zstd missing")
    # shortcut: a broken zstd stream read through the command reports no hits instead of an error; upgrade when a
    # history has to tell a corrupt transcript from one that simply does not match.
    process = subprocess.Popen([executable, "-dc", "--", str(path)], stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    try:
        with io.TextIOWrapper(process.stdout, encoding="utf-8", errors="replace") as stream:
            yield stream
    finally:
        process.kill()
        process.wait()


def decoded(value):
    return base64.b64decode(value, validate=True).decode("utf-8")


def decode_query(value):
    try:
        query = decoded(value)
    except ValueError as exc:
        raise SearchError("search_query_invalid") from exc
    if not query:
        raise SearchError("search_query_empty")
    if len(query) > MAX_QUERY or control(query):
        raise SearchError("search_query_invalid")
    return query


def decode_scope(value):
    """One session's candidates as the caller knows them, since this script never walks history on its own."""
    try:
        scope = json.loads(decoded(value))
    except ValueError as exc:
        raise SearchError("search_scope_invalid") from exc
    if not isinstance(scope, dict):
        raise SearchError("search_scope_invalid")
    agent = scope.get("agent")
    session = scope.get("sessionId")
    directory = scope.get("directory")
    files = scope.get("files")
    if not isinstance(agent, str) or not 0 < len(agent) <= MAX_AGENT or control(agent):
        raise SearchError("search_scope_invalid")
    if not isinstance(session, str) or not 0 < len(session) <= MAX_SESSION or control(session):
        raise SearchError("search_scope_invalid")
    if not isinstance(directory, str) or not os.path.isabs(directory) or len(directory) > MAX_DIRECTORY \
            or control(directory):
        raise SearchError("search_scope_invalid")
    if not isinstance(files, list) or len(files) > MAX_FILES \
            or not all(isinstance(name, str) and candidate_name(name) for name in files):
        raise SearchError("search_scope_invalid")
    return {"key": agent + ":" + session, "agent": agent, "sessionId": session,
            "directory": directory, "files": files}


def limits(args):
    """Every bound a caller can raise is clamped, so one request can never turn into an unbounded scan."""
    args.max_files = min(max(1, args.max_files), MAX_FILES)
    args.max_bytes = min(max(1024, args.max_bytes), MAX_BYTES)
    args.max_matches = min(max(1, args.max_matches), MAX_MATCHES)
    args.max_snippet = min(max(16, args.max_snippet), MAX_SNIPPET)
    args.timeout = min(max(1, args.timeout), MAX_TIMEOUT)


def search(query, scopes, args):
    needle = query.casefold()
    deadline = time.monotonic() + args.timeout
    roots = [resolved(scope["directory"]) for scope in scopes]
    candidates = [(scope, roots[index], name)
                  for index, scope in enumerate(scopes) for name in scope["files"]]
    matches = 0
    read = 0
    unreadable = 0
    scanned = 0
    truncated = False
    timed_out = False
    for position, (scope, root, name) in enumerate(candidates):
        if matches >= args.max_matches or position >= args.max_files:
            truncated = True
            break
        if time.monotonic() > deadline:
            timed_out = True
            break
        if root is None:
            unreadable += 1
            continue
        path = root / name
        try:
            if path.is_symlink() or not path.is_file():
                unreadable += 1
                continue
            # The file is opened here, so a candidate this host cannot read is one unreadable file rather than a
            # search that fails as a whole; a file that fails halfway gives up its remainder the same way.
            with transcript(path) as stream:
                line_number = 0
                while True:
                    if matches >= args.max_matches:
                        truncated = True
                        break
                    if time.monotonic() > deadline:
                        timed_out = True
                        break
                    # The budget is counted in bytes while a read is capped in characters, so one line can overshoot
                    # it by its own width; the next read sees no room left and stops.
                    room = args.max_bytes - scanned
                    if room <= 0:
                        truncated = True
                        break
                    limit = min(MAX_LINE, room)
                    line = stream.readline(limit)
                    if not line:
                        break
                    if len(line) == limit and not line.endswith(("\n", "\r")):
                        # This line runs past the byte budget or the character cap, so the rest of the file stays
                        # unread instead of being counted as several shorter lines.
                        scanned += len(line.encode("utf-8", "replace"))
                        truncated = True
                        break
                    line_number += 1
                    scanned += len(line.encode("utf-8", "replace"))
                    text = line.rstrip("\r\n")
                    found = first_match(text, needle)
                    if found is None:
                        continue
                    start, end = found
                    shown, clipped = snippet(text, start, end, args.max_snippet)
                    emit({"record": "match", "sessionKey": scope["key"], "agent": scope["agent"],
                          "sessionId": scope["sessionId"], "directory": scope["directory"], "file": name,
                          "line": line_number, "offset": start, "snippet": shown, "clipped": clipped})
                    matches += 1
            read += 1
        except OSError:
            unreadable += 1
    emit({"record": "search", "scopes": len(scopes), "files": len(candidates), "read": read,
          "bytes": scanned, "matches": matches, "truncated": truncated, "unreadable": unreadable,
          "timedOut": timed_out})


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--query-b64", default="")
    parser.add_argument("--scope-b64", action="append", default=[])
    parser.add_argument("--max-files", type=int, default=50)
    parser.add_argument("--max-bytes", type=int, default=2 * 1024 * 1024)
    parser.add_argument("--max-matches", type=int, default=50)
    parser.add_argument("--max-snippet", type=int, default=160)
    parser.add_argument("--timeout", type=int, default=30)
    args = parser.parse_args(argv)
    try:
        query = decode_query(args.query_b64)
        scopes = [decode_scope(value) for value in args.scope_b64]
        if not scopes:
            raise SearchError("search_scope_invalid")
        limits(args)
        search(query, scopes, args)
        return 0
    except SearchError as exc:
        fail(str(exc))
    except (OSError, ValueError, TypeError, RecursionError):
        fail("search_failed")
    return 1


if __name__ == "__main__":
    sys.exit(main())
