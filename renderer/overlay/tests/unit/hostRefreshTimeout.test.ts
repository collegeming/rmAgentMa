/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { TFunction } from 'i18next';
import { BackendHttpError } from '@/common/adapter/httpBridge';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/common/adapter/httpBridge', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/common/adapter/httpBridge')>()),
  httpRequest: request,
}));

import { hostDirectorySnapshot, loadHostDirectory, resetHostDirectory } from '@renderer/host/hostDirectory';
import { hostRefreshReadingOf, refreshHostRow, resetHostRefresh } from '@renderer/host/hostRefresh';

/**
 * What one host's own probe does when it runs past the renderer's window: the host may still be probing — it
 * runs its actions one after another — so the host stays in its refreshing state, the verdicts the row already
 * held stay drawn, and the result is stated as unknown with the retry beside it. A refusal is the other case,
 * and it ends the refresh as the host's own answer.
 */
const t = ((key: string, options?: Record<string, unknown>) =>
  options ? `${key}(${Object.values(options).join('|')})` : key) as unknown as TFunction;

const agent = (state: string) => ({
  id: 'claude',
  state,
  executable: '/usr/bin/claude',
  version: '1.2.3',
  capabilities: {},
  error: '',
});

const hostRow = {
  id: 'host-1',
  nickname: 'first',
  hostname: '10.0.0.5',
  port: 22,
  username: 'me',
  protocol: 'ssh',
  os_type: 'linux',
  agent_target: 'native',
  target: 'posix',
  target_supported: true,
  session_count: 3,
  detection: { checked: true, agents: [agent('available')] },
};

beforeEach(() => {
  request.mockReset();
  resetHostDirectory();
  resetHostRefresh();
});

describe('a host probe that ran past its window', () => {
  it('stays refreshing with the verdicts it had, and takes the answer of the retry', async () => {
    request.mockResolvedValueOnce({ items: [hostRow] });
    await loadHostDirectory(t);
    expect(hostDirectorySnapshot().hosts[0]?.detection?.agents?.[0]?.state).toBe('available');

    request.mockRejectedValueOnce(new Error('HOST_TIMEOUT: hosts.refresh; result unknown'));
    await refreshHostRow('host-1', t);

    expect(hostRefreshReadingOf('host-1')).toEqual({
      state: 'refreshing',
      sessions: null,
      failure: 'settings.hosts.refreshUnknown',
    });
    // The probe verdict the row already held is still what the app holds: nothing was replaced with an empty
    // or unprobed reading, and the session count it had is not invented either.
    expect(hostDirectorySnapshot().hosts[0]?.detection?.agents?.[0]?.state).toBe('available');
    expect(hostDirectorySnapshot().hosts[0]?.session_count).toBe(3);

    request.mockResolvedValueOnce({
      host_id: 'host-1',
      refreshed: true,
      sessions: 6,
      detection: { checked: true, agents: [agent('not_installed')] },
    });
    await refreshHostRow('host-1', t);

    expect(hostRefreshReadingOf('host-1')).toEqual({ state: 'idle', sessions: 6, failure: null });
    expect(hostDirectorySnapshot().hosts[0]?.session_count).toBe(6);
    expect(hostDirectorySnapshot().hosts[0]?.detection?.agents?.[0]?.state).toBe('not_installed');
    expect(request).toHaveBeenLastCalledWith('POST', '/api/hosts/host-1/refresh');
  });

  it('ends a refused refresh as that refusal, with the code and reason the host gave', async () => {
    request.mockResolvedValueOnce({ items: [hostRow] });
    await loadHostDirectory(t);

    request.mockRejectedValueOnce(
      new BackendHttpError({
        method: 'POST',
        path: '/api/hosts/host-1/refresh',
        status: 500,
        body: { success: false, code: 'HOST_REFRESH_FAILED', error: '主机未能探测。' },
      })
    );
    await refreshHostRow('host-1', t);

    expect(hostRefreshReadingOf('host-1')).toEqual({
      state: 'failed',
      sessions: null,
      failure: 'settings.hosts.requestFailed(HOST_REFRESH_FAILED|主机未能探测。)',
    });
    expect(hostDirectorySnapshot().hosts[0]?.detection?.agents?.[0]?.state).toBe('available');
  });
});
