/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { SavedHost } from '@/common/host/hostTransport';
import { hostFailureText, listHosts } from '@/renderer/pages/hosts/hostsApi';
import type { TFunction } from 'i18next';
import { useSyncExternalStore } from 'react';

/**
 * The saved hosts, read once and shared by every surface that has to name one — the workspace group titles,
 * the sidebar's host filter, the search page. Nothing is loaded until a surface asks for the list.
 */
export type HostDirectory = { hosts: SavedHost[]; failure: string | null; loaded: boolean };

let snapshot: HostDirectory = { hosts: [], failure: null, loaded: false };
let request: Promise<void> | null = null;
const listeners = new Set<() => void>();

const publish = (next: HostDirectory) => {
  snapshot = next;
  listeners.forEach((listener) => listener());
};

export const subscribeHostDirectory = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

export const hostDirectorySnapshot = (): HostDirectory => snapshot;

/** The name to show for a host id: the saved host when it has been read, the id while it has not. */
export function hostLabelOf(hostId: string): string | null {
  const host = snapshot.hosts.find((row) => row.id === hostId);
  return host ? host.nickname || host.hostname || host.id : null;
}

/**
 * Reads the saved hosts. Concentrate callers on one request: a second ask while the first is in flight
 * shares it, and a list already read is reused until `force` asks for it again.
 */
export function loadHostDirectory(t: TFunction, force = false): Promise<void> {
  if (request) return request;
  if (snapshot.loaded && !force) return Promise.resolve();
  request = listHosts()
    .then((hosts) => publish({ hosts, failure: null, loaded: true }))
    .catch((error: unknown) => publish({ hosts: snapshot.hosts, failure: hostFailureText(error, t), loaded: true }))
    .finally(() => {
      request = null;
    });
  return request;
}

/** The directory as last read. This subscribes but never loads, so a title can name a host after the fact. */
export function useHostDirectory(): HostDirectory {
  return useSyncExternalStore(subscribeHostDirectory, hostDirectorySnapshot, hostDirectorySnapshot);
}

/** Test hook: drop the shared answer and its subscribers. */
export function resetHostDirectory(): void {
  snapshot = { hosts: [], failure: null, loaded: false };
  request = null;
  listeners.clear();
}
