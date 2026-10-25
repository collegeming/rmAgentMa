/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Whether a typed working directory is one a remote host can hold: a POSIX absolute path, a Windows
 * drive path or a Windows UNC share. A relative path names no directory on the host, so it is refused
 * here rather than sent and refused later.
 */
export function isAbsoluteRemotePath(value: string): boolean {
  const path = value.trim();
  if (path.startsWith('/')) return true;
  if (/^[A-Za-z]:[\\/]/.test(path)) return true;
  return path.startsWith('\\\\');
}
