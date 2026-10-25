/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { isHostTimeout, type HostDetection } from '@/common/host/hostTransport';
import type { TFunction } from 'i18next';
import { useSyncExternalStore } from 'react';
import { applyHostRefresh } from './hostDirectory';
import { hostRefreshFailureText, refreshHost } from '@renderer/pages/hosts/hostsApi';

/**
 * Refreshing one saved host when it is selected: `hosts.refresh` probes that host again and rescans the
 * session index it holds, so the sessions under it are read from the host rather than from an older cache.
 *
 * Every answer is kept per host. A refresh that fails leaves the verdicts the row already showed in place and
 * states the code the host returned; a refresh that lands late updates its own host alone, because the row it
 * writes to is the one named by the request that answered. A refresh that ran past its window is not a
 * failure of the host — the probe is likely still running — so that host stays in its refreshing state and
 * the row says the result is unknown, with the retry right there.
 */
export type HostRefreshReading = {
  /**
   * `idle` before anything was asked, `refreshing` while the request is out or while its result is unknown,
   * `failed` after the host itself refused it.
   */
  state: 'idle' | 'refreshing' | 'failed';
  /** How many sessions the rescan left behind, once one answered. */
  sessions: number | null;
  /** The refusal the host gave, or the unknown result of a request that ran past its window. */
  failure: string | null;
};

const IDLE: HostRefreshReading = { state: 'idle', sessions: null, failure: null };

const readings = new Map<string, HostRefreshReading>();
const inFlight = new Map<string, Promise<void>>();
const listeners = new Set<() => void>();

const publish = (hostId: string, reading: HostRefreshReading): void => {
  readings.set(hostId, reading);
  listeners.forEach((listener) => listener());
};

export const hostRefreshReadingOf = (hostId: string): HostRefreshReading => readings.get(hostId) ?? IDLE;

/**
 * Refreshes one host once. A second ask while the first is in flight is not a second probe: it waits for the
 * same answer, so selecting the same host twice does not double the work or rewrite the row twice.
 */
export function refreshHostRow(hostId: string, t: TFunction): Promise<void> {
  if (!hostId) return Promise.resolve();
  const running = inFlight.get(hostId);
  if (running) return running;
  publish(hostId, { state: 'refreshing', sessions: readings.get(hostId)?.sessions ?? null, failure: null });
  const request = refreshHost(hostId)
    .then((answer) => {
      // Only this host's row is written: the verdicts and the count the host just answered are its own.
      applyHostRefresh(hostId, answer.detection as HostDetection | undefined, answer.sessions);
      publish(hostId, {
        state: 'idle',
        sessions: typeof answer.sessions === 'number' ? answer.sessions : null,
        failure: null,
      });
    })
    .catch((error: unknown) => {
      // The verdicts the row already showed stay where they are; only the reason is added. A refusal ends the
      // refresh, while a request that ran past its window leaves the host in its refreshing state: the probe
      // is most likely still running there, and the retry beside that note is how it is asked again.
      publish(hostId, {
        state: isHostTimeout(error) ? 'refreshing' : 'failed',
        sessions: readings.get(hostId)?.sessions ?? null,
        failure: hostRefreshFailureText(error, t),
      });
    })
    .finally(() => {
      inFlight.delete(hostId);
    });
  inFlight.set(hostId, request);
  return request;
}

/** Test hook: drop every reading and waiter. */
export function resetHostRefresh(): void {
  readings.clear();
  inFlight.clear();
  listeners.clear();
}

const subscribe = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

/** The refresh state of one host, as this renderer last saw it. */
export function useHostRefreshReading(hostId: string): HostRefreshReading {
  return useSyncExternalStore(
    subscribe,
    () => hostRefreshReadingOf(hostId),
    () => hostRefreshReadingOf(hostId)
  );
}
