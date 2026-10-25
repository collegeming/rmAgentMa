/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * What the renderer knows about the native host's connection, written only by the transport itself: a host
 * request that fails without an answer (a timeout, or a bridge that is not there) marks the connection
 * interrupted, and any answer or event that follows marks it live again. The host's own event stream is the
 * recovery channel — nothing here polls, and no timer invents a status.
 */
export type HostConnectionSnapshot = {
  state: 'live' | 'interrupted';
  /** The transport error that interrupted the connection; the code the user is shown. */
  reason: string;
  /** When the interruption was recorded, for staleness-free reporting. */
  at: number;
};

let snapshot: HostConnectionSnapshot = { state: 'live', reason: '', at: 0 };
const listeners = new Set<() => void>();

const publish = (next: HostConnectionSnapshot) => {
  if (next.state === snapshot.state && next.reason === snapshot.reason) return;
  snapshot = next;
  for (const listener of listeners) {
    try {
      listener();
    } catch (error) {
      console.warn('[host] connection listener failed', error);
    }
  }
};

export const subscribeHostConnection = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

export const hostConnectionSnapshot = (): HostConnectionSnapshot => snapshot;

/** The transport itself answered, or a host event arrived: the connection is live. */
export function markHostLive(): void {
  publish({ state: 'live', reason: '', at: 0 });
}

/** A host request failed with no answer: the connection is interrupted, and the code says why. */
export function markHostInterrupted(error: unknown): void {
  publish({
    state: 'interrupted',
    reason: error instanceof Error ? error.message : String(error),
    at: Date.now(),
  });
}

/** Test hook: reset the store between cases. */
export function resetHostConnection(): void {
  snapshot = { state: 'live', reason: '', at: 0 };
  listeners.clear();
}
