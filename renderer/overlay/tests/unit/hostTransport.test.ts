/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NativeTransport, resolveHostRoute } from '@/common/host/hostTransport';

function harness(timeout = 1000) {
  const sent: { requestId: string; documentId: string; action: string; payload: unknown }[] = [];
  const bridge = {
    postMessage: (raw: string) => {
      sent.push(JSON.parse(raw));
    },
    onmessage: undefined as ((event: MessageEvent<string>) => void) | undefined,
  };
  // A short window for every action, so a case never waits on the real budget table.
  const transport = new NativeTransport(bridge, () => timeout);
  const reply = (body: unknown, documentId = 'document-1') => {
    bridge.onmessage?.({
      data: JSON.stringify({ ...sent.at(-1), documentId, ok: true, status: 200, body }),
    } as MessageEvent<string>);
  };
  return { sent, bridge, transport, reply };
}

afterEach(() => vi.useRealTimers());

describe('native host v1 transport', () => {
  it('maps exact routes and query without widening the action surface', () => {
    expect(resolveHostRoute('PUT', '/api/conversations/conv-probe-1/config-options/model?source=ui')).toEqual({
      action: 'config.set',
      payload: { params: { conversationId: 'conv-probe-1', optionId: 'model' }, query: { source: 'ui' }, body: null },
    });
    expect(() => resolveHostRoute('DELETE', '/api/conversations/conv-probe-1')).toThrow('HOST_ACTION_UNSUPPORTED');
    expect(() => resolveHostRoute('GET', 'https://example.com/api/auth/user')).toThrow('HOST_ORIGIN_DENIED');
  });

  it('requires a native-generated document before business requests', async () => {
    const h = harness();
    await expect(h.transport.call('runtime.ensure')).rejects.toThrow('HOST_NOT_READY');
    const ready = h.transport.initialize();
    expect(h.sent[0]).toMatchObject({
      documentId: '',
      action: 'host.handshake',
      payload: { params: {}, query: {}, body: null },
    });
    h.reply({ documentId: 'document-1', protocolVersion: 1 });
    await ready;
    await expect(h.transport.call('arbitrary.shell')).rejects.toThrow('HOST_ACTION_UNSUPPORTED');
    const result = h.transport.call('auth.read');
    expect(h.sent.at(-1)?.documentId).toBe('document-1');
    h.reply({ success: true, user: { id: 'probe' } });
    expect((await result).body).toEqual({ success: true, user: { id: 'probe' } });
    h.transport.close();
  });

  it('accepts maximum 16k UTF-16 draft content including JSON escapes within the native 100KiB envelope', async () => {
    const h = harness();
    const ready = h.transport.initialize();
    h.reply({ documentId: 'document-1', protocolVersion: 1 });
    await ready;
    for (const text of [
      '中'.repeat(16384),
      String.fromCharCode(0).repeat(16384),
      '😀'.repeat(8192),
      '"\n'.repeat(8192),
    ]) {
      const result = h.transport.call('draft.save', {
        params: { conversationId: 'conv-probe-1' },
        query: {},
        body: { text, files: [] },
      });
      expect(new TextEncoder().encode(JSON.stringify(h.sent.at(-1))).byteLength).toBeLessThanOrEqual(100 * 1024);
      h.reply({ saved: true });
      await result;
    }
    h.transport.close();
  });

  it('rejects late replies from old documents and times out without replay', async () => {
    vi.useFakeTimers();
    const h = harness(10);
    const ready = h.transport.initialize();
    h.reply({ documentId: 'document-1', protocolVersion: 1 });
    await ready;
    const result = h.transport.call('messages.send');
    const failed = expect(result).rejects.toThrow('HOST_TIMEOUT');
    h.reply({}, 'old-document');
    await vi.advanceTimersByTimeAsync(11);
    await failed;
    expect(h.sent).toHaveLength(2);
    h.transport.close();
  });

  it('deduplicates events and rejects stale documents and epochs', async () => {
    const h = harness();
    const ready = h.transport.initialize();
    h.reply({ documentId: 'document-1', protocolVersion: 1 });
    await ready;
    const receive = vi.fn();
    h.transport.on(receive);
    const event = {
      type: 'event',
      documentId: 'document-1',
      name: 'message.stream',
      data: { content: '中文' },
      epoch: 2,
      seq: 1,
    };
    const emit = (value: unknown) => h.bridge.onmessage?.({ data: JSON.stringify(value) } as MessageEvent<string>);
    emit(event);
    emit(event);
    emit({ ...event, documentId: 'old', seq: 2 });
    emit({ ...event, epoch: 1, seq: 2 });
    emit({ ...event, seq: 2 });
    expect(receive).toHaveBeenCalledTimes(2);
    h.transport.close();
  });

  it('resolves the maintenance and host refresh routes the contract declares instead of refusing them', async () => {
    expect(resolveHostRoute('POST', '/api/index/clear')).toEqual({
      action: 'index.clear',
      payload: { params: {}, query: {}, body: null },
    });
    expect(resolveHostRoute('POST', '/api/hosts/host-3/refresh')).toEqual({
      action: 'hosts.refresh',
      payload: { params: { hostId: 'host-3' }, query: {}, body: null },
    });

    const h = harness();
    const ready = h.transport.initialize();
    h.reply({ documentId: 'document-1', protocolVersion: 1 });
    await ready;
    const clearing = h.transport.call('index.clear');
    expect(h.sent.at(-1)?.action).toBe('index.clear');
    h.reply({ cleared: true, sessions_before: 2, sessions: 0 });
    expect((await clearing).body).toEqual({ cleared: true, sessions_before: 2, sessions: 0 });
    const refreshing = h.transport.call('hosts.refresh', { params: { hostId: 'host-3' }, query: {}, body: null });
    expect(h.sent.at(-1)?.action).toBe('hosts.refresh');
    h.reply({ host_id: 'host-3', refreshed: true, sessions: 4, detection: { checked: true, agents: [] } });
    expect((await refreshing).body).toMatchObject({ refreshed: true, sessions: 4 });
    h.transport.close();
  });
});
