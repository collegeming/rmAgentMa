/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from '@arco-design/web-react';
// Arco's imperative toasts render through the root this adapter installs under React 19, as main.tsx does.
import '@arco-design/web-react/lib/_util/react-19-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BackendHttpError } from '@/common/adapter/httpBridge';

const { request, t } = vi.hoisted(() => ({
  request: vi.fn(),
  t: (key: string, options?: Record<string, unknown>) =>
    options ? `${key}(${Object.values(options).join('|')})` : key,
}));

vi.mock('@/common/adapter/httpBridge', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/common/adapter/httpBridge')>()),
  httpRequest: request,
}));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t, i18n: { language: 'zh-CN' } }) }));
vi.mock('@renderer/pages/settings/components/SettingsPageWrapper', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import HostMaintenanceSettings, { parseHistoryClearAnswer } from '@renderer/pages/settings/HostMaintenanceSettings';

const renderPage = () =>
  render(
    <ConfigProvider>
      <HostMaintenanceSettings />
    </ConfigProvider>
  );

beforeEach(() => request.mockReset());
afterEach(() => cleanup());

describe('clearing the conversation history cache', () => {
  it('is its own action, asked for behind its own confirmation, and reports the count the host named', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('clear-history-cache'));
    // Nothing is asked of the host before the user confirms, and nothing touches the session index.
    expect(request).not.toHaveBeenCalled();
    expect(screen.getByText('settings.hostMaintenance.clearHistoryConfirm')).toBeInTheDocument();

    request.mockResolvedValueOnce({ cleared: true, conversations: 12 });
    await user.click(screen.getByTestId('clear-history-confirm'));

    expect(request).toHaveBeenLastCalledWith('POST', '/api/history/clear', {});
    expect(await screen.findByTestId('clear-history-done')).toHaveTextContent(
      'settings.hostMaintenance.clearHistoryDone(12)'
    );
  });

  it('never reports a clear the host refused, and keeps its code and reason', async () => {
    const user = userEvent.setup();
    renderPage();
    request.mockRejectedValueOnce(
      new BackendHttpError({
        method: 'POST',
        path: '/api/history/clear',
        status: 500,
        body: { success: false, code: 'HISTORY_CLEAR_FAILED', error: '历史缓存未能清空。' },
      })
    );
    await user.click(screen.getByTestId('clear-history-cache'));
    await user.click(screen.getByTestId('clear-history-confirm'));

    const failure = await screen.findByTestId('clear-history-failure');
    expect(failure).toHaveTextContent('settings.hosts.requestFailed(HISTORY_CLEAR_FAILED|历史缓存未能清空。)');
    expect(screen.queryByTestId('clear-history-done')).not.toBeInTheDocument();
  });

  it('refuses an answer that does not state the count, instead of reporting a clear it cannot read', () => {
    expect(() => parseHistoryClearAnswer({ cleared: true })).toThrow('HOST_HISTORY_CLEAR_INVALID');
    expect(() => parseHistoryClearAnswer({ conversations: 3 })).toThrow('HOST_HISTORY_CLEAR_INVALID');
    expect(parseHistoryClearAnswer({ cleared: true, conversations: 0 })).toEqual({ conversations: 0 });
  });

  it('keeps clearing the session index a separate button with its own route', async () => {
    const user = userEvent.setup();
    renderPage();
    request.mockResolvedValueOnce({ cleared: true, sessions_before: 4, sessions: 0 });
    await user.click(screen.getByTestId('clear-index-cache'));
    await user.click(screen.getByTestId('clear-index-confirm'));
    expect(request).toHaveBeenLastCalledWith('POST', '/api/index/clear', {});
    expect(await screen.findByTestId('clear-index-done')).toHaveTextContent(
      'settings.hostMaintenance.clearIndexDone(4|0)'
    );
    expect(request.mock.calls.some(([, path]) => path === '/api/history/clear')).toBe(false);
  });
});
