/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { httpRequest, isBackendHttpError } from '@/common/adapter/httpBridge';
import { isHostTimeout } from '@/common/host/hostTransport';
import type { HostDeletion, HostFields, HostRefresh, SavedHost } from '@/common/host/hostTransport';
import type { TFunction } from 'i18next';

const HOSTS_PATH = '/api/hosts';
const hostPath = (hostId: string): string => `${HOSTS_PATH}/${encodeURIComponent(hostId)}`;

/** One unreadable answer is reported as itself, never drawn as a host list without the hosts it holds. */
function requireHostList(payload: { items?: unknown } | null | undefined): SavedHost[] {
  const items = payload?.items;
  if (!Array.isArray(items) || items.some((row) => typeof (row as SavedHost | null)?.id !== 'string'))
    throw new Error('HOST_LIST_INVALID');
  return items.map((row) => {
    const host = row as SavedHost;
    const detection = host.detection as unknown;
    // A host that holds no probe yet answers with no detection at all; it reads as a not-probed host. A
    // detection that IS there but is not readable is a refusal, not an empty verdict list.
    if (detection === undefined || detection === null) return { ...host, detection: { checked: false, agents: [] } };
    if (typeof detection !== 'object' || !Array.isArray((detection as { agents?: unknown }).agents))
      throw new Error('HOST_LIST_INVALID');
    return host;
  });
}

/** Every saved SSH host with the probe verdict it already holds. */
export async function listHosts(): Promise<SavedHost[]> {
  return requireHostList(await httpRequest<{ items?: unknown }>('GET', HOSTS_PATH));
}

/** Creates one host; a nickname that is already taken is refused by the host, not by this page. */
export function createHost(fields: HostFields): Promise<SavedHost> {
  return httpRequest<SavedHost>('POST', HOSTS_PATH, fields);
}

/** Saves one host; the answer is the row as the host now holds it. */
export function updateHost(hostId: string, fields: HostFields): Promise<SavedHost> {
  return httpRequest<SavedHost>('PUT', hostPath(hostId), fields);
}

export function deleteHost(hostId: string): Promise<HostDeletion> {
  return httpRequest<HostDeletion>('DELETE', hostPath(hostId));
}

/**
 * Refreshes one host: probes its Agent target again *and* rescans the session index it holds, which is the
 * entry the page's refresh is for — a probe alone leaves the history list untouched. The answer carries the
 * verdicts it recorded and the session count the rescan left behind.
 */
export function refreshHost(hostId: string): Promise<HostRefresh> {
  return httpRequest<HostRefresh>('POST', `${hostPath(hostId)}/refresh`);
}

/**
 * The visible text of a failed host request: the host's own stable code and reason when it answered with one,
 * and the transport's own message otherwise. No failure is swallowed into a success.
 */
export function hostFailureText(error: unknown, t: TFunction): string {
  if (isBackendHttpError(error)) {
    const code = error.code || `HTTP ${error.status}`;
    return t('settings.hosts.requestFailed', {
      code,
      reason: error.backendMessage || t('settings.hosts.reasonUnknown'),
    });
  }
  return error instanceof Error ? error.message : String(error);
}

/**
 * The visible text of a failed refresh or probe. A refusal is the host's own answer and is said as that; a
 * request that ran past its window is not a refusal at all — the host may still be probing — so it is stated
 * as a result nobody has yet, with the retry it can be asked again with.
 */
export function hostRefreshFailureText(error: unknown, t: TFunction): string {
  return isHostTimeout(error) ? t('settings.hosts.refreshUnknown') : hostFailureText(error, t);
}
