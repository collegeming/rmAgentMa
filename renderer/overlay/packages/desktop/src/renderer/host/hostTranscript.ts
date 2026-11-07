/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { httpRequest, isBackendHttpError } from '@/common/adapter/httpBridge';

/**
 * One conversation's own history, read without opening it. The host answers through
 * `GET /api/conversations/:conversationId/transcript` and never switches the current conversation, so this
 * module only reads, projects and states a refusal — it has no way to send anything.
 */

/** One row as the host answered it; every field is read as the text it carries, never as another shape. */
export type TranscriptItem = { type: string; text: string; id: string; title: string; status: string };

/** One page, as this panel reads it: the rows, and the host's own continuation (empty when it gave none). */
export type TranscriptPage = { items: TranscriptItem[]; nextCursor: string };

/** The window one call reads; the host accepts 1..200 and this panel never needs more than one screenful. */
export const TRANSCRIPT_PAGE_LIMIT = 50;

/** The longest single line a row draws; a longer one is cut with an ellipsis instead of flooding the panel. */
export const MAX_TRANSCRIPT_ROW_CHARS = 400;

export type TranscriptKind = 'user' | 'assistant' | 'tool' | 'thinking' | 'other';

export type TranscriptRow = {
  /** Stable within one projection; the panel makes it unique across pages. */
  key: string;
  kind: TranscriptKind;
  /** The Simplified Chinese label this build uses for that kind. */
  label: string;
  /** Newlines, control characters and bidi overrides removed, cut to one line. */
  text: string;
  /** `title · status`, when the host gave either; some tool rows carry their only text there. */
  meta: string;
  /** Tool calls and thinking are folded by default; a conversation's own turns are not. */
  collapsed: boolean;
  truncated: boolean;
};

const recordOf = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' ? (value as Record<string, unknown>) : {};

const textOf = (value: unknown): string => (typeof value === 'string' ? value : '');

const KIND_BY_TYPE: Record<string, TranscriptKind> = {
  user: 'user',
  human: 'user',
  assistant: 'assistant',
  agent: 'assistant',
  model: 'assistant',
  tool: 'tool',
  tool_call: 'tool',
  tool_result: 'tool',
  function: 'tool',
  thinking: 'thinking',
  reasoning: 'thinking',
};

const LABEL_BY_KIND: Record<TranscriptKind, string> = {
  user: '你',
  assistant: '助手',
  tool: '工具',
  thinking: '思考',
  other: '其他',
};

/** A bidi override, which could otherwise reorder the text around a row, is replaced by a space too. */
const BIDI_OVERRIDES = new Set([0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2066, 0x2067, 0x2068, 0x2069]);

/**
 * One line of host text as it is safe to draw: a terminal colour or cursor sequence is dropped, every other
 * control (C0, DEL, C1 and the bidi overrides) becomes a space so two runs never fuse, all whitespace is
 * folded into one space, and the line is cut to `MAX_TRANSCRIPT_ROW_CHARS`.
 */
const normalize = (value: unknown): string => {
  const text = textOf(value);
  const kept: string[] = [];
  for (let index = 0; index < text.length; index += 1) {
    const code = text.charCodeAt(index);
    if (code === 0x1b && text[index + 1] === '[') {
      // CSI: the parameter bytes run to the first final byte, which is never kept.
      let end = index + 2;
      while (end < text.length && !(text.charCodeAt(end) >= 0x40 && text.charCodeAt(end) <= 0x7e)) end += 1;
      index = end;
      continue;
    }
    kept.push(code < 0x20 || (code >= 0x7f && code <= 0x9f) || BIDI_OVERRIDES.has(code) ? ' ' : text[index]);
  }
  return kept.join('').replace(/\s+/g, ' ').trim();
};

/** One line of host text: controls neutralised, whitespace folded, and cut to `MAX_TRANSCRIPT_ROW_CHARS`. */
export function stripTranscriptText(value: unknown): string {
  return cutToLine(normalize(value));
}

const cutToLine = (line: string): string => {
  const characters = [...line];
  return characters.length > MAX_TRANSCRIPT_ROW_CHARS
    ? `${characters.slice(0, MAX_TRANSCRIPT_ROW_CHARS).join('')}…`
    : line;
};

const lineOf = (value: unknown): { text: string; truncated: boolean } => {
  const line = normalize(value);
  return { text: cutToLine(line), truncated: [...line].length > MAX_TRANSCRIPT_ROW_CHARS };
};

/** Maps the host's items onto rows: one label per kind, tool and thinking folded, every line bounded. */
export function projectTranscript(items: readonly TranscriptItem[], startIndex = 0): TranscriptRow[] {
  return items.map((item, index) => {
    const type = normalize(item.type).toLowerCase();
    const kind = KIND_BY_TYPE[type] ?? 'other';
    const text = lineOf(item.text);
    const title = stripTranscriptText(item.title);
    const status = stripTranscriptText(item.status);
    return {
      key: `${item.id || type || 'row'}-${startIndex + index}`,
      kind,
      label: LABEL_BY_KIND[kind],
      text: text.text,
      meta: [title, status].filter(Boolean).join(' · '),
      collapsed: kind === 'tool' || kind === 'thinking',
      truncated: text.truncated,
    };
  });
}

/** The one sentence each refusal is shown as. A code this build does not know keeps the host's own code. */
export function transcriptFailureText(error: unknown): string {
  const code = isBackendHttpError(error) ? error.code : '';
  const reason = isBackendHttpError(error) ? error.backendMessage : '';
  if (code === 'CWD_MISSING') return '这个会话的工作目录未知，读不到它的历史。';
  if (code === 'TRANSCRIPT_UNSUPPORTED') return '这个 Agent 没有可读取的历史。';
  if (code === 'TRANSCRIPT_UNREADABLE') return '远端读取这段历史失败。';
  if (code === 'INVALID_LIMIT') return '读取参数非法，请重试。';
  if (code === 'INVALID_CURSOR') return '继续读取的位置已失效，请重新打开这段历史。';
  if (code) return `读取失败（${code}）${reason ? `：${reason}` : ''}`;
  // An answer that broke the route's own shape is named by this build's stable code, never as a retry alone.
  if (error instanceof Error && /^HOST_[A-Z_]+$/.test(error.message)) return `读取失败（${error.message}）`;
  return '读取失败，请重试。';
}

/**
 * What a failed hand-over to the queue says. It is a different action from a read, so the working-directory
 * refusal is worded for a message, and the host's own code and reason travel with every other failure.
 */
export function queueFailureText(error: unknown): string {
  const code = isBackendHttpError(error) ? error.code : '';
  const reason = isBackendHttpError(error) ? error.backendMessage : '';
  if (code === 'CWD_MISSING') return '这个会话的工作目录未知，消息无法排队。';
  if (code) return `发送失败（${code}）${reason ? `：${reason}` : ''}`;
  return '发送失败，请重试。';
}

/**
 * Reads one page of that conversation's history, latest first. The only continuation this panel follows is the
 * `next_cursor` the host itself gave; `has_more` without one leaves nothing to ask for. An answer that names
 * another conversation or carries no item list is refused as a failure, never drawn as an empty history.
 */
export async function readTranscript(conversationId: string, cursor?: string): Promise<TranscriptPage> {
  const params = new URLSearchParams({ limit: String(TRANSCRIPT_PAGE_LIMIT) });
  if (cursor) params.set('cursor', cursor);
  const answer = recordOf(
    await httpRequest<unknown>(
      'GET',
      `/api/conversations/${encodeURIComponent(conversationId)}/transcript?${params.toString()}`
    )
  );
  if (answer.conversation_id !== conversationId) throw new Error('HOST_TRANSCRIPT_MISMATCH');
  if (!Array.isArray(answer.items)) throw new Error('HOST_TRANSCRIPT_INVALID');
  return {
    items: answer.items.map((row) => {
      const item = recordOf(row);
      return {
        type: textOf(item.type),
        text: textOf(item.text),
        id: textOf(item.id),
        title: textOf(item.title),
        status: textOf(item.status),
      };
    }),
    nextCursor: answer.has_more === true ? textOf(answer.next_cursor) : '',
  };
}
