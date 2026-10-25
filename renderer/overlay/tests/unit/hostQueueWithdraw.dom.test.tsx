/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider, Menu } from '@arco-design/web-react';
// Arco's imperative toasts render through the root this adapter installs under React 19, as main.tsx does.
import '@arco-design/web-react/lib/_util/react-19-adapter';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BackendHttpError } from '@/common/adapter/httpBridge';
import type { TChatConversation } from '@/common/config/storage';

const { request, t, list } = vi.hoisted(() => ({
  request: vi.fn(),
  t: (key: string, options?: Record<string, unknown>) =>
    options ? `${key}(${Object.values(options).join('|')})` : key,
  list: { conversations: [] as TChatConversation[] },
}));

vi.mock('@/common/adapter/httpBridge', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/common/adapter/httpBridge')>()),
  httpRequest: request,
}));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t, i18n: { language: 'zh-CN' } }) }));
vi.mock('@renderer/pages/conversation/GroupedHistory/hooks/useConversationListSync', () => ({
  useConversationListSync: () => ({ conversations: list.conversations }),
}));

import HostCrossSessionMenuItems from '@renderer/host/HostCrossSessionMenuItems';

const conversation = (id: string, name: string) =>
  ({
    id,
    name,
    type: 'acp',
    created_at: 1,
    modified_at: 1,
    extra: { backend: 'claude' },
    model: {},
  }) as TChatConversation;

const queued = (overrides: Record<string, unknown> = {}) => ({
  queue_id: 'queue-1',
  conversation_id: 'conv-target',
  host_id: 'host-3',
  agent: 'kimi',
  session_id: 'session-9',
  kind: 'request',
  text: '请在目标会话里继续',
  created_at: 1_700_000_000_000,
  state: 'pending',
  pending: true,
  ...overrides,
});

const renderMenu = async () => {
  const user = userEvent.setup();
  render(
    <ConfigProvider>
      <MemoryRouter>
        <Menu>
          <HostCrossSessionMenuItems conversation={conversation('conv-source', '来源')} />
        </Menu>
      </MemoryRouter>
    </ConfigProvider>
  );
  await user.click(screen.getByTestId('conversation-menu-queue-send'));
  return user;
};

const confirmWithdraw = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByTestId('queue-withdraw-queue-1'));
  const question = await screen.findByText('conversation.hostTools.queueWithdrawConfirm');
  const ok = question.closest('.arco-popconfirm')?.querySelector('.arco-btn-primary');
  expect(ok).not.toBeNull();
  // The popup is still animating in jsdom, so the confirmation is taken with a plain click.
  fireEvent.click(ok as HTMLElement);
};

beforeEach(() => {
  request.mockReset();
  list.conversations = [conversation('conv-source', '来源'), conversation('conv-target', '目标会话')];
});

afterEach(() => {
  cleanup();
});

describe('the pending queue of the cross-session dialog', () => {
  it('lists what the host holds and withdraws a pending message in place', async () => {
    request.mockResolvedValueOnce({ items: [queued()], total: 1, stats: { stored: 1, pending: 1 } });
    const user = await renderMenu();

    expect(request).toHaveBeenCalledWith('GET', '/api/queue');
    const row = await screen.findByTestId('queue-pending-item');
    expect(within(row).getByText('conversation.hostTools.queueStatePending')).toBeInTheDocument();
    expect(within(row).getByText('请在目标会话里继续')).toBeInTheDocument();
    expect(within(row).getByText('conversation.hostTools.queueTarget：目标会话')).toBeInTheDocument();

    request.mockResolvedValueOnce({ cancelled: true, queue_id: 'queue-1', delivered: false });
    await confirmWithdraw(user);

    expect(request).toHaveBeenLastCalledWith('DELETE', '/api/queue/queue-1');
    // In place: the row is the withdrawn one now, and nothing offers a second withdrawal for it.
    expect(await screen.findByText('conversation.hostTools.queueStateWithdrawn')).toBeInTheDocument();
    expect(screen.queryByTestId('queue-withdraw-queue-1')).toBeNull();
    expect(screen.getByText('conversation.hostTools.queueNoWithdraw')).toBeInTheDocument();
  });

  it('offers no withdraw for a message its conversation already took, and says why', async () => {
    request.mockResolvedValueOnce({
      items: [queued({ state: 'withdrawn', pending: false })],
      total: 1,
    });
    await renderMenu();

    const row = await screen.findByTestId('queue-pending-item');
    expect(within(row).getByText('conversation.hostTools.queueStateWithdrawn')).toBeInTheDocument();
    expect(screen.queryByTestId('queue-withdraw-queue-1')).toBeNull();
    expect(screen.getByText('conversation.hostTools.queueNoWithdraw')).toBeInTheDocument();
  });

  it('reports the code the host refused with, and stops offering the withdraw it refused', async () => {
    request.mockResolvedValueOnce({ items: [queued()], total: 1 });
    const user = await renderMenu();
    await screen.findByTestId('queue-pending-item');

    request.mockRejectedValueOnce(
      new BackendHttpError({
        method: 'DELETE',
        path: '/api/queue/queue-1',
        status: 409,
        body: { success: false, code: 'QUEUE_NOT_PENDING', error: '该消息已被目标会话取走' },
      })
    );
    await confirmWithdraw(user);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'settings.hosts.requestFailed(QUEUE_NOT_PENDING|该消息已被目标会话取走)'
    );
    expect(screen.queryByTestId('queue-withdraw-queue-1')).toBeNull();
    expect(screen.getByText('conversation.hostTools.queueStateTaken')).toBeInTheDocument();
    // Nothing is reported as withdrawn: the row is not the withdrawn one.
    expect(screen.queryByText('conversation.hostTools.queueStateWithdrawn')).toBeNull();
  });

  it('claims no withdrawal when the host answer did not cancel anything', async () => {
    request.mockResolvedValueOnce({ items: [queued()], total: 1 });
    const user = await renderMenu();
    await screen.findByTestId('queue-pending-item');

    request.mockResolvedValueOnce({ cancelled: false, queue_id: 'queue-1', delivered: false });
    await confirmWithdraw(user);

    expect(await screen.findByRole('alert')).toHaveTextContent('HOST_QUEUE_NOT_WITHDRAWN');
    expect(screen.getByTestId('queue-withdraw-queue-1')).toBeInTheDocument();
    expect(screen.queryByText('conversation.hostTools.queueStateWithdrawn')).toBeNull();
  });

  it('states a queue it could not read instead of an empty one', async () => {
    request.mockRejectedValueOnce(
      new BackendHttpError({
        method: 'GET',
        path: '/api/queue',
        status: 500,
        body: { success: false, code: 'QUEUE_UNAVAILABLE', error: '队列不可读' },
      })
    );
    await renderMenu();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'settings.hosts.requestFailed(QUEUE_UNAVAILABLE|队列不可读)'
    );
    expect(screen.queryByText('conversation.hostTools.queuePendingEmpty')).toBeNull();
    expect(screen.getByTestId('queue-reload')).toBeInTheDocument();
  });

  it('sends a message and lists what is waiting afterwards', async () => {
    request.mockResolvedValueOnce({ items: [], total: 0 });
    const user = await renderMenu();
    await screen.findByText('conversation.hostTools.queuePendingEmpty');

    request.mockResolvedValueOnce({ queue_id: 'queue-2', state: 'pending', queued: true, delivered: false });
    await user.type(screen.getByLabelText('conversation.hostTools.queueText'), '把结果写进文档');
    request.mockResolvedValueOnce({ items: [queued({ queue_id: 'queue-2', text: '把结果写进文档' })], total: 1 });
    await user.click(screen.getByTestId('queue-send-submit'));

    expect(request).toHaveBeenCalledWith('POST', '/api/queue', {
      conversation_id: 'conv-target',
      host_id: null,
      agent: null,
      session_id: null,
      kind: 'notify',
      text: '把结果写进文档',
    });
    // The list is read again from the host, never guessed at from the send call.
    const row = await screen.findByTestId('queue-pending-item');
    expect(within(row).getByText('把结果写进文档')).toBeInTheDocument();
  });
});
