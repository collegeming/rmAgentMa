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
// The settings chrome is not what this page decides: only its own action and its own reporting are.
vi.mock('@renderer/pages/settings/components/SettingsPageWrapper', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import HostMaintenanceSettings, { parseIndexClearAnswer } from '@renderer/pages/settings/HostMaintenanceSettings';

const renderPage = () =>
  render(
    <ConfigProvider>
      <HostMaintenanceSettings />
    </ConfigProvider>
  );

/** The confirmation the page asks for: the host is only asked after its own button is taken. */
const confirmClear = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByTestId('clear-index-cache'));
  await user.click(await screen.findByTestId('clear-index-confirm'));
};

const clearError = (code: string, reason: string) =>
  new BackendHttpError({
    method: 'POST',
    path: '/api/index/clear',
    status: 500,
    body: { success: false, code, error: reason },
  });

beforeEach(() => {
  request.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('clearing the session index cache', () => {
  it('asks for the confirmation first and reports the counts the host named', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('clear-index-cache'));
    // The confirmation is the user's step: nothing has been asked of the host yet.
    expect(request).not.toHaveBeenCalled();
    expect(screen.getByText('settings.hostMaintenance.clearIndexConfirm')).toBeInTheDocument();
    request.mockResolvedValueOnce({ cleared: true, sessions_before: 42, sessions: 0 });
    await user.click(screen.getByTestId('clear-index-confirm'));

    expect(request).toHaveBeenLastCalledWith('POST', '/api/index/clear', {});
    expect(await screen.findByTestId('clear-index-done')).toHaveTextContent(
      'settings.hostMaintenance.clearIndexDone(42|0)'
    );
  });

  it('never reports a clear the host refused, and keeps its code and reason', async () => {
    const user = userEvent.setup();
    renderPage();
    request.mockRejectedValueOnce(clearError('INDEX_CLEAR_FAILED', '索引文件被占用'));
    await confirmClear(user);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'settings.hosts.requestFailed(INDEX_CLEAR_FAILED|索引文件被占用)'
    );
    expect(screen.queryByTestId('clear-index-done')).toBeNull();
  });

  it('reads an answer without the counts as a failure instead of a cleared index of zero', async () => {
    const user = userEvent.setup();
    renderPage();
    request.mockResolvedValueOnce({ cleared: true, sessions: 0 });
    await confirmClear(user);

    expect(await screen.findByRole('alert')).toHaveTextContent('HOST_INDEX_CLEAR_INVALID');
    expect(screen.queryByTestId('clear-index-done')).toBeNull();
  });

  it('accepts only an answer that says it cleared and names both counts', () => {
    expect(parseIndexClearAnswer({ cleared: true, sessions_before: 3, sessions: 3 })).toEqual({
      sessionsBefore: 3,
      sessions: 3,
    });
    expect(() => parseIndexClearAnswer({ cleared: false, sessions_before: 3, sessions: 0 })).toThrow(
      'HOST_INDEX_CLEAR_INVALID'
    );
    expect(() => parseIndexClearAnswer(null)).toThrow('HOST_INDEX_CLEAR_INVALID');
    expect(() => parseIndexClearAnswer({ cleared: true, sessions_before: '3', sessions: 0 })).toThrow(
      'HOST_INDEX_CLEAR_INVALID'
    );
  });
});
