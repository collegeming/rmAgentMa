/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { useSyncExternalStore } from 'react';

/**
 * What the host holds for one conversation's history, as every `messages.list`, `messages.before` and
 * `messages.latest` page states it. `state` is one of `complete`, `partial` or `unavailable`; this module
 * keeps whatever the host said, and only `complete` ever reads as a whole history — a partial or unavailable
 * answer is what the conversation screen has to say out loud instead of drawing the loaded page as all of it.
 */
export type HistoryState = 'complete' | 'partial' | 'unavailable';

export type HistoryReading = {
  /** The literal the host answered, or empty while it never stated one. */
  state: string;
  /** The host's own reason, e.g. which part of the history it could not read. */
  note: string;
};

export const HISTORY_COMPLETE: HistoryState = 'complete';
export const HISTORY_PARTIAL: HistoryState = 'partial';
export const HISTORY_UNAVAILABLE: HistoryState = 'unavailable';

/** The state this build can act on, or null for a history the host did not describe as one of the three. */
export function historyStateOf(reading: HistoryReading | null | undefined): HistoryState | null {
  const state = reading?.state;
  return state === HISTORY_COMPLETE || state === HISTORY_PARTIAL || state === HISTORY_UNAVAILABLE ? state : null;
}

/**
 * True while the host said this history is not whole. A history the host never described is not read as
 * whole either: nothing would have claimed it was.
 */
export function historyIncomplete(reading: HistoryReading | null | undefined): boolean {
  const state = historyStateOf(reading);
  return state !== null && state !== HISTORY_COMPLETE;
}

/** The `history` field one page answer carries, or null when that answer names none. */
export function readHistoryField(payload: unknown): HistoryReading | null {
  if (!payload || typeof payload !== 'object') return null;
  const row = payload as { history?: unknown };
  const field = row.history;
  if (!field || typeof field !== 'object') return null;
  const stated = field as { state?: unknown; note?: unknown };
  if (typeof stated.state !== 'string' || !stated.state.trim()) return null;
  return {
    state: stated.state.trim(),
    note: typeof stated.note === 'string' ? stated.note.trim() : '',
  };
}

/**
 * The reading the host last gave for each conversation. Every page load states it again, so a later page that
 * says `complete` is what clears an earlier `partial`, and a conversation that was never read holds none.
 */
const readings = new Map<string, HistoryReading>();
const listeners = new Set<() => void>();

/** The last reading of one conversation; a page the host never described for it holds none. */
export const historyReadingOf = (conversationId: string): HistoryReading | null => readings.get(conversationId) ?? null;

export function publishHistoryReading(conversationId: string, reading: HistoryReading | null): void {
  if (!conversationId || !reading) return;
  const previous = readings.get(conversationId);
  if (previous && previous.state === reading.state && previous.note === reading.note) return;
  readings.set(conversationId, reading);
  listeners.forEach((listener) => listener());
}

/** Test hook: drop every reading. */
export function resetHistoryReadings(): void {
  readings.clear();
  listeners.clear();
}

const subscribe = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

/** The reading of one conversation, or null while the host never stated one for it. */
export function useHistoryReading(conversationId: string): HistoryReading | null {
  return useSyncExternalStore(
    subscribe,
    () => historyReadingOf(conversationId),
    () => historyReadingOf(conversationId)
  );
}

/**
 * The renderer's own retry of an incomplete history. It is a request the conversation screen makes to itself:
 * the hook that loads pages listens for it and reads the newest page again, and nothing here claims a history
 * was reloaded — the host's own next answer is what says so.
 */
export const HISTORY_RETRY_EVENT = 'rmagent-history-retry';

export function requestHistoryRetry(conversationId: string): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent(HISTORY_RETRY_EVENT, { detail: { conversation_id: conversationId } }));
}
