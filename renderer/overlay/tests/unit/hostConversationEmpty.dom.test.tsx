/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BackendHttpError } from '@/common/adapter/httpBridge';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) =>
      options ? `${key}(${Object.values(options).join('|')})` : key,
  }),
}));

import { HostConversationFailure, isConversationMissing } from '@renderer/host/HostConversationFailure';

/**
 * A fresh install lands on the renderer's built-in entry conversation, which the host's index does not hold.
 * That is the empty state of an untouched phone — not a failure page with nothing but a retry — and the way out
 * of it is the new-conversation entry. Nothing is created on the user's behalf.
 */
const notFound = (conversationId = 'conv-probe-1') =>
  new BackendHttpError({
    method: 'GET',
    path: `/api/conversations/${conversationId}`,
    status: 404,
    body: { success: false, code: 'CONVERSATION_NOT_FOUND', error: '索引中没有这个会话。' },
  });

const refusal = () =>
  new BackendHttpError({
    method: 'GET',
    path: '/api/conversations/conv-probe-1',
    status: 409,
    body: { success: false, code: 'HOST_NOT_CONNECTED', error: '尚未打开任何主机会话。' },
  });

const Route_ = () => {
  const location = useLocation();
  return <div data-testid='open-route'>{location.pathname}</div>;
};

const renderNotFound = (error: unknown, onRetry = vi.fn()) =>
  render(
    <MemoryRouter initialEntries={['/conversation/conv-probe-1']}>
      <Routes>
        <Route path='/conversation/:id' element={<HostConversationFailure error={error} onRetry={onRetry} />} />
        <Route path='/guid' element={<Route_ />} />
      </Routes>
    </MemoryRouter>
  );

afterEach(() => cleanup());

describe('a conversation the host does not hold', () => {
  it('is recognized by its own code, and a refusal is not mistaken for it', () => {
    expect(isConversationMissing(notFound())).toBe(true);
    expect(isConversationMissing(refusal())).toBe(false);
    expect(isConversationMissing(new Error('HOST_TIMEOUT: conversation.read; result unknown'))).toBe(false);
  });

  it('offers the new-conversation entry instead of a failure page, and creates nothing by itself', async () => {
    const onRetry = vi.fn();
    renderNotFound(notFound(), onRetry);

    expect(screen.getByTestId('conversation-empty-state')).toHaveTextContent('conversation.hostTools.emptyTitle');
    expect(screen.getByTestId('conversation-empty-state')).toHaveTextContent('conversation.hostTools.emptyHint');
    // No failure page, and no retry into the same missing conversation.
    expect(screen.queryByTestId('conversation-open-retry')).toBeNull();
    expect(onRetry).not.toHaveBeenCalled();

    await userEvent.setup().click(screen.getByTestId('conversation-empty-new'));
    // The new-conversation page is where this leads; the empty state opens no session on its way there.
    expect(screen.getByTestId('open-route')).toHaveTextContent('/guid');
    expect(onRetry).not.toHaveBeenCalled();
  });

  it('still reports a failure that is not a missing conversation, with its retry', async () => {
    const onRetry = vi.fn();
    renderNotFound(refusal(), onRetry);

    expect(screen.queryByTestId('conversation-empty-state')).toBeNull();
    expect(screen.getByRole('alert')).toHaveTextContent(
      'conversation.hostTools.openFailed(settings.hosts.requestFailed(HOST_NOT_CONNECTED|尚未打开任何主机会话。))'
    );
    await userEvent.setup().click(screen.getByTestId('conversation-open-retry'));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
