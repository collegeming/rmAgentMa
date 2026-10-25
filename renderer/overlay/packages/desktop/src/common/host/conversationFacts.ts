/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { useSyncExternalStore } from 'react';
import type { RecoveryReading } from '@renderer/host/hostRows';

/**
 * The facts a conversation row states about itself beyond its title: whether its working directory can be
 * used, why not, and what became of the session behind it.
 *
 * The rows that carry them arrive on more than one route — the list, one conversation read, a runtime
 * snapshot — and a screen that only held one of those answers would keep showing an older truth. Recording
 * them here, from every answer that states them, is what lets the send box refuse a directory the host cannot
 * use even when the list was read long before it changed.
 */
export type ConversationFacts = {
  /** The host cannot use this conversation's working directory. */
  cwdMissing: boolean;
  /** Why, as the host stated it; empty while it stated none. */
  cwdNote: string;
  /** What the host said about the session itself, when it said anything. */
  recovery: RecoveryReading | null;
};

const EMPTY: ConversationFacts = { cwdMissing: false, cwdNote: '', recovery: null };

const facts = new Map<string, ConversationFacts>();
const listeners = new Set<() => void>();

const text = (value: unknown): string => (typeof value === 'string' ? value.trim() : '');

/** Reads one row's own fields, or null when that row is not a conversation row and states none of them. */
export function conversationFactsOfRow(row: unknown): { id: string; reading: ConversationFacts } | null {
  if (!row || typeof row !== 'object') return null;
  const fields = row as Record<string, unknown>;
  const id = text(fields.id);
  if (!id) return null;
  const cwdMissing = fields.cwd_missing === true;
  const cwdNote = text(fields.cwd_note);
  const recoveryField = fields.recovery;
  const recovery =
    recoveryField && typeof recoveryField === 'object' && text((recoveryField as { state?: unknown }).state)
      ? {
          state: text((recoveryField as { state?: unknown }).state),
          note: text((recoveryField as { note?: unknown }).note),
        }
      : null;
  // A row that names the conversation's own workspace is a conversation row: what it does not state is what
  // that conversation does not have, so it replaces what an older answer said. Anything else is only read for
  // the facts it really states.
  const isConversationRow = 'workspace' in fields || 'name' in fields;
  if (!isConversationRow && !cwdMissing && !cwdNote && !recovery) return null;
  return { id, reading: { cwdMissing, cwdNote, recovery } };
}

/**
 * Records what one answer stated about a conversation. Only a real conversation row — one that names the
 * conversation's own workspace or title — replaces the recorded directory state, so a row that does not carry
 * the field at all cannot make a missing directory look usable again; every other answer adds what it states.
 */
export function publishConversationFacts(row: unknown): void {
  const projected = conversationFactsOfRow(row);
  if (!projected) return;
  const fields = row as Record<string, unknown>;
  const isConversationRow = 'workspace' in fields || 'name' in fields;
  const previous = facts.get(projected.id);
  const merged: ConversationFacts = {
    cwdMissing: isConversationRow
      ? projected.reading.cwdMissing
      : projected.reading.cwdMissing || (previous?.cwdMissing ?? false),
    cwdNote: isConversationRow ? projected.reading.cwdNote : projected.reading.cwdNote || (previous?.cwdNote ?? ''),
    recovery: projected.reading.recovery ?? previous?.recovery ?? null,
  };
  if (
    previous &&
    previous.cwdMissing === merged.cwdMissing &&
    previous.cwdNote === merged.cwdNote &&
    sameRecovery(previous.recovery, merged.recovery)
  )
    return;
  facts.set(projected.id, merged);
  listeners.forEach((listener) => listener());
}

const sameRecovery = (left: RecoveryReading | null, right: RecoveryReading | null): boolean =>
  (left?.state ?? '') === (right?.state ?? '') && (left?.note ?? '') === (right?.note ?? '');

export const conversationFactsOf = (conversationId: string): ConversationFacts => facts.get(conversationId) ?? EMPTY;

/** True while the host cannot use that conversation's working directory. */
export const isCwdMissingFact = (conversationId: string): boolean => conversationFactsOf(conversationId).cwdMissing;

/** Test hook: drop every recorded fact. */
export function resetConversationFacts(): void {
  facts.clear();
  listeners.clear();
}

const subscribe = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

/** What is known about one conversation, as the last answers stated it. */
export function useConversationFacts(conversationId: string): ConversationFacts {
  return useSyncExternalStore(
    subscribe,
    () => conversationFactsOf(conversationId),
    () => conversationFactsOf(conversationId)
  );
}
