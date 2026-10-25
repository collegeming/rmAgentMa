/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from '@arco-design/web-react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BackendHttpError } from '@/common/adapter/httpBridge';
import type { SavedHost } from '@/common/host/hostTransport';

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
vi.mock('@/renderer/hooks/context/LayoutContext', () => ({
  useLayoutContext: () => ({ isMobile: true, siderCollapsed: false, setSiderCollapsed: () => {} }),
}));
vi.mock('@/renderer/hooks/context/ThemeContext', () => ({
  useThemeContext: () => ({ theme: 'light', fontScale: 1 }),
}));

import HostsPage from '@/renderer/pages/hosts';

let answerRefresh: (value: unknown) => void = () => {};

const kimi = (state: string) => ({
  id: 'kimi',
  state,
  executable: '/home/ci/.local/bin/kimi',
  version: '1.2.3',
  capabilities: { terminal: true },
  error: state === 'available' ? '' : 'executable_missing',
});

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
  detection: { checked: true, agents: [kimi('available')] },
  ...overrides,
});

const renderPage = () =>
  render(
    <ConfigProvider>
      <HostsPage />
    </ConfigProvider>
  );

const hostError = (status: number, code: string, reason: string) =>
  new BackendHttpError({
    method: 'POST',
    path: '/api/hosts/host-3/refresh',
    status,
    body: { success: false, code, error: reason },
  });

beforeEach(() => {
  request.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('hosts page', () => {
  it('lists each host with its address, target and the executable, version and verdict of every Agent', async () => {
    request.mockResolvedValueOnce({ items: [host()], total: 1, has_more: false });
    renderPage();

    expect(await screen.findByText('build-box')).toBeInTheDocument();
    expect(screen.getByText('ci@10.0.0.7:22')).toBeInTheDocument();
    expect(screen.getByText('settings.hosts.osLinux')).toBeInTheDocument();
    expect(screen.getByText('settings.hosts.targetNative')).toBeInTheDocument();
    expect(screen.getByText('settings.hosts.probeAvailable')).toBeInTheDocument();
    expect(screen.getByText('settings.hosts.executable(/home/ci/.local/bin/kimi)')).toBeInTheDocument();
    expect(screen.getByText('settings.hosts.version(1.2.3)')).toBeInTheDocument();
    expect(request).toHaveBeenCalledWith('GET', '/api/hosts');
  });

  it('refreshes one host through its own refresh entry and takes the verdicts and the session count it answers', async () => {
    request.mockResolvedValueOnce({ items: [host({ session_count: 2 })] });
    renderPage();
    await screen.findByText('build-box');
    expect(screen.getByText('settings.hosts.sessionCount(2)')).toBeInTheDocument();

    request.mockReturnValueOnce(new Promise((resolve) => (answerRefresh = resolve)));
    await userEvent.setup().click(screen.getByRole('button', { name: 'settings.hosts.refresh' }));

    // The route the contract declares for a refresh — it probes the host *and* rescans its session index.
    expect(request).toHaveBeenLastCalledWith('POST', '/api/hosts/host-3/refresh');
    expect(screen.getAllByText('settings.hosts.refreshing').length).toBeGreaterThan(0);

    answerRefresh({
      host_id: 'host-3',
      refreshed: true,
      sessions: 5,
      detection: { checked: true, agents: [kimi('not_installed')] },
    });
    expect(await screen.findByText('settings.hosts.probeNotInstalled')).toBeInTheDocument();
    expect(screen.queryByText('settings.hosts.probeAvailable')).toBeNull();
    expect(screen.getByText('settings.hosts.executable(/home/ci/.local/bin/kimi)')).toBeInTheDocument();
    // The rescan is the half a probe alone never did: the count the host answered is what the row shows.
    expect(screen.getByText('settings.hosts.sessionCount(5)')).toBeInTheDocument();
  });

  it('keeps the verdicts it already showed when the refresh fails, and states the code and reason', async () => {
    request.mockResolvedValueOnce({ items: [host()] });
    renderPage();
    await screen.findByText('build-box');

    request.mockRejectedValueOnce(hostError(500, 'HOST_REFRESH_FAILED', 'ssh unreachable'));
    await userEvent.setup().click(screen.getByRole('button', { name: 'settings.hosts.refresh' }));

    expect(
      await screen.findByText('settings.hosts.requestFailed(HOST_REFRESH_FAILED|ssh unreachable)')
    ).toBeInTheDocument();
    expect(screen.getByText('settings.hosts.probeAvailable')).toBeInTheDocument();
    expect(screen.getByText('settings.hosts.executable(/home/ci/.local/bin/kimi)')).toBeInTheDocument();
  });

  it('refuses a refresh answer that carries no readable verdicts instead of showing an empty probe', async () => {
    request.mockResolvedValueOnce({ items: [host()] });
    renderPage();
    await screen.findByText('build-box');

    request.mockResolvedValueOnce({ host_id: 'host-3', refreshed: true, sessions: 5 });
    await userEvent.setup().click(screen.getByRole('button', { name: 'settings.hosts.refresh' }));

    expect(await screen.findByText('HOST_REFRESH_INVALID')).toBeInTheDocument();
    expect(screen.getByText('settings.hosts.probeAvailable')).toBeInTheDocument();
  });

  it('saves a Windows host whose Agent runs on Windows itself, and says what that probe relies on', async () => {
    request.mockResolvedValueOnce({ items: [] });
    renderPage();
    const user = userEvent.setup();
    await user.click(screen.getAllByRole('button', { name: 'settings.hosts.add' })[0]);

    await user.type(screen.getByLabelText('settings.hosts.nickname'), 'win-box');
    await user.type(screen.getByLabelText('settings.hosts.address'), '10.0.0.9');
    await user.click(screen.getByText('settings.hosts.osWindows'));
    // The option names the target, and the hint under it states what the probe depends on.
    expect(screen.getByText('settings.hosts.targetWindowsHint')).toBeInTheDocument();

    request.mockResolvedValueOnce(host({ id: 'host-9', nickname: 'win-box', os_type: 'windows' }));
    await user.click(screen.getByRole('button', { name: 'settings.hosts.save' }));

    await waitFor(() =>
      expect(request).toHaveBeenLastCalledWith('POST', '/api/hosts', {
        nickname: 'win-box',
        hostname: '10.0.0.9',
        port: 22,
        username: '',
        os_type: 'windows',
        agent_target: 'windows',
        auth_method: 'key',
      })
    );
  });

  it('requires the WSL distribution name and sends the target the user typed', async () => {
    request.mockResolvedValueOnce({ items: [] });
    renderPage();
    const user = userEvent.setup();
    await user.click(screen.getAllByRole('button', { name: 'settings.hosts.add' })[0]);

    await user.type(screen.getByLabelText('settings.hosts.nickname'), 'wsl-box');
    await user.type(screen.getByLabelText('settings.hosts.address'), '10.0.0.10');
    await user.click(screen.getByText('settings.hosts.osWindows'));
    await user.click(screen.getByText('settings.hosts.targetWsl'));
    await user.click(screen.getByRole('button', { name: 'settings.hosts.save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('settings.hosts.distributionRequired');
    expect(request).toHaveBeenCalledTimes(1);

    await user.type(screen.getByLabelText('settings.hosts.wslDistribution'), 'Ubuntu-22.04');
    request.mockResolvedValueOnce(host({ id: 'host-10', nickname: 'wsl-box' }));
    await user.click(screen.getByRole('button', { name: 'settings.hosts.save' }));

    await waitFor(() =>
      expect(request).toHaveBeenLastCalledWith(
        'POST',
        '/api/hosts',
        expect.objectContaining({ os_type: 'windows', agent_target: 'wsl:Ubuntu-22.04' })
      )
    );
  });

  it('creates a host with the fields of the form and shows the refusal the host returned', async () => {
    request.mockResolvedValueOnce({ items: [] });
    renderPage();
    const user = userEvent.setup();
    await user.click(screen.getAllByRole('button', { name: 'settings.hosts.add' })[0]);

    await user.type(screen.getByLabelText('settings.hosts.nickname'), 'taken-box');
    await user.type(screen.getByLabelText('settings.hosts.address'), '10.0.0.8');
    request.mockRejectedValueOnce(
      new BackendHttpError({
        method: 'POST',
        path: '/api/hosts',
        status: 409,
        body: { success: false, code: 'HOST_NICKNAME_TAKEN', error: 'already taken' },
      })
    );
    await user.click(screen.getByRole('button', { name: 'settings.hosts.save' }));

    expect(
      await screen.findByText('settings.hosts.requestFailed(HOST_NICKNAME_TAKEN|already taken)')
    ).toBeInTheDocument();
    expect(request).toHaveBeenLastCalledWith('POST', '/api/hosts', {
      nickname: 'taken-box',
      hostname: '10.0.0.8',
      port: 22,
      username: '',
      os_type: 'linux',
      agent_target: 'native',
      auth_method: 'key',
    });
  });
});
