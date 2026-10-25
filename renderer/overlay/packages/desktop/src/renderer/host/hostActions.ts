/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { httpRequest } from '@/common/adapter/httpBridge';

/**
 * The host's own cross-session surface: content search over every saved host, the waiting-work projection,
 * the queue one conversation can hand another, and the context pack a conversation offers to a new one.
 * Every answer is checked before it is shown — an unreadable answer is a failure, never an empty list drawn
 * as "nothing found", a row the host says cannot be opened is not made clickable, and a pack with no text is
 * never put into an input box.
 */

const recordOf = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' ? (value as Record<string, unknown>) : {};

const rowList = (value: unknown): Record<string, unknown>[] | null =>
  Array.isArray(value) ? value.map(recordOf) : null;

/** The first non-empty string among `names`, read as the value it carries. */
const pick = (row: Record<string, unknown>, ...names: string[]): string => {
  for (const name of names) {
    const value = row[name];
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return '';
};

const pickNumber = (row: Record<string, unknown>, ...names: string[]): number | null => {
  for (const name of names) {
    const value = row[name];
    if (typeof value === 'number' && Number.isFinite(value)) return value;
  }
  return null;
};

const pickStrings = (row: Record<string, unknown>, name: string): string[] => {
  const value = row[name];
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string' && Boolean(item)) : [];
};

/** One search hit: which host, which Agent, which directory, the matched line, and whether it can be opened. */
export type SessionSearchHit = {
  conversationId: string;
  title: string;
  hostId: string;
  agent: string;
  cwd: string;
  snippet: string;
  /** The host said this conversation is no longer held, so the row is shown but not made clickable. */
  openable: boolean;
};

/** What one search covered: whether every part of the range was read, and what stayed unread. */
export type SearchCoverage = {
  complete: boolean;
  truncated: boolean;
  failedSources: { hostId: string; reason: string }[];
  /** Stable codes for parts of the range that were skipped rather than read, e.g. search_timeout. */
  problems: string[];
};

export type SessionSearchAnswer = { hits: SessionSearchHit[]; coverage: SearchCoverage };

export type SessionSearchQuery = { q: string; hostId?: string; agent?: string; limit?: number };

/** Searches the content of the sessions the host indexes, across its saved hosts. */
export async function searchSessions(query: SessionSearchQuery): Promise<SessionSearchAnswer> {
  const params = new URLSearchParams({ q: query.q });
  if (query.hostId) params.set('host_id', query.hostId);
  if (query.agent) params.set('agent', query.agent);
  if (query.limit) params.set('limit', String(query.limit));
  const answer = recordOf(await httpRequest<unknown>('GET', `/api/search?${params.toString()}`));
  const rows = rowList(answer.items) ?? rowList(answer.hits) ?? rowList(answer.results);
  if (!rows) throw new Error('HOST_SEARCH_INVALID');
  const coverage = recordOf(answer.coverage);
  return {
    hits: rows.map((row) => ({
      conversationId: pick(row, 'conversation_id', 'session_id', 'session', 'id'),
      title: pick(row, 'conversation_name', 'title', 'name'),
      hostId: pick(row, 'host_id', 'host'),
      agent: pick(row, 'agent', 'agent_id'),
      cwd: pick(row, 'cwd', 'workspace', 'directory', 'dir'),
      snippet: pick(row, 'snippet', 'excerpt', 'text', 'match', 'content'),
      openable: row.openable !== false,
    })),
    coverage: {
      complete: coverage.complete === true,
      truncated: coverage.truncated === true,
      failedSources: (rowList(coverage.failed_sources) ?? []).map((row) => ({
        hostId: pick(row, 'host_id', 'host', 'host_name'),
        reason: pick(row, 'reason', 'error', 'message', 'code'),
      })),
      problems: pickStrings(coverage, 'problems'),
    },
  };
}

/** One piece of work waiting on the user, as the host's own attention projection reports it. */
export type InboxItem = {
  /** Stable reason codes, e.g. approval, question, failure, busy; an unknown code is shown as it is. */
  reasons: string[];
  conversationId: string;
  hostId: string;
  agent: string;
  summary: string;
  lastError: string;
  pendingApprovals: number;
  pendingQuestions: number;
  updatedAt: number | null;
};

export type InboxAnswer = {
  items: InboxItem[];
  /** The route projects the open conversation alone; anything else is refused, not guessed at. */
  scope: string;
};

/** The waiting work the host holds, in its own order — this page never re-ranks it. */
export async function listInbox(): Promise<InboxAnswer> {
  const answer = recordOf(await httpRequest<unknown>('GET', '/api/inbox'));
  const rows = rowList(answer.items) ?? rowList(answer.inbox) ?? rowList(answer.entries);
  if (!rows) throw new Error('HOST_INBOX_INVALID');
  return {
    items: rows.map((row) => ({
      reasons: pickStrings(row, 'reasons'),
      conversationId: pick(row, 'conversation_id', 'session_id', 'session', 'id'),
      hostId: pick(row, 'host_id', 'host'),
      agent: pick(row, 'agent', 'agent_id'),
      summary: pick(row, 'summary', 'detail', 'message', 'text'),
      lastError: pick(row, 'last_error', 'error'),
      pendingApprovals: pickNumber(row, 'pending_approvals') ?? 0,
      pendingQuestions: pickNumber(row, 'pending_questions') ?? 0,
      updatedAt: pickNumber(row, 'updated_at', 'at', 'created_at', 'time'),
    })),
    scope: pick(answer, 'scope'),
  };
}

/**
 * One queued message, the conversation it waits for, and the state the host holds it in. Only a message the
 * host still reports as pending can be withdrawn; every other one is shown with its own state and reason.
 */
export type QueuedMessage = {
  queueId: string;
  session: string;
  conversationId: string;
  hostId: string;
  agent: string;
  title: string;
  kind: string;
  text: string;
  /** The host's own state name, e.g. `pending` or `withdrawn`, read as it came. */
  state: string;
  /** Whether the host still holds this message as waiting for its conversation. */
  pending: boolean;
  createdAt: number | null;
};

export async function listQueue(sessionId?: string): Promise<QueuedMessage[]> {
  const path = sessionId ? `/api/queue?session_id=${encodeURIComponent(sessionId)}` : '/api/queue';
  const answer = recordOf(await httpRequest<unknown>('GET', path));
  const rows = rowList(answer.items) ?? rowList(answer.queue);
  if (!rows) throw new Error('HOST_QUEUE_INVALID');
  return rows.map((row) => {
    const state = pick(row, 'state', 'status');
    return {
      queueId: pick(row, 'queue_id', 'id'),
      session: pick(row, 'session_id', 'conversation_id', 'session'),
      conversationId: pick(row, 'conversation_id', 'conversation'),
      hostId: pick(row, 'host_id', 'host'),
      agent: pick(row, 'agent', 'agent_id'),
      title: pick(row, 'conversation_name', 'title', 'name'),
      kind: pick(row, 'kind', 'type'),
      text: pick(row, 'text', 'message', 'content'),
      state,
      // A row that names no state is not read as pending: this page never offers a withdraw the host may refuse.
      pending: typeof row.pending === 'boolean' ? row.pending : state === 'pending',
      createdAt: pickNumber(row, 'created_at', 'at', 'time'),
    };
  });
}

/** `notify` is a notice; `request` is sent when the target conversation is next opened. */
export type QueueKind = 'notify' | 'request';

export type QueueMessageInput = {
  /** The target conversation the product already holds, and the host/Agent/session it resolves to. */
  conversationId: string;
  hostId?: string;
  agent?: string;
  sessionId?: string;
  kind: QueueKind;
  text: string;
};

/** Hands one message to another conversation's queue. It is queued here, never delivered by this call. */
export async function sendToQueue(input: QueueMessageInput): Promise<void> {
  await httpRequest<unknown>('POST', '/api/queue', {
    conversation_id: input.conversationId,
    host_id: input.hostId ?? null,
    agent: input.agent ?? null,
    session_id: input.sessionId ?? null,
    kind: input.kind,
    text: input.text,
  });
}

/**
 * Withdraws one queued message so it can never be delivered later. The host's answer has to say it was
 * cancelled: an answer that does not is a failure, never a withdrawal.
 */
export async function cancelQueuedMessage(queueId: string): Promise<void> {
  const answer = recordOf(await httpRequest<unknown>('DELETE', `/api/queue/${encodeURIComponent(queueId)}`));
  if (answer.cancelled !== true) throw new Error('HOST_QUEUE_NOT_WITHDRAWN');
}

/** One packed context window: the text to fill in, and the host and directory it came from. */
export type ReferencePack = { text: string; hostId: string; cwd: string; status: string };

/** Asks the host to pack this conversation's own window; the answer is what goes into the input box. */
export async function packReference(conversationId: string): Promise<ReferencePack> {
  const answer = await httpRequest<unknown>(
    'POST',
    `/api/conversations/${encodeURIComponent(conversationId)}/reference`
  );
  const row = recordOf(answer);
  const text = typeof answer === 'string' ? answer : pick(row, 'text', 'pack', 'reference', 'content');
  if (!text) throw new Error('HOST_REFERENCE_EMPTY');
  return { text, hostId: pick(row, 'host_id', 'host'), cwd: pick(row, 'cwd'), status: pick(row, 'status') };
}
