/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { hostFetch, initializeHost } from '@/common/host/hostTransport';
import { resetHostConnection } from '@/common/host/hostConnection';
import { conversationFactsOf, isCwdMissingFact, resetConversationFacts } from '@/common/host/conversationFacts';
import { HISTORY_COMPLETE, HISTORY_PARTIAL, resetHistoryReadings, historyReadingOf } from '@/common/host/historyState';

const ORIGIN = 'https://appassets.androidplatform.net';

type Sent = { requestId: string; documentId: string; action: string; payload: { params: Record<string, string> } };

/** The bridge of the transport tests, answering each business call with the body of the next reply. */
function harness() {
  const sent: Sent[] = [];
  let next: { status: number; body: unknown } = { status: 200, body: { success: true, data: {} } };
  const bridge = {
    postMessage: (raw: string) => {
      const message = JSON.parse(raw) as Sent;
      sent.push(message);
      const isHandshake = message.action === 'host.handshake';
      const body = isHandshake
        ? { documentId: 'document-1', protocolVersion: 1 }
        : message.action === 'events.subscribe'
          ? { subscribed: true }
          : next.body;
      bridge.onmessage?.({
        data: JSON.stringify({
          requestId: message.requestId,
          documentId: 'document-1',
          ok: isHandshake || message.action === 'events.subscribe' ? true : next.status < 400,
          status: isHandshake || message.action === 'events.subscribe' ? 200 : next.status,
          body,
        }),
      } as MessageEvent<string>);
    },
    onmessage: undefined as ((event: MessageEvent<string>) => void) | undefined,
  };
  vi.stubGlobal('window', { location: { origin: ORIGIN }, RmAgentHost: bridge, addEventListener: () => {} });
  return { sent, reply: (status: number, body: unknown) => (next = { status, body }) };
}

const page = (history?: unknown) => ({
  success: true,
  data: {
    items: [{ id: 'm-1', type: 'text' }],
    oldest_cursor: 'm-1',
    newest_cursor: 'm-1',
    has_more_before: false,
    ...(history === undefined ? {} : { history }),
  },
});

beforeEach(() => {
  resetHostConnection();
  resetHistoryReadings();
  resetConversationFacts();
});

afterEach(() => vi.unstubAllGlobals());

describe('what the host says about a history and a conversation', () => {
  it('records the history state of every message page the renderer reads', async () => {
    const h = harness();
    await initializeHost();

    h.reply(200, page({ state: 'partial', note: '索引缺一段' }));
    await hostFetch('/api/conversations/conv-probe-1/messages?limit=50');
    expect(historyReadingOf('conv-probe-1')).toEqual({ state: 'partial', note: '索引缺一段' });

    // A later page is what clears it: only the host's own next answer says the history is whole again.
    h.reply(200, page({ state: 'complete', note: null }));
    await hostFetch('/api/conversations/conv-probe-1/messages?before=m-1&limit=50');
    expect(historyReadingOf('conv-probe-1')).toEqual({ state: 'complete', note: '' });
    expect(HISTORY_PARTIAL).toBe('partial');

    // The reading belongs to the conversation the page was read for, not to the reader.
    expect(historyReadingOf('conv-probe-2')).toBeNull();
    expect(HISTORY_COMPLETE).toBe('complete');
  });

  it('never invents a history state for a page that carries none', async () => {
    const h = harness();
    await initializeHost();
    h.reply(200, page());
    await hostFetch('/api/conversations/conv-probe-1/messages?limit=50');
    expect(historyReadingOf('conv-probe-1')).toBeNull();
  });

  it('records the unusable directory of the row it belongs to, and leaves the other rows alone', async () => {
    const h = harness();
    await initializeHost();

    h.reply(200, {
      success: true,
      data: {
        items: [
          {
            id: 'conv-probe-1',
            name: 'one',
            workspace: { host_id: 'host-1', cwd: '/home/me/gone' },
            cwd_missing: true,
            cwd_note: '目录已被删除',
          },
          { id: 'conv-probe-2', name: 'two', workspace: { host_id: 'host-1', cwd: '/home/me/work' } },
        ],
      },
    });
    await hostFetch('/api/conversations');

    expect(isCwdMissingFact('conv-probe-1')).toBe(true);
    expect(conversationFactsOf('conv-probe-1').cwdNote).toBe('目录已被删除');
    expect(isCwdMissingFact('conv-probe-2')).toBe(false);
    expect(conversationFactsOf('conv-probe-3').cwdMissing).toBe(false);

    // A later read of that conversation states the same fact about it, and only about it.
    h.reply(200, {
      success: true,
      data: { id: 'conv-probe-1', name: 'one', workspace: { host_id: 'host-1', cwd: '/home/me/work' } },
    });
    await hostFetch('/api/conversations/conv-probe-1');
    expect(isCwdMissingFact('conv-probe-1')).toBe(false);
    expect(conversationFactsOf('conv-probe-2').cwdMissing).toBe(false);
  });

  it('records the session recovery a runtime snapshot or a conversation read stated', async () => {
    const h = harness();
    await initializeHost();

    h.reply(200, {
      success: true,
      data: {
        items: [
          {
            id: 'conv-probe-1',
            name: 'one',
            workspace: { host_id: 'host-1', cwd: '/home/me/work' },
            recovery: { state: 'gone', note: '会话已删除' },
          },
        ],
      },
    });
    await hostFetch('/api/conversations');
    expect(conversationFactsOf('conv-probe-1').recovery).toEqual({ state: 'gone', note: '会话已删除' });

    // A row that states no recovery keeps the one already recorded: silence is not "attached".
    h.reply(200, {
      success: true,
      data: { id: 'conv-probe-1', name: 'one', workspace: { host_id: 'host-1', cwd: '/home/me/work' } },
    });
    await hostFetch('/api/conversations/conv-probe-1');
    expect(conversationFactsOf('conv-probe-1').recovery).toEqual({ state: 'gone', note: '会话已删除' });
  });
});
