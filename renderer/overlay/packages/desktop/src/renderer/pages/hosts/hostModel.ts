/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import {
  AGENT_TARGET_NATIVE,
  AGENT_TARGET_WSL_PREFIX,
  HOST_OS_LINUX,
  HOST_OS_WINDOWS,
  HOST_PROBE_STATES,
  type HostAgentProbe,
  type HostFields,
  type SavedHost,
} from '@/common/host/hostTransport';
import type { TFunction } from 'i18next';

/** The verdicts the host derives, plus the renderer's own in-flight and not-probed readings. */
export type HostProbeReading = (typeof HOST_PROBE_STATES)[number] | 'refreshing' | 'unchecked';

const PROBE_KEYS: Record<HostProbeReading, string> = {
  available: 'settings.hosts.probeAvailable',
  not_installed: 'settings.hosts.probeNotInstalled',
  start_failed: 'settings.hosts.probeStartFailed',
  no_protocol_capability: 'settings.hosts.probeNoProtocolCapability',
  connect_failed: 'settings.hosts.probeConnectFailed',
  target_unsupported: 'settings.hosts.probeTargetUnsupported',
  unknown_failure: 'settings.hosts.probeUnknownFailure',
  refreshing: 'settings.hosts.refreshing',
  unchecked: 'settings.hosts.probeUnchecked',
};

/** One Agent's reading on one host; a verdict this build does not know reads as a failed probe. */
export function probeReading(probe: HostAgentProbe | undefined, refreshing: boolean): HostProbeReading {
  if (refreshing) return 'refreshing';
  if (!probe) return 'unchecked';
  return (HOST_PROBE_STATES as readonly string[]).includes(probe.state)
    ? (probe.state as HostProbeReading)
    : 'unknown_failure';
}

export const probeLabelKey = (reading: HostProbeReading): string => PROBE_KEYS[reading];

/** The OS a host holds, read as the value it carries when this build does not know it. */
export function osText(osType: string, t: TFunction): string {
  if (osType === HOST_OS_WINDOWS) return t('settings.hosts.osWindows');
  if (osType === HOST_OS_LINUX) return t('settings.hosts.osLinux');
  return osType;
}

/** The shell context one host runs its Agent in: the host's own, or one named WSL distribution. */
export function targetLabel(host: SavedHost): { key: string; values?: Record<string, string> } {
  if (host.os_type === HOST_OS_WINDOWS && host.agent_target.startsWith(AGENT_TARGET_WSL_PREFIX)) {
    return {
      key: 'settings.hosts.wslBadge',
      values: { distribution: host.agent_target.slice(AGENT_TARGET_WSL_PREFIX.length) },
    };
  }
  if (host.os_type === HOST_OS_WINDOWS) return { key: 'settings.hosts.targetNativeUnsupported' };
  return { key: 'settings.hosts.targetNative' };
}

/** How many sessions this host's index holds, read as the count it named and never as an invented one. */
export function sessionCountText(host: SavedHost, t: TFunction): string {
  const count = host.session_count;
  return typeof count === 'number' && Number.isFinite(count) && count >= 0
    ? t('settings.hosts.sessionCount', { total: count })
    : t('settings.hosts.valueNone');
}

/**
 * One Agent's capabilities as chip labels. A capability map keeps only the entries the probe answered yes to
 * (a false reading is not a capability), an array is read as the list itself, and anything unreadable — or
 * empty — is no capability at all: the caller shows a dash rather than a placeholder.
 */
export function capabilityLabels(capabilities: HostAgentProbe['capabilities']): string[] {
  if (Array.isArray(capabilities)) {
    return capabilities.filter((entry): entry is string => typeof entry === 'string' && Boolean(entry.trim()));
  }
  if (!capabilities || typeof capabilities !== 'object') return [];
  return Object.entries(capabilities)
    .filter(
      ([, value]) => value === true || (typeof value === 'string' && Boolean(value.trim())) || typeof value === 'number'
    )
    .map(([key, value]) => (value === true ? key : `${key}: ${String(value)}`));
}

/**
 * A distribution name must be safe to quote into the Windows shell, so only the characters WSL itself reports
 * are accepted — the same rule the host applies before it stores one.
 */
export function validWslDistribution(distribution: string): boolean {
  return /^[A-Za-z0-9][A-Za-z0-9._ -]*$/.test(distribution) && distribution.length <= MAX_WSL_DISTRIBUTION_LENGTH;
}

export function validPort(port: string): boolean {
  const value = port.trim();
  return /^\d+$/.test(value) && Number(value) >= 1 && Number(value) <= MAX_PORT;
}

/** One host being written in the editor. */
export type HostDraft = {
  nickname: string;
  hostname: string;
  port: string;
  username: string;
  osType: string;
  target: 'native' | 'wsl';
  distribution: string;
};

export function emptyDraft(): HostDraft {
  return {
    nickname: '',
    hostname: '',
    port: String(DEFAULT_SSH_PORT),
    username: '',
    osType: HOST_OS_LINUX,
    target: 'native',
    distribution: '',
  };
}

export function draftFromHost(host: SavedHost): HostDraft {
  const wsl = host.os_type === HOST_OS_WINDOWS && host.agent_target.startsWith(AGENT_TARGET_WSL_PREFIX);
  return {
    nickname: host.nickname,
    hostname: host.hostname,
    port: String(host.port),
    username: host.username,
    osType: host.os_type,
    target: wsl ? 'wsl' : 'native',
    distribution: wsl ? host.agent_target.slice(AGENT_TARGET_WSL_PREFIX.length) : '',
  };
}

/** What stops this draft from being saved; the message each case reads is DRAFT_PROBLEM_KEYS. */
export type DraftProblem = { kind: 'nickname' | 'hostname' | 'port' | 'distribution' | 'unsupportedTarget' };

export const DRAFT_PROBLEM_KEYS: Record<DraftProblem['kind'], string> = {
  nickname: 'settings.hosts.nicknameRequired',
  hostname: 'settings.hosts.addressRequired',
  port: 'settings.hosts.portInvalid',
  distribution: 'settings.hosts.distributionRequired',
  unsupportedTarget: 'settings.hosts.targetNativeHint',
};

/**
 * The one problem that blocks a save, or null. A Windows host whose Agent would run on Windows itself is
 * refused here: this app has no entry for that target, so the combination is never saved.
 */
export function draftProblem(draft: HostDraft): DraftProblem | null {
  if (!draft.nickname.trim()) return { kind: 'nickname' };
  if (!draft.hostname.trim()) return { kind: 'hostname' };
  if (!validPort(draft.port)) return { kind: 'port' };
  if (draft.osType === HOST_OS_WINDOWS) {
    if (draft.target === 'native') return { kind: 'unsupportedTarget' };
    if (!validWslDistribution(draft.distribution.trim())) return { kind: 'distribution' };
  }
  return null;
}

/** The request body of one create or update; a Linux host keeps the only target it can run an Agent in. */
export function hostFields(draft: HostDraft): HostFields {
  const osType = draft.osType === HOST_OS_WINDOWS ? HOST_OS_WINDOWS : HOST_OS_LINUX;
  const agentTarget =
    osType === HOST_OS_WINDOWS && draft.target === 'wsl'
      ? AGENT_TARGET_WSL_PREFIX + draft.distribution.trim()
      : AGENT_TARGET_NATIVE;
  return {
    nickname: draft.nickname.trim(),
    hostname: draft.hostname.trim(),
    port: Number(draft.port.trim()),
    username: draft.username.trim(),
    os_type: osType,
    agent_target: agentTarget,
  };
}

const MAX_WSL_DISTRIBUTION_LENGTH = 64;
const MAX_PORT = 65535;
const DEFAULT_SSH_PORT = 22;
