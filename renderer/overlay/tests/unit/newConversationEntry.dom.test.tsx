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
import type { SavedHost } from '@/common/host/hostTransport';

const { request, navigate, t } = vi.hoisted(() => ({
  request: vi.fn(),
  navigate: vi.fn(),
  t: (key: string, options?: Record<string, unknown>) =>
    options ? `${key}(${Object.values(options).join('|')})` : key,
}));

vi.mock('@/common/adapter/httpBridge', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/common/adapter/httpBridge')>()),
  httpRequest: request,
}));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t, i18n: { language: 'en' } }) }));
vi.mock('react-router-dom', () => ({ useNavigate: () => navigate }));

import SiderNewConversationEntry from '@/renderer/components/layout/Sider/SiderNav/SiderNewConversationEntry';

const host = (overrides: Partial<SavedHost> = {}): SavedHost => ({
  id: 'host-3',
  nickname: 'build-box',
  hostname: '10.0.0.7',
  port: 22,
  username: 'ci',
  protocol: 'ssh',
  os_type: 'linux',
  agent_target: 'native',
  target: 'posix',
  target_supported: true,
  detection: {
    checked: true,
    agents: [
      {
        id: 'kimi',
        state: 'available',
        executable: '/home/ci/.local/bin/kimi',
        version: '1.2.3',
        capabilities: null,
        error: '',
      },
      { id: 'dsh', state: 'not_installed', executable: '', version: '', capabilities: null, error: 'missing' },
    ],
  },
  ...overrides,
});

const openDialog = async () => {
  render(
    <ConfigProvider>
      <SiderNewConversationEntry isMobile collapsed={false} siderTooltipProps={{ disabled: true }} />
    </ConfigProvider>
  );
  await userEvent.setup().click(screen.getByText('settings.newConversation.title'));
  return screen.findByTestId('new-conversation');
};

beforeEach(() => {
  request.mockReset();
  navigate.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('new conversation entry', () => {
  it('creates host first with a probed Agent and the typed directory, then opens it', async () => {
    request.mockResolvedValueOnce({ items: [host()] });
    await openDialog();
    request.mockResolvedValueOnce({ id: 'conv-9' });

    await userEvent.setup().type(screen.getByLabelText('settings.escape.cwd'), '/srv/app');
    await userEvent.setup().click(screen.getByRole('button', { name: 'settings.newConversation.create' }));

    expect(request).toHaveBeenLastCalledWith('POST', '/api/conversations', {
      host_id: 'host-3',
      agent: 'kimi',
      cwd: '/srv/app',
    });
    expect(navigate).toHaveBeenLastCalledWith('/conversation/conv-9');
  });

  it('offers no Agent when the host probe found none runnable, and creates nothing', async () => {
    request.mockResolvedValueOnce({
      items: [host({ detection: { checked: true, agents: [host().detection.agents[1]] } })],
    });
    await openDialog();
    request.mockResolvedValueOnce({ id: 'conv-9' });

    expect(screen.getAllByText('settings.newConversation.agentNone').length).toBeGreaterThan(0);
    await userEvent.setup().type(screen.getByLabelText('settings.escape.cwd'), '/srv/app');
    await userEvent.setup().click(screen.getByRole('button', { name: 'settings.newConversation.create' }));

    expect(request).toHaveBeenCalledTimes(1);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('refuses a relative directory itself and creates nothing', async () => {
    request.mockResolvedValueOnce({ items: [host()] });
    await openDialog();

    await userEvent.setup().type(screen.getByLabelText('settings.escape.cwd'), 'srv/app');
    await userEvent.setup().click(screen.getByRole('button', { name: 'settings.newConversation.create' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('settings.escape.cwdNotAbsolute');
    expect(request).toHaveBeenCalledTimes(1);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('states the code and reason the host returned instead of opening a conversation', async () => {
    request.mockResolvedValueOnce({ items: [host()] });
    await openDialog();
    request.mockRejectedValueOnce(
      new BackendHttpError({
        method: 'POST',
        path: '/api/conversations',
        status: 409,
        body: { success: false, code: 'HOST_OFFLINE', error: 'ssh handshake failed' },
      })
    );

    await userEvent.setup().type(screen.getByLabelText('settings.escape.cwd'), '/srv/app');
    await userEvent.setup().click(screen.getByRole('button', { name: 'settings.newConversation.create' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'settings.hosts.requestFailed(HOST_OFFLINE|ssh handshake failed)'
    );
    expect(navigate).not.toHaveBeenCalled();
  });
});
