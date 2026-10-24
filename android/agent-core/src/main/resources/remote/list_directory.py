#!/usr/bin/env python3
# Copyright 2026 rmAgentMa contributors
# SPDX-License-Identifier: Apache-2.0
"""List one directory read-only for the phone's file panel; stdout is NDJSON.

One `entry` row per entry, then exactly one `listing` row (or one `error` row instead of both), so the caller can
tell a directory that really is empty from one that was never read. Nothing here writes: no temp file, no index, no
recursive walk. A symlink is reported as a symlink, never followed, so a listing can never leave the directory it
was asked about.
"""

import argparse
import json
import os
import sys

MAX_LIMIT = 200
MAX_BYTES = 128 * 1024


def emit(value):
    print(json.dumps(value, ensure_ascii=False, separators=(",", ":")), flush=True)


def fail(category):
    emit({"record": "error", "level": "error", "error": category})


def printable(value):
    """Undecodable bytes arrive as surrogates, which cannot be serialized as UTF-8; show them as '?'."""
    return value.encode("utf-8", "replace").decode("utf-8")


def describe(item):
    symlink = False
    try:
        symlink = item.is_symlink()
    except OSError:
        pass
    target = None
    if symlink:
        try:
            target = printable(os.readlink(item.path))
        except OSError:
            target = None
    kind = "symlink"
    if not symlink:
        try:
            kind = "dir" if item.is_dir(follow_symlinks=False) else "file"
        except OSError:
            kind = "file"
    try:
        size = item.stat(follow_symlinks=False).st_size
    except OSError:
        size = -1
    row = {"record": "entry", "name": printable(item.name), "kind": kind, "size": size}
    if target is not None:
        row["symlink_target"] = target
    return row


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", required=True)
    parser.add_argument("--limit", type=int, default=MAX_LIMIT)
    parser.add_argument("--max-bytes", type=int, default=MAX_BYTES)
    args = parser.parse_args()

    directory = args.directory
    if not directory.startswith("/"):
        fail("dir_not_absolute")
        return
    limit = max(1, min(args.limit, MAX_LIMIT))
    budget = max(1024, min(args.max_bytes, MAX_BYTES))

    try:
        os.stat(directory)
    except FileNotFoundError:
        fail("dir_missing")
        return
    except PermissionError:
        fail("dir_not_readable")
        return
    except OSError:
        fail("dir_list_failed")
        return
    if not os.path.isdir(directory):
        fail("dir_not_a_directory")
        return

    count = 0
    used = 0
    truncated = False
    try:
        with os.scandir(directory) as scan:
            for item in scan:
                if count >= limit:
                    truncated = True
                    break
                row = describe(item)
                rendered = len(json.dumps(row, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) + 1
                if used + rendered > budget:
                    truncated = True
                    break
                used += rendered
                count += 1
                emit(row)
    except PermissionError:
        fail("dir_not_readable")
        return
    except OSError:
        fail("dir_list_failed")
        return

    emit({"record": "listing", "directory": printable(directory), "count": count, "truncated": truncated})


if __name__ == "__main__":
    sys.exit(main())
