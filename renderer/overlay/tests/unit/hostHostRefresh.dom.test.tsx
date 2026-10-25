/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BackendHttpError } from '@/common/adapter/httpBridge';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/common/adapter/httpBridge', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/common/adapter/httpBridge')>()),
  httpRequest: request,
}));
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) =>
      options ? `${key}(${Object.values(options).join('|')})` : key,
  }),
}));

import HostHistoryFilterBar from '@renderer/host/HostHistoryFilterBar';
import { hostDirectorySnapshot, resetHostDirectory } from '@renderer/host/hostDirectory';
import { resetHostRefresh } from '@renderer/host/hostRefresh';

const agent = (state: string) => ({
  id: 'claude',
  state,
  executable: '/usr/bin/claude',
  version: '1.2.3',
  capabilities: {},
  error: '',
});

const hostRow = (id: string, nickname: string, state: string, sessions: number | null) => ({
  id,
  nickname,
  hostname: `${id}.local`,
  port: 22,
  username: 'me',
  protocol: 'ssh',
  os_type: 'linux',
  agent_target: 'native',
  target: 'posix',
  target_supported: true,
  ...(sessions === null ? {} : { session_count: sessions }),
  detection: { checked: true, agents: [agent(state)] },
  auth_method: 'key',
});

const conversation = (id: string, hostId: string) =>
  ({
    id,
    name: `session ${id}`,
    workspace: { host_id: hostId, cwd: '/home/me/work' },
    agent: 'claude',
  }) as never;

const conversations = [conversation('c1', 'host-1'), conversation('c2', 'host-2')];

/** The sidebar holds both filters itself, so the bar is rendered the way the sidebar renders it. */
const renderBar = () => {
  const onHostChange = vi.fn();
  const Bar = () => {
    const [hostId, setHostId] = React.useState('');
    const [agentId, setAgentId] = React.useState('');
    return (
      <HostHistoryFilterBar
        conversations={conversations}
        hostId={hostId}
        agentId={agentId}
        onHostChange={(value: string) => {
          onHostChange(value);
          setHostId(value);
        }}
        onAgentChange={setAgentId}
      />
    );
  };
  render(<Bar />);
  return { onHostChange };
};

/** Takes one option of the host selector, the way the sidebar does. */
const chooseHost = async (label: string) => {
  // Arco keeps its option list `pointer-events: none` until its own motion frame runs, which jsdom never
  // paints, so the check is off here and the click is delivered as the user would deliver it.
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  // The two selectors are the sidebar's own Arco selects; the host one is the first of the two, and its own
  // list is read through the id that selector points at, so the Agent selector's list is never touched.
  const [hostSelect] = screen.getByTestId('host-history-filters').querySelectorAll('.arco-select');
  await user.click(hostSelect);
  const popup = document.getElementById(hostSelect.getAttribute('aria-controls') ?? '');
  expect(popup).not.toBeNull();
  await user.click(await within(popup as HTMLElement).findByText(label));
};

beforeEach(() => {
  request.mockReset();
  resetHostDirectory();
  resetHostRefresh();
});

afterEach(() => cleanup());

describe('selecting one host in the sidebar', () => {
  it('refreshes that host and draws the verdicts and session count it answered', async () => {
    request.mockImplementation((method: string, path: string) => {
      if (method === 'GET' && path === '/api/hosts') {
        return Promise.resolve({
          items: [hostRow('host-1', 'first', 'not_installed', 3), hostRow('host-2', 'second', 'available', 4)],
        });
      }
      if (method === 'POST' && path === '/api/hosts/host-1/refresh') {
        return Promise.resolve({
          host_id: 'host-1',
          refreshed: true,
          sessions: 7,
          detection: { checked: true, agents: [agent('available')] },
        });
      }
      return Promise.reject(new Error(`unexpected ${method} ${path}`));
    });
    const { onHostChange } = renderBar();
    await waitFor(() => expect(request).toHaveBeenCalledWith('GET', '/api/hosts'));

    await chooseHost('first');
    expect(onHostChange).toHaveBeenCalledWith('host-1');
    await waitFor(() => expect(request).toHaveBeenCalledWith('POST', '/api/hosts/host-1/refresh'));

    await waitFor(() =>
      expect(screen.getByTestId('host-refresh-sessions')).toHaveTextContent('settings.hosts.sessionCount(7)')
    );
    expect(screen.getByTestId('host-refresh-probe-claude')).toHaveTextContent('settings.hosts.probeAvailable');
    // Only the host that was refreshed is rewritten; the other row keeps what the list answered.
    expect(hostDirectorySnapshot().hosts.map((host) => host.session_count)).toEqual([7, 4]);
  });

  it('keeps a host refreshing with the verdicts it had when its probe runs past the renderer’s window', async () => {
    let timingOut = true;
    request.mockImplementation((method: string, path: string) => {
      if (method === 'GET' && path === '/api/hosts') {
        return Promise.resolve({
          items: [hostRow('host-1', 'first', 'available', 3), hostRow('host-2', 'second', 'available', 4)],
        });
      }
      if (method === 'POST' && path === '/api/hosts/host-1/refresh') {
        return timingOut
          ? Promise.reject(new Error('HOST_TIMEOUT: hosts.refresh; result unknown'))
          : Promise.resolve({
              host_id: 'host-1',
              refreshed: true,
              sessions: 6,
              detection: { checked: true, agents: [agent('available')] },
            });
      }
      return Promise.reject(new Error(`unexpected ${method} ${path}`));
    });
    renderBar();
    await waitFor(() => expect(request).toHaveBeenCalledWith('GET', '/api/hosts'));

    await chooseHost('first');
    const notice = await screen.findByTestId('host-refresh-failure');
    expect(notice).toHaveTextContent('settings.hosts.refreshUnknown');
    // The rendering state is reused: the probe may still be running on the host, so the row says so and the
    // verdict it already held is not drawn as unprobed.
    expect(screen.getByTestId('host-refresh-state')).toHaveAttribute('data-refresh-state', 'refreshing');
    expect(screen.getByTestId('host-refresh-probe-claude')).toHaveTextContent('settings.hosts.refreshing');
    expect(screen.getByTestId('host-refresh-probe-claude')).toHaveAttribute('data-probe-state', 'available');
    expect(hostDirectorySnapshot().hosts[0]?.detection?.agents?.[0]?.state).toBe('available');

    // The retry beside that notice asks again, and the answer it gets is what the row takes.
    timingOut = false;
    await userEvent.setup({ pointerEventsCheck: 0 }).click(screen.getByTestId('host-refresh-retry'));
    await waitFor(() =>
      expect(screen.getByTestId('host-refresh-sessions')).toHaveTextContent('settings.hosts.sessionCount(6)')
    );
    expect(screen.queryByTestId('host-refresh-failure')).toBeNull();
  });

  it('keeps the verdicts it already showed when the refresh fails, and offers the retry', async () => {
    let fail = true;
    request.mockImplementation((method: string, path: string) => {
      if (method === 'GET' && path === '/api/hosts') {
        return Promise.resolve({
          items: [hostRow('host-1', 'first', 'available', 3), hostRow('host-2', 'second', 'available', 4)],
        });
      }
      if (method === 'POST' && path === '/api/hosts/host-1/refresh') {
        return fail
          ? Promise.reject(
              new BackendHttpError({
                method: 'POST',
                path,
                status: 500,
                body: { success: false, code: 'HOST_REFRESH_FAILED', error: '主机未能探测。' },
              })
            )
          : Promise.resolve({
              host_id: 'host-1',
              refreshed: true,
              sessions: 9,
              detection: { checked: true, agents: [agent('available')] },
            });
      }
      return Promise.reject(new Error(`unexpected ${method} ${path}`));
    });
    renderBar();
    await waitFor(() => expect(request).toHaveBeenCalledWith('GET', '/api/hosts'));

    await chooseHost('first');
    const failure = await screen.findByTestId('host-refresh-failure');
    expect(failure).toHaveTextContent('settings.hosts.requestFailed(HOST_REFRESH_FAILED|主机未能探测。)');
    // The probe verdict the row already held is still drawn.
    expect(screen.getByTestId('host-refresh-probe-claude')).toHaveTextContent('settings.hosts.probeAvailable');
    expect(hostDirectorySnapshot().hosts[0]?.detection?.agents?.[0]?.state).toBe('available');

    fail = false;
    await userEvent.setup({ pointerEventsCheck: 0 }).click(screen.getByTestId('host-refresh-retry'));
    await waitFor(() =>
      expect(screen.getByTestId('host-refresh-sessions')).toHaveTextContent('settings.hosts.sessionCount(9)')
    );
  });

  it('does not ask anything of a host when the filter is put back to all', async () => {
    request.mockImplementation((method: string, path: string) => {
      if (method === 'GET' && path === '/api/hosts') {
        return Promise.resolve({
          items: [hostRow('host-1', 'first', 'available', 3), hostRow('host-2', 'second', 'available', 4)],
        });
      }
      return Promise.reject(new Error(`unexpected ${method} ${path}`));
    });
    renderBar();
    await waitFor(() => expect(request).toHaveBeenCalledWith('GET', '/api/hosts'));

    await chooseHost('first');
    // The refresh of host-1 is refused by this mock, which is not what this case is about.
    await waitFor(() => expect(request).toHaveBeenCalledWith('POST', '/api/hosts/host-1/refresh'));
    await chooseHost('conversation.hostTools.filterAll');
    expect(request.mock.calls.filter(([, path]) => String(path).includes('/refresh')).length).toBe(1);
  });
});
