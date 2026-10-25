/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { TChatConversation } from '@/common/config/storage';

/**
 * The host-side identity of one conversation row: the host it runs on and its working directory.
 * The host is part of the identity, so the same directory name on two hosts is two groups.
 */
export type HostWorkspaceRef = { hostId: string; cwd: string };

type HostRowFields = {
  offline?: unknown;
  cached?: unknown;
  agent?: unknown;
  workspace?: unknown;
};

const fieldsOf = (conversation: TChatConversation | undefined): HostRowFields =>
  (conversation ?? {}) as unknown as HostRowFields;

const nonEmptyString = (value: unknown): string | null =>
  typeof value === 'string' && value.trim() ? value.trim() : null;

/** The `workspace {host_id, cwd}` a host row carries; null for a row that names none. */
export function hostWorkspaceOf(conversation: TChatConversation | undefined): HostWorkspaceRef | null {
  const workspace = fieldsOf(conversation).workspace;
  if (!workspace || typeof workspace !== 'object') return null;
  const row = workspace as { host_id?: unknown; cwd?: unknown };
  const hostId = nonEmptyString(row.host_id);
  const cwd = nonEmptyString(row.cwd);
  return hostId && cwd ? { hostId, cwd } : null;
}

/**
 * The group key of one host workspace; the host leads it so two hosts never share a group, and a NUL
 * separates the two halves because neither a host id nor a directory path can hold one.
 */
export const hostWorkspaceKey = (ref: HostWorkspaceRef): string => `host:${ref.hostId}\u0000${ref.cwd}`;

/** The Agent the host reports for one row, when it reports one. */
export function hostAgentOf(conversation: TChatConversation | undefined): string | null {
  return nonEmptyString(fieldsOf(conversation).agent);
}

/** The host reported this session's host as unreachable; the row's copy is not live. */
export const isSessionOffline = (conversation: TChatConversation | undefined): boolean =>
  fieldsOf(conversation).offline === true;

/** Only a cached copy of this session is available, so it cannot run a turn. */
export const isSessionCached = (conversation: TChatConversation | undefined): boolean =>
  fieldsOf(conversation).cached === true;

/** The last segment of a host directory: the first half of a workspace group's title. */
export function directoryShortName(cwd: string): string {
  const parts = cwd
    .replace(/[\\/]+$/, '')
    .split(/[\\/]/)
    .filter(Boolean);
  return parts.at(-1) ?? cwd;
}

/** The host id one row belongs to, without requiring a working directory. */
export function hostIdOf(conversation: TChatConversation | undefined): string | null {
  const workspace = fieldsOf(conversation).workspace;
  if (workspace && typeof workspace === 'object') return nonEmptyString((workspace as { host_id?: unknown }).host_id);
  return null;
}

/**
 * Whether one row passes the sidebar's host × Agent filters. An empty filter means "all", and a row that
 * names no host workspace cannot match a named host: it is hidden rather than shown under a guessed one.
 */
export function matchesHostFilters(conversation: TChatConversation, hostId: string, agentId: string): boolean {
  if (hostId && hostIdOf(conversation) !== hostId) return false;
  if (agentId && hostAgentOf(conversation) !== agentId) return false;
  return true;
}

/** Every host id and Agent id the loaded rows name, in first-seen order, for the two selectors. */
export function filterOptionIds(conversations: TChatConversation[]): { hostIds: string[]; agentIds: string[] } {
  const hostIds = new Set<string>();
  const agentIds = new Set<string>();
  for (const conversation of conversations) {
    const hostId = hostIdOf(conversation);
    if (hostId) hostIds.add(hostId);
    const agentId = hostAgentOf(conversation);
    if (agentId) agentIds.add(agentId);
  }
  return { hostIds: [...hostIds], agentIds: [...agentIds] };
}

/**
 * What the host said about the session behind one row — `attached`, `gone` or `unknown` — and its own note.
 * `conversation.read` and `runtime.snapshot` state it directly, a row may carry it inside its `runtime`, and
 * nothing else is read as attached: a conversation whose state the host never stated is not drawn as recovered.
 */
export type RecoveryReading = { state: string; note: string };

const RECOVERY_STATES = ['attached', 'gone', 'unknown'] as const;

export function recoveryStateName(state: string): (typeof RECOVERY_STATES)[number] | null {
  return (RECOVERY_STATES as readonly string[]).includes(state) ? (state as (typeof RECOVERY_STATES)[number]) : null;
}

export function recoveryOf(conversation: TChatConversation | undefined): RecoveryReading | null {
  const runtime = (conversation as unknown as { runtime?: unknown } | undefined)?.runtime;
  return recoveryFrom(conversation) ?? recoveryFrom(runtime);
}

function recoveryFrom(value: unknown): RecoveryReading | null {
  if (!value || typeof value !== 'object') return null;
  const field = (value as { recovery?: unknown }).recovery;
  if (!field || typeof field !== 'object') return null;
  const stated = field as { state?: unknown; note?: unknown };
  if (typeof stated.state !== 'string' || !stated.state.trim()) return null;
  return {
    state: stated.state.trim(),
    note: typeof stated.note === 'string' ? stated.note.trim() : '',
  };
}

/** The host could not find the session this conversation names, so nothing here can run it again. */
export const isSessionRecoveryGone = (conversation: TChatConversation | undefined): boolean =>
  recoveryOf(conversation)?.state === 'gone';

/** The host does not know whether that session still runs; it is never drawn as if it did. */
export const isSessionRecoveryUnknown = (conversation: TChatConversation | undefined): boolean =>
  recoveryOf(conversation)?.state === 'unknown';

/**
 * The host could not use this row's working directory. Such a row can still be read as history, but nothing
 * is sent or created in it: the host refuses both, and the surface says which directory is missing.
 */
export const isCwdMissing = (conversation: TChatConversation | undefined): boolean => {
  const fields = fieldsOf(conversation) as { cwd_missing?: unknown; workspace?: unknown };
  if (fields.cwd_missing === true) return true;
  const workspace = fields.workspace;
  return Boolean(workspace && typeof workspace === 'object' && (workspace as { missing?: unknown }).missing === true);
};

/** Why that directory could not be used, as the host stated it; empty while it stated none. */
export function cwdNoteOf(conversation: TChatConversation | undefined): string {
  const fields = fieldsOf(conversation) as { cwd_note?: unknown; cwdNote?: unknown };
  return nonEmptyString(fields.cwd_note) ?? nonEmptyString(fields.cwdNote) ?? '';
}

/** The session id a renderer conversation id carries: `c1.<hostId>.<hex agent>.<hex sessionId>`. */
export function sessionIdOfConversationId(conversationId: string): string | null {
  const parts = conversationId.startsWith('c1.') ? conversationId.slice(3).split('.') : [];
  if (parts.length !== 3) return null;
  const value = parts[2];
  if (!value || value.length % 2 !== 0 || /[^0-9a-f]/.test(value)) return null;
  const bytes = new Uint8Array(value.length / 2);
  for (let index = 0; index < bytes.length; index++)
    bytes[index] = Number.parseInt(value.slice(index * 2, index * 2 + 2), 16);
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return null;
  }
}

/**
 * What a row's title really is. The host's own label is shown when it is a label: a title that only repeats the
 * session id, or that is a directory path, is not a name the user gave, so the row says it is untitled instead
 * of putting an identifier where a title belongs.
 */
export function conversationTitleOf(conversation: TChatConversation | undefined): string {
  const name = nonEmptyString(conversation?.name);
  if (!name) return '';
  if (name === sessionIdOfConversationId(conversation?.id ?? '')) return '';
  const workspace = hostWorkspaceOf(conversation);
  if (workspace && (name === workspace.cwd || name === directoryShortName(workspace.cwd))) return '';
  if (/^(?:[\\/]|[A-Za-z]:[\\/])/.test(name)) return '';
  return name;
}

/**
 * When this row last moved, in milliseconds, or null while no field of it holds a time: a row the host never
 * timed shows 未知 rather than a date invented from zero.
 */
export function activityTimeOf(conversation: TChatConversation | undefined): number | null {
  const fields = fieldsOf(conversation) as {
    modified_at?: unknown;
    created_at?: unknown;
    updated_at?: unknown;
    lastActivity?: unknown;
  };
  const times = [fields.modified_at, fields.updated_at, fields.created_at, fields.lastActivity].filter(
    (value): value is number => typeof value === 'number' && Number.isFinite(value) && value > 0
  );
  return times.length > 0 ? Math.max(...times) : null;
}
