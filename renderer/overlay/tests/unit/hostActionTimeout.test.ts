/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  HOST_ACTIONS,
  HOST_READ_TIMEOUT_MS,
  HOST_WAIT_TIMEOUT_MS,
  NativeTransport,
  hostActionTimeoutMs,
  isHostTimeout,
} from '@/common/host/hostTransport';
import { hostConnectionSnapshot, resetHostConnection } from '@/common/host/hostConnection';

/**
 * The actions that wait on the far end. Every other action the transport declares is served from what the host
 * already holds, and keeps the short window: a probe over five Agents and the session index rescanned beside it
 * took over 25 seconds on a real phone, while a credentials read must not make the UI wait that long.
 */
const WAITING_ACTIONS = [
  'attachment.finish',
  'challenges.answer',
  'conversation.read',
  'conversations.create',
  'detection.refresh',
  'hosts.refresh',
  'messages.before',
  'messages.send',
  'runtime.ensure',
  'search.sessions',
  'terminal.command.start',
  'terminal.open',
];

type Frame = { requestId: string; documentId: string; action: string; payload: unknown };

function harness() {
  const sent: Frame[] = [];
  const bridge = {
    postMessage: (raw: string) => {
      sent.push(JSON.parse(raw) as Frame);
    },
    onmessage: undefined as ((event: MessageEvent<string>) => void) | undefined,
  };
  // The real budget table: this file is about the windows themselves.
  const transport = new NativeTransport(bridge);
  const answer = (action: string, body: unknown) => {
    const frame = sent.findLast((item) => item.action === action);
    if (!frame) throw new Error(`no request for ${action}`);
    bridge.onmessage?.({
      data: JSON.stringify({ requestId: frame.requestId, documentId: 'document-1', ok: true, status: 200, body }),
    } as MessageEvent<string>);
  };
  return { sent, bridge, transport, answer };
}

/** Opens one transport on document-1, the way the renderer's own bootstrap does. */
async function opened() {
  const host = harness();
  const ready = host.transport.initialize();
  host.answer('host.handshake', { documentId: 'document-1', protocolVersion: 1 });
  await ready;
  return host;
}

beforeEach(() => {
  vi.useFakeTimers();
  resetHostConnection();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('the window each host action gets', () => {
  it('decides every action this transport declares, and only the far-end ones wait long', () => {
    expect(HOST_READ_TIMEOUT_MS).toBeLessThan(HOST_WAIT_TIMEOUT_MS);
    // Exactly these actions wait on the far end; adding one without deciding its window fails here.
    const waiting = HOST_ACTIONS.filter((action) => hostActionTimeoutMs(action) === HOST_WAIT_TIMEOUT_MS);
    expect(waiting.length).toBe(WAITING_ACTIONS.length);
    expect(new Set(waiting)).toEqual(new Set(WAITING_ACTIONS));
    for (const action of HOST_ACTIONS)
      expect([HOST_READ_TIMEOUT_MS, HOST_WAIT_TIMEOUT_MS]).toContain(hostActionTimeoutMs(action));
    // The reads the phone asks for while the user is looking at it keep the short window.
    for (const action of ['challenges.read', 'hosts.list', 'conversations.list', 'messages.list', 'approvals.list'])
      expect(hostActionTimeoutMs(action)).toBe(HOST_READ_TIMEOUT_MS);
  });

  it('recognizes its own timeout code and nothing else', () => {
    expect(isHostTimeout(new Error('HOST_TIMEOUT: hosts.refresh; result unknown'))).toBe(true);
    expect(
      isHostTimeout(new Error('HOST_ACTION_FAILED: hosts.refresh (500) HOST_REFRESH_FAILED: 主机未能探测。'))
    ).toBe(false);
    expect(isHostTimeout('HOST_TIMEOUT')).toBe(false);
  });
});

describe('one action running past its window', () => {
  it('fails that action alone, leaving the connection live and the next request answered', async () => {
    const host = await opened();

    const slow = host.transport.call('messages.send', {
      params: { conversationId: 'conv-probe-1' },
      query: {},
      body: { content: '开始' },
    });
    const slowFailed = expect(slow).rejects.toThrow('HOST_TIMEOUT: messages.send; result unknown');

    // A read's window passes while the slow action is still out; nothing about the connection changed.
    await vi.advanceTimersByTimeAsync(HOST_READ_TIMEOUT_MS + 1);
    expect(hostConnectionSnapshot().state).toBe('live');

    // The next request goes out and is answered: a probe running late is not a host that stopped answering.
    const challenges = host.transport.call('challenges.read');
    host.answer('challenges.read', { items: [] });
    await expect(challenges).resolves.toMatchObject({ status: 200, body: { items: [] } });
    expect(hostConnectionSnapshot().state).toBe('live');

    // The slow action's own window is what ends it.
    await vi.advanceTimersByTimeAsync(HOST_WAIT_TIMEOUT_MS - HOST_READ_TIMEOUT_MS + 1);
    await slowFailed;
    expect(hostConnectionSnapshot().state).toBe('live');

    const hosts = host.transport.call('hosts.list');
    host.answer('hosts.list', { items: [] });
    await expect(hosts).resolves.toMatchObject({ status: 200, body: { items: [] } });
    expect(hostConnectionSnapshot().state).toBe('live');
    host.transport.close();
  });

  it('keeps a read on the 15 second window, so a slow one is reported instead of the screen waiting', async () => {
    const host = await opened();

    const read = host.transport.call('hosts.list');
    const failed = expect(read).rejects.toThrow('HOST_TIMEOUT: hosts.list; result unknown');
    await vi.advanceTimersByTimeAsync(HOST_READ_TIMEOUT_MS - 1);
    // Still inside its window: nothing has been reported yet.
    expect(hostConnectionSnapshot().state).toBe('live');
    await vi.advanceTimersByTimeAsync(2);
    await failed;
    expect(hostConnectionSnapshot().state).toBe('live');
    host.transport.close();
  });

  it('marks the connection interrupted when the bridge itself cannot take the message', async () => {
    const host = await opened();

    host.bridge.postMessage = () => {
      throw new Error('bridge gone');
    };
    await expect(host.transport.call('hosts.list')).rejects.toThrow('bridge gone');
    expect(hostConnectionSnapshot().state).toBe('interrupted');
    expect(hostConnectionSnapshot().reason).toBe('bridge gone');
    host.transport.close();
  });
});
