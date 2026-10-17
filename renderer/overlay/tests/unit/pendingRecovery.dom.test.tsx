/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { usePendingConfirmationsRecovery } from '@/renderer/pages/conversation/Messages/usePendingConfirmationsRecovery';
import type { TMessage } from '@/common/chat/chatLib';
const { list, handlers, update } = vi.hoisted(() => ({
  list: vi.fn(),
  handlers: new Map<string, () => void>(),
  update: vi.fn(),
}));
vi.mock('@/common', () => ({
  ipcBridge: { conversation: { confirmation: { list: { invoke: list }, remove: { on: () => () => {} } } } },
}));
vi.mock('@/common/adapter/httpBridge', () => ({
  wsEmitter: (name: string) => ({
    on: (fn: () => void) => {
      handlers.set(name, fn);
      return () => handlers.delete(name);
    },
  }),
}));
vi.mock('@/renderer/pages/conversation/Messages/hooks', () => ({ useUpdateMessageList: () => update }));
const approval = {
  request_id: 'live-ref',
  conversation_id: 'conv-probe-1',
  session_id: 'conv-probe-1',
  epoch: 'epoch-1',
  options: [{ option_id: 'allow-once' }],
  tool_call: { tool_call_id: 'tool-1' },
};
beforeEach(() => {
  list.mockReset();
  handlers.clear();
  update.mockReset();
});

describe('pending recovery after live history reconciliation', () => {
  it('rebuilds independent authoritative cards after snapshot and post-history refresh', async () => {
    let messages = [] as TMessage[];
    update.mockImplementation((fn: (items: TMessage[]) => TMessage[]) => {
      messages = fn(messages);
    });
    list.mockResolvedValue([]);
    renderHook(() => usePendingConfirmationsRecovery('conv-probe-1'));
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
    messages = [
      {
        id: 'live-card',
        type: 'acp_permission',
        conversation_id: 'conv-probe-1',
        content: { ...approval, readonly: true },
      },
    ] as unknown as TMessage[];
    list.mockResolvedValue([approval]);
    act(() => handlers.get('runtime.snapshot')?.());
    await waitFor(() => expect(messages.some((message) => message.id === 'confirmation:live-ref')).toBe(true));
    expect(messages[0].type === 'acp_permission' && messages[0].content.readonly).toBe(true);
    messages = messages.map((message) =>
      message.id === 'confirmation:live-ref' ? { ...message, content: { ...message.content, readonly: true } } : message
    ) as TMessage[];
    act(() => window.dispatchEvent(new Event('rmagent-history-reconciled')));
    await waitFor(() =>
      expect(messages.find((message) => message.id === 'confirmation:live-ref')?.content).toMatchObject({
        readonly: false,
        request_id: 'live-ref',
      })
    );
    list.mockResolvedValue([]);
    act(() => handlers.get('realtime.reconnected')?.());
    await waitFor(() => expect(messages).toHaveLength(1));
  });
});
