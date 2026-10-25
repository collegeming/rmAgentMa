/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import {
  AGENT_TARGET_NATIVE,
  AGENT_TARGET_WINDOWS,
  AGENT_TARGET_WSL_PREFIX,
  HOST_AUTH_KEY,
  HOST_AUTH_PASSWORD,
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

/** The shell context one host runs its Agent in: the host's own, one named WSL distribution, or Windows. */
export function targetLabel(host: SavedHost): { key: string; values?: Record<string, string> } {
  if (host.os_type === HOST_OS_WINDOWS && host.agent_target.startsWith(AGENT_TARGET_WSL_PREFIX)) {
    return {
      key: 'settings.hosts.wslBadge',
      values: { distribution: host.agent_target.slice(AGENT_TARGET_WSL_PREFIX.length) },
    };
  }
  if (host.os_type === HOST_OS_WINDOWS) return { key: 'settings.hosts.targetWindowsNative' };
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
  /** How this host logs in: a key the product holds, or a password the user types here. */
  authMethod: string;
  /** The password being typed; it is never read back and never stored by this page. */
  password: string;
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
    authMethod: HOST_AUTH_KEY,
    password: '',
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
    // The method the row states, or a key when it states none this build knows: an existing password is never
    // read back, so an unstated method is not guessed to be one.
    authMethod: host.auth_method === HOST_AUTH_PASSWORD ? HOST_AUTH_PASSWORD : HOST_AUTH_KEY,
    password: '',
  };
}

/** How this host logs in, as the row states it; an unknown value is shown as the value it carries. */
export function authMethodLabel(host: SavedHost, t: TFunction): string {
  if (host.auth_method === HOST_AUTH_PASSWORD) return t('settings.hosts.authPassword');
  if (host.auth_method === HOST_AUTH_KEY) {
    const key = host.key_name || host.key_id || '';
    return key ? t('settings.hosts.authKeyNamed', { key }) : t('settings.hosts.authKeyDefault');
  }
  return host.auth_method ? String(host.auth_method) : t('settings.hosts.valueNone');
}

/** What stops this draft from being saved; the message each case reads is DRAFT_PROBLEM_KEYS. */
export type DraftProblem = { kind: 'nickname' | 'hostname' | 'port' | 'distribution' | 'password' };

export const DRAFT_PROBLEM_KEYS: Record<DraftProblem['kind'], string> = {
  nickname: 'settings.hosts.nicknameRequired',
  hostname: 'settings.hosts.addressRequired',
  port: 'settings.hosts.portInvalid',
  distribution: 'settings.hosts.distributionRequired',
  password: 'settings.hosts.passwordRequired',
};

/**
 * The one problem that blocks a save, or null. A Windows host may run its Agent on Windows itself or in one
 * WSL distribution; a distribution is required for the WSL target, because the host quotes it into the
 * Windows shell and its shell context is what the Agent is launched in. The password method needs a password
 * the first time it is chosen: an empty field is not a password, and sending one would write nothing.
 */
export function draftProblem(draft: HostDraft, editing: boolean): DraftProblem | null {
  if (!draft.nickname.trim()) return { kind: 'nickname' };
  if (!draft.hostname.trim()) return { kind: 'hostname' };
  if (!validPort(draft.port)) return { kind: 'port' };
  if (draft.osType === HOST_OS_WINDOWS && draft.target === 'wsl' && !validWslDistribution(draft.distribution.trim()))
    return { kind: 'distribution' };
  if (draft.authMethod === HOST_AUTH_PASSWORD && !draft.password && !editing) return { kind: 'password' };
  return null;
}

/**
 * The request body of one create or update: the OS, the Agent target that OS can run an Agent in, and how the
 * host logs in. A password travels only when the user typed one — an empty field means "leave the stored
 * password alone" on an update, and is refused by `draftProblem` on a create.
 */
export function hostFields(draft: HostDraft): HostFields {
  const osType = draft.osType === HOST_OS_WINDOWS ? HOST_OS_WINDOWS : HOST_OS_LINUX;
  const agentTarget =
    osType !== HOST_OS_WINDOWS
      ? AGENT_TARGET_NATIVE
      : draft.target === 'wsl'
        ? AGENT_TARGET_WSL_PREFIX + draft.distribution.trim()
        : AGENT_TARGET_WINDOWS;
  return {
    nickname: draft.nickname.trim(),
    hostname: draft.hostname.trim(),
    port: Number(draft.port.trim()),
    username: draft.username.trim(),
    os_type: osType,
    agent_target: agentTarget,
    auth_method: draft.authMethod === HOST_AUTH_PASSWORD ? HOST_AUTH_PASSWORD : HOST_AUTH_KEY,
    ...(draft.authMethod === HOST_AUTH_PASSWORD && draft.password ? { password: draft.password } : {}),
  };
}

const MAX_WSL_DISTRIBUTION_LENGTH = 64;
const MAX_PORT = 65535;
const DEFAULT_SSH_PORT = 22;
