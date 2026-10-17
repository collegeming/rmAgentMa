/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React, { useState } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { IConfirmation, IMessageAcpPermission, TMessage } from '@/common/chat/chatLib';
import { transformMessage } from '@/common/chat/chatLib';
import {
  buildMessageIndex,
  composeMessageWithIndex,
  normalizeDbMessage,
} from '@/renderer/pages/conversation/Messages/hooks';
import {
  reconcilePendingConfirmations,
  usePendingConfirmationsRecovery,
} from '@/renderer/pages/conversation/Messages/usePendingConfirmationsRecovery';
import MessageAcpPermission from '@/renderer/pages/conversation/Messages/acp/MessageAcpPermission';

const { pendingList, callbacks, updateMessages, review } = vi.hoisted(() => ({
  pendingList: vi.fn(),
  callbacks: new Map<string, Set<(data: unknown) => void>>(),
  updateMessages: vi.fn(),
  review: vi.fn(),
}));
vi.mock('@/common', () => ({
  ipcBridge: { conversation: { confirmation: { list: { invoke: pendingList }, remove: { on: () => () => {} } } } },
}));
vi.mock('@/common/adapter/httpBridge', () => ({
  httpRequest: (method: string) => (method === 'GET' ? pendingList() : review()),
  wsEmitter: (name: string) => ({
    on: (callback: (data: unknown) => void) => {
      const listeners = callbacks.get(name) ?? new Set();
      listeners.add(callback);
      callbacks.set(name, listeners);
      return () => {
        listeners.delete(callback);
      };
    },
  }),
  isBackendHttpError: () => false,
}));
vi.mock('@/renderer/pages/conversation/Messages/hooks', async (original) => ({
  ...(await original<typeof import('@/renderer/pages/conversation/Messages/hooks')>()),
  useUpdateMessageList: () => updateMessages,
}));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));

const conversationId = 'conv-probe-1';
const epoch = 'd581fc4d-431a-4838-96dd-e1a577abc8b9';
const approval = (requestId = 'request-live', generation = epoch, conversation = conversationId) => ({
  id: requestId,
  call_id: requestId,
  request_id: requestId,
  epoch: generation,
  conversation_id: conversation,
  session_id: conversation,
  title: 'isolated command',
  description: 'isolated command',
  tool_call: { tool_call_id: 'tool-1', title: 'isolated command', raw_input: { command: 'printf probe' } },
  options: [
    { option_id: 'allow-once', name: 'Allow once', kind: 'allow_once' },
    { option_id: 'reject-once', name: 'Reject', kind: 'reject_once' },
  ],
});
const confirmations = (items = [approval()]) => items as unknown as IConfirmation<unknown>[];
const liveMessage = (requestId = 'request-live', generation = epoch): IMessageAcpPermission =>
  transformMessage({
    type: 'acp_permission',
    conversation_id: conversationId,
    msg_id: `backend-${requestId}`,
    data: approval(requestId, generation),
  } as Parameters<typeof transformMessage>[0]) as IMessageAcpPermission;
let messages: TMessage[] = [];
function Scene({ initial }: { initial: TMessage[] }) {
  const [items, setItems] = useState(initial);
  messages = items;
  updateMessages.mockImplementation((updater: (current: TMessage[]) => TMessage[]) => setItems(updater));
  usePendingConfirmationsRecovery(conversationId);
  return (
    <>
      {items.map(
        (message) =>
          message.type === 'acp_permission' && (
            <div key={message.id} data-row-id={message.id}>
              <MessageAcpPermission message={message} />
            </div>
          )
      )}
    </>
  );
}
const emit = (name: string) =>
  act(() => callbacks.get(name)?.forEach((callback) => callback({ conversation_id: conversationId })));
const enabled = (id: string) =>
  screen
    .getAllByTestId(`message-acp-permission-option-${id}`)
    .filter((element) => !(element as HTMLButtonElement).disabled);
beforeEach(() => {
  pendingList.mockReset();
  review.mockReset().mockResolvedValue({ state: 'answered' });
  callbacks.clear();
  updateMessages.mockReset();
  messages = [];
});
afterEach(() => vi.restoreAllMocks());

describe('one actionable approval projection per scoped request', () => {
  it.each(['allow-once', 'reject-once'])(
    'reuses the actual live UUID card through recovery, snapshot and reconnect for %s',
    async (option) => {
      const live = liveMessage();
      expect(live.id).not.toMatch(/^confirmation:/);
      pendingList.mockResolvedValue(confirmations());
      render(<Scene initial={[live]} />);
      await waitFor(() => expect(enabled('allow-once')).toHaveLength(1));
      for (const event of ['runtime.snapshot', 'realtime.reconnected', 'runtime.snapshot']) {
        emit(event);
        await waitFor(() => expect(enabled('allow-once')).toHaveLength(1));
        expect(enabled('reject-once')).toHaveLength(1);
        expect(messages).toHaveLength(1);
        expect(messages[0].id).toBe(live.id);
      }
      fireEvent.click(enabled(option)[0]);
      await waitFor(() => expect(review).toHaveBeenCalledTimes(1));
    }
  );

  it('merges existing live and recovery duplicates in the message list instead of hiding a second card', async () => {
    const live = liveMessage();
    const duplicate = { ...live, id: 'confirmation:request-live', msg_id: 'confirmation:request-live' };
    pendingList.mockResolvedValue(confirmations([approval(), approval()]));
    render(<Scene initial={[duplicate, live]} />);
    await waitFor(() => expect(messages).toHaveLength(1));
    expect(messages[0].id).toBe(live.id);
    expect(enabled('allow-once')).toHaveLength(1);
    expect(enabled('reject-once')).toHaveLength(1);
  });

  it('does not re-enable persisted history and recovers one pending projection after history refresh', async () => {
    const history = normalizeDbMessage(liveMessage());
    pendingList.mockResolvedValue(confirmations());
    render(<Scene initial={[history]} />);
    await waitFor(() => expect(enabled('allow-once')).toHaveLength(1));
    expect(messages[0].type === 'acp_permission' && messages[0].content.readonly).toBe(true);
    act(() => updateMessages((items: TMessage[]) => items.map(normalizeDbMessage)));
    act(() => window.dispatchEvent(new Event('rmagent-history-reconciled')));
    await waitFor(() => expect(enabled('allow-once')).toHaveLength(1));
    expect(enabled('reject-once')).toHaveLength(1);
    expect(messages[0].type === 'acp_permission' && messages[0].content.readonly).toBe(true);
    pendingList.mockResolvedValue([]);
    emit('realtime.reconnected');
    await waitFor(() => expect(enabled('allow-once')).toHaveLength(0));
  });

  it('merges live frames arriving after recovery and repeated live deltas using request identity', async () => {
    pendingList.mockResolvedValue(confirmations());
    render(<Scene initial={[]} />);
    await waitFor(() => expect(enabled('allow-once')).toHaveLength(1));
    const existingId = messages[0].id;
    act(() =>
      updateMessages((items: TMessage[]) => composeMessageWithIndex(liveMessage(), items, buildMessageIndex(items)))
    );
    act(() =>
      updateMessages((items: TMessage[]) => composeMessageWithIndex(liveMessage(), items, buildMessageIndex(items)))
    );
    await waitFor(() => expect(enabled('allow-once')).toHaveLength(1));
    expect(messages).toHaveLength(1);
    expect(messages[0].id).toBe(existingId);
  });

  it('does not merge distinct request references or runtime epochs', async () => {
    const first = liveMessage('one');
    const second = liveMessage('two');
    const laterEpoch = liveMessage('one', 'second-uuid-epoch');
    const pending = confirmations([approval('one'), approval('two'), approval('one', 'second-uuid-epoch')]);
    pendingList.mockResolvedValue(pending);
    render(<Scene initial={[first, second, laterEpoch]} />);
    await waitFor(() => expect(enabled('allow-once')).toHaveLength(3));
    expect(reconcilePendingConfirmations(messages, conversationId, pending)).toHaveLength(3);
    expect(enabled('reject-once')).toHaveLength(3);
  });
});
