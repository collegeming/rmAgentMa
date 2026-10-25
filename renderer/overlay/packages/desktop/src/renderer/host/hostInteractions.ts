/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { BackendHttpError } from '@/common/adapter/httpBridge';
import { hostFetch } from '@/common/host/hostTransport';

/**
 * The host chats on a question card and asks for a credential on its own routes. Both are called through
 * `hostFetch` directly instead of `httpRequest` on purpose: the paging helper logs the request it sends, and a
 * password, a passphrase or a host key answer must never appear in a log. Every refusal is raised as the host's
 * own stable code, so the surface renders the code the host answered instead of a rounded-off failure.
 */
async function hostRoute<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
  const response = await hostFetch(path, {
    method,
    ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  });
  if (!response.ok) {
    const raw = await response.text().catch(() => '');
    let failure: unknown = raw;
    try {
      failure = JSON.parse(raw) as unknown;
    } catch {
      failure = raw;
    }
    throw new BackendHttpError({ method, path, status: response.status, body: failure });
  }
  if (!response.headers.get('Content-Type')?.includes('application/json')) return undefined as T;
  const answer = (await response.json()) as unknown;
  if (answer && typeof answer === 'object' && 'data' in answer) return (answer as { data: T }).data;
  return answer as T;
}

/**
 * Answering the live question of the open conversation. The reference is the question card's own request id and
 * the answer is the exact text the host validates against the schema the Agent stated: a single-string question
 * takes the text, a multi-field one takes one JSON object, and a question that is no longer the pending instance
 * of that conversation is refused with 409 NOT_PENDING and never reaches the Agent.
 */
export function submitQuestionAnswer(
  conversationId: string,
  requestRef: string,
  answer: string,
  epoch?: number | string
): Promise<{ state?: string }> {
  return hostRoute<{ state?: string }>(
    'POST',
    `/api/conversations/${encodeURIComponent(conversationId)}/interactions/${encodeURIComponent(requestRef)}/review`,
    { answer, ...(epoch === undefined ? {} : { epoch }) }
  );
}

/**
 * The connection waiting for the user right now, if any. The payload carries the host, the kind and the prompt
 * and never the answer, and a `null` challenge is the truth of a host that asks for nothing — not a failure.
 */
export type ConnectionChallenge = {
  id: string;
  /** The saved host behind it as `host-<id>`, or empty once that host is gone. */
  hostId: string;
  hostName: string;
  /** `host_key`, `password` or `key_passphrase`; anything else is a request this build cannot answer. */
  kind: string;
  message: string;
  /** True while the answer is a credential the surface must not show, store, log or echo. */
  secret: boolean;
};

function projectChallenge(value: unknown): ConnectionChallenge | null {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'object') throw new Error('HOST_CHALLENGE_INVALID');
  const row = value as Record<string, unknown>;
  const text = (name: string): string => (typeof row[name] === 'string' ? (row[name] as string) : '');
  const id = text('id');
  const kind = text('kind');
  if (!id || !kind) throw new Error('HOST_CHALLENGE_INVALID');
  return {
    id,
    hostId: text('host_id'),
    hostName: text('host_name'),
    kind,
    message: text('message'),
    secret: row.secret === true,
  };
}

export async function readConnectionChallenge(): Promise<ConnectionChallenge | null> {
  const answer = await hostRoute<{ challenge?: unknown }>('GET', '/api/challenges');
  if (!answer || typeof answer !== 'object') throw new Error('HOST_CHALLENGE_INVALID');
  return projectChallenge(answer.challenge);
}

/**
 * Hands the user's answer to the pending challenge and nothing else. An absent answer cancels the request, which
 * is the same act as the cancel button of the product's own dialog; the value is passed through alone, and an id
 * that is no longer the pending one is refused with 409 CHALLENGE_NOT_PENDING.
 */
export function answerConnectionChallenge(challengeId: string, answer: string | null): Promise<{ answered?: boolean }> {
  return hostRoute<{ answered?: boolean }>('POST', `/api/challenges/${encodeURIComponent(challengeId)}/answer`, {
    answer,
  });
}
