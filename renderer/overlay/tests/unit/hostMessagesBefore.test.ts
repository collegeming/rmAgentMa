/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { hostFetch, initializeHost, resolveHostRoute } from '@/common/host/hostTransport';
import { resetHostConnection } from '@/common/host/hostConnection';

const ORIGIN = 'https://appassets.androidplatform.net';

type Sent = {
  requestId: string;
  documentId: string;
  action: string;
  payload: { params: Record<string, string>; query: Record<string, string> };
};

/** The fake bridge of the existing transport tests, plus the one reply the next business call gets. */
function harness() {
  const sent: Sent[] = [];
  let next: { status: number; body: unknown } = { status: 200, body: { success: true, data: {} } };
  const bridge = {
    postMessage: (raw: string) => {
      const message = JSON.parse(raw) as Sent;
      sent.push(message);
      const body =
        message.action === 'host.handshake'
          ? { documentId: 'document-1', protocolVersion: 1 }
          : message.action === 'events.subscribe'
            ? { subscribed: true }
            : next.body;
      const status = message.action === 'host.handshake' || message.action === 'events.subscribe' ? 200 : next.status;
      bridge.onmessage?.({
        data: JSON.stringify({
          requestId: message.requestId,
          documentId: 'document-1',
          ok: status < 400,
          status,
          body,
        }),
      } as MessageEvent<string>);
    },
    onmessage: undefined as ((event: MessageEvent<string>) => void) | undefined,
  };
  vi.stubGlobal('window', { location: { origin: ORIGIN }, RmAgentHost: bridge, addEventListener: () => {} });
  return { sent, reply: (status: number, body: unknown) => (next = { status, body }) };
}

const page = (ids: string[]) => ({
  success: true,
  data: {
    items: ids.map((id) => ({ id, type: 'text' })),
    oldest_cursor: ids[0],
    newest_cursor: ids.at(-1),
    has_more_before: true,
  },
});

beforeEach(() => {
  resetHostConnection();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the older page of the renderer history paging', () => {
  it('resolves a cursor to the host route that reads one page above the window', () => {
    expect(
      resolveHostRoute('GET', '/api/conversations/conv-probe-1/messages?before=m-1&limit=50&content_mode=compact')
    ).toEqual({
      action: 'messages.before',
      payload: {
        params: { conversationId: 'conv-probe-1' },
        query: { before: 'm-1', limit: '50', content_mode: 'compact' },
        body: null,
      },
    });
    // Without a cursor, and with an anchor or an `after` cursor, the caller is reading the window itself.
    expect(resolveHostRoute('GET', '/api/conversations/conv-probe-1/messages?limit=50').action).toBe('messages.list');
    expect(
      resolveHostRoute('GET', '/api/conversations/conv-probe-1/messages?anchor_message_id=m-1&before=m-1').action
    ).toBe('messages.list');
    expect(resolveHostRoute('GET', '/api/conversations/conv-probe-1/messages?after=m-1').action).toBe('messages.list');
  });

  it('reads the page the host answers, and states the end of the history the host refuses with', async () => {
    const h = harness();
    await initializeHost();
    h.reply(200, page(['m-1', 'm-2']));
    const loaded = await hostFetch('/api/conversations/conv-probe-1/messages?before=m-3&limit=50&content_mode=compact');
    expect(h.sent.at(-1)?.action).toBe('messages.before');
    expect(loaded.status).toBe(200);
    expect(await loaded.json()).toMatchObject({ data: { oldest_cursor: 'm-1', has_more_before: true } });

    // 409 HISTORY_EXHAUSTED means the product holds no older page: the paging protocol reads that as an end.
    h.reply(409, { success: false, code: 'HISTORY_EXHAUSTED', error: '该会话没有更早的一页历史。' });
    const end = await hostFetch('/api/conversations/conv-probe-1/messages?before=m-3&limit=50');
    expect(end.status).toBe(200);
    expect(await end.json()).toEqual({
      success: true,
      data: { items: [], has_more_before: false, has_more_after: true },
    });
  });

  it('leaves every other refusal to fail as itself', async () => {
    const h = harness();
    await initializeHost();
    h.reply(500, { success: false, code: 'HISTORY_LOAD_FAILED', error: '更早的历史未能读取。' });
    const failed = await hostFetch('/api/conversations/conv-probe-1/messages?before=m-3&limit=50');
    expect(failed.status).toBe(500);
    expect(await failed.json()).toMatchObject({ code: 'HISTORY_LOAD_FAILED' });

    h.reply(422, { success: false, code: 'INVALID_CURSOR', error: '游标不在当前已加载的历史中。' });
    const stale = await hostFetch('/api/conversations/conv-probe-1/messages?before=m-old&limit=50');
    expect(stale.status).toBe(422);
    expect(await stale.json()).toMatchObject({ code: 'INVALID_CURSOR' });
  });

  it('reaches the question and challenge routes the host serves', async () => {
    const h = harness();
    await initializeHost();
    h.reply(200, { success: true, data: { state: 'answered' } });
    await hostFetch('/api/conversations/conv-probe-1/interactions/req-1/review', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ answer: 'ok', epoch: 'epoch-1' }),
    });
    expect(h.sent.at(-1)).toMatchObject({
      action: 'interactions.review',
      payload: {
        params: { conversationId: 'conv-probe-1', requestRef: 'req-1' },
        body: { answer: 'ok', epoch: 'epoch-1' },
      },
    });

    h.reply(200, { success: true, data: { challenge: null } });
    await hostFetch('/api/challenges');
    expect(h.sent.at(-1)).toMatchObject({ action: 'challenges.read', payload: { params: {}, query: {} } });

    h.reply(200, { success: true, data: { answered: true } });
    await hostFetch('/api/challenges/challenge-1/answer', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ answer: 'hunter2' }),
    });
    expect(h.sent.at(-1)).toMatchObject({
      action: 'challenges.answer',
      payload: { params: { challengeId: 'challenge-1' }, body: { answer: 'hunter2' } },
    });
  });
});
