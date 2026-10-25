/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from '@arco-design/web-react';
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
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t, i18n: { language: 'en' } }) }));

import { EscapeHatchForm } from '@/renderer/host/EscapeHatch';

const renderForm = (target: { conversationId: string } | { hostId: string }, cwd = '/home/ci/project') =>
  render(
    <ConfigProvider>
      <EscapeHatchForm target={target} defaultCwd={cwd} />
    </ConfigProvider>
  );

const terminalError = (code: string, reason: string) =>
  new BackendHttpError({
    method: 'POST',
    path: '/api/hosts/host-3/terminal',
    status: 502,
    body: { success: false, code, error: reason },
  });

beforeEach(() => {
  request.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('escape hatch', () => {
  it('opens an SSH terminal for the conversation with the directory it was given', async () => {
    request.mockResolvedValueOnce({ opened: true });
    renderForm({ conversationId: 'conv-7' }, '/srv/app');

    await userEvent.setup().click(screen.getByRole('button', { name: 'settings.escape.openTerminal' }));

    expect(request).toHaveBeenLastCalledWith('POST', '/api/conversations/conv-7/terminal', { cwd: '/srv/app' });
    expect(screen.getByRole('status')).toHaveTextContent('settings.escape.opened(settings.escape.openTerminal)');
  });

  it('starts the TUI command on the same target through its own action', async () => {
    request.mockResolvedValueOnce({ opened: true });
    renderForm({ hostId: 'host-3' }, 'C:\\Users\\ci\\project');

    await userEvent.setup().click(screen.getByRole('button', { name: 'settings.escape.startTui' }));

    expect(request).toHaveBeenLastCalledWith('POST', '/api/hosts/host-3/terminal/command', {
      cwd: 'C:\\Users\\ci\\project',
    });
  });

  it('refuses a relative directory itself and asks the host nothing', async () => {
    renderForm({ hostId: 'host-3' }, 'project');

    await userEvent.setup().click(screen.getByRole('button', { name: 'settings.escape.openTerminal' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('settings.escape.cwdNotAbsolute');
    expect(request).not.toHaveBeenCalled();
  });

  it('refuses an empty directory itself and asks the host nothing', async () => {
    renderForm({ hostId: 'host-3' }, '');

    await userEvent.setup().click(screen.getByRole('button', { name: 'settings.escape.startTui' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('settings.escape.cwdRequired');
    expect(request).not.toHaveBeenCalled();
  });

  it('states the code and reason the host returned instead of reporting an opening', async () => {
    request.mockRejectedValueOnce(terminalError('TERMINAL_BUSY', 'all sessions in use'));
    renderForm({ hostId: 'host-3' });

    await userEvent.setup().click(screen.getByRole('button', { name: 'settings.escape.openTerminal' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'settings.hosts.requestFailed(TERMINAL_BUSY|all sessions in use)'
    );
    expect(screen.queryByRole('status')).toBeNull();
  });
});
