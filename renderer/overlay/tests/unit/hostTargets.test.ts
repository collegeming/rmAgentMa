/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { describe, expect, it } from 'vitest';
import type { SavedHost } from '@/common/host/hostTransport';
import {
  draftFromHost,
  draftProblem,
  emptyDraft,
  hostFields,
  targetLabel,
  type HostDraft,
} from '@/renderer/pages/hosts/hostModel';

const draft = (overrides: Partial<HostDraft> = {}): HostDraft => ({
  ...emptyDraft(),
  nickname: 'win-box',
  hostname: '10.0.0.9',
  username: 'ci',
  ...overrides,
});

const host = (overrides: Partial<SavedHost> = {}): SavedHost => ({
  id: 'host-9',
  nickname: 'win-box',
  hostname: '10.0.0.9',
  port: 22,
  username: 'ci',
  protocol: 'ssh',
  os_type: 'windows',
  agent_target: 'windows',
  target: 'windows-native',
  target_supported: true,
  detection: { checked: false, agents: [] },
  ...overrides,
});

describe('the Agent target of a saved host', () => {
  it('saves a Windows host whose Agent runs on Windows itself', () => {
    const windows = draft({ osType: 'windows', target: 'native' });

    expect(draftProblem(windows)).toBeNull();
    expect(hostFields(windows)).toEqual({
      nickname: 'win-box',
      hostname: '10.0.0.9',
      port: 22,
      username: 'ci',
      os_type: 'windows',
      agent_target: 'windows',
    });
  });

  it('saves a WSL target with the distribution the user typed', () => {
    const wsl = draft({ osType: 'windows', target: 'wsl', distribution: 'Ubuntu-22.04' });

    expect(draftProblem(wsl)).toBeNull();
    expect(hostFields(wsl).agent_target).toBe('wsl:Ubuntu-22.04');
  });

  it('still requires a usable distribution name for the WSL target', () => {
    expect(draftProblem(draft({ osType: 'windows', target: 'wsl', distribution: ' Ubuntu 22.04 ' }))).toBeNull();
    expect(draftProblem(draft({ osType: 'windows', target: 'wsl', distribution: 'ubuntu; rm -rf /' }))).toEqual({
      kind: 'distribution',
    });
    expect(draftProblem(draft({ osType: 'windows', target: 'wsl', distribution: '' }))).toEqual({
      kind: 'distribution',
    });
  });

  it('keeps the fields a host needs before any target is written', () => {
    expect(draftProblem(draft({ nickname: ' ' }))).toEqual({ kind: 'nickname' });
    expect(draftProblem(draft({ hostname: '' }))).toEqual({ kind: 'hostname' });
    expect(draftProblem(draft({ port: '70000' }))).toEqual({ kind: 'port' });
  });

  it('reads the Windows target back into the editor and onto the host row', () => {
    expect(draftFromHost(host())).toMatchObject({ osType: 'windows', target: 'native', distribution: '' });
    expect(draftFromHost(host({ agent_target: 'wsl:Ubuntu-22.04' }))).toMatchObject({
      target: 'wsl',
      distribution: 'Ubuntu-22.04',
    });
    expect(targetLabel(host())).toEqual({ key: 'settings.hosts.targetWindowsNative' });
    expect(targetLabel(host({ agent_target: 'wsl:Ubuntu-22.04' }))).toEqual({
      key: 'settings.hosts.wslBadge',
      values: { distribution: 'Ubuntu-22.04' },
    });
    expect(targetLabel(host({ os_type: 'linux', agent_target: 'native' }))).toEqual({
      key: 'settings.hosts.targetNative',
    });
  });
});
