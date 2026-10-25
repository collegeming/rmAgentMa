/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { getCurrentConversation } from '@renderer/pages/conversation/explorer/currentConversationStore';
import { getConversationRuntimeViewSnapshot } from '@renderer/pages/conversation/runtime/conversationRuntimeViewStore';
import { useSyncExternalStore } from 'react';
import { hasLiveQuestion } from './pendingInteraction';

/**
 * Leaving a conversation that is still doing something.
 *
 * The product's own switch closes the conversation it leaves (the repository closes the current session before
 * it opens the next one), so a switch made while a turn runs or while an interaction waits is a stop. That
 * makes the confirmation the user's to give: this module holds the action while it is unanswered, and only the
 * user's own button runs it. Nothing here cancels a turn or closes a conversation on its own.
 */
export type BusyReading = {
  /** The conversation is running a turn, cancelling one, or starting one. */
  running: boolean;
  /** A drawn question of that conversation is still waiting for an answer. */
  question: boolean;
  /** The host reported pending approvals for it. */
  approvals: number;
};

/** What the user asked for, which decides the wording of the button that stops the conversation. */
export type BusyIntent = 'switch' | 'newSession';

export const BUSY_IDLE: BusyReading = { running: false, question: false, approvals: 0 };

export const isBusy = (reading: BusyReading): boolean => reading.running || reading.question || reading.approvals > 0;

/**
 * What the open conversation is doing right now, from the product's own runtime store — the same reading the
 * chat's stop button and the sidebar's spinner use, never a guess made from a request of our own.
 */
export function openConversationBusy(): BusyReading {
  const conversationId = getCurrentConversation();
  if (!conversationId) return BUSY_IDLE;
  const view = getConversationRuntimeViewSnapshot(conversationId);
  return {
    running: view?.isProcessing === true,
    question: hasLiveQuestion(conversationId),
    approvals: typeof view?.pendingConfirmations === 'number' && view.pendingConfirmations > 0 ? 1 : 0,
  };
}

/** One held action, with the reading that held it and the wording it is confirmed with. */
export type PendingSwitch = { from: string; kind: BusyIntent; reading: BusyReading; run: () => void };

let pending: PendingSwitch | null = null;
const listeners = new Set<() => void>();

const publish = (next: PendingSwitch | null): void => {
  pending = next;
  listeners.forEach((listener) => listener());
};

/**
 * Asks to leave the open conversation for good. A conversation that is doing nothing goes ahead and this
 * answers false, so the caller runs `run` itself; one that is running or waiting for an answer is held here
 * and this answers true, so the caller runs nothing until the confirmation is taken.
 */
export function beginSwitch(run: () => void, kind: BusyIntent = 'switch'): boolean {
  const from = getCurrentConversation();
  const reading = openConversationBusy();
  if (!from || !isBusy(reading)) return false;
  publish({ from, kind, reading, run });
  return true;
}

/** The held action the user confirmed, and nothing else: running it is the caller's own line. */
export function takeConfirmedSwitch(): PendingSwitch | null {
  const taken = pending;
  publish(null);
  return taken;
}

export function cancelSwitch(): void {
  publish(null);
}

export function pendingSwitchOf(): PendingSwitch | null {
  return pending;
}

/** Test hook: drop a held switch. */
export function resetPendingSwitch(): void {
  pending = null;
  listeners.clear();
}

const subscribe = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

/** The action being confirmed right now, or null while the user is free to move around. */
export function usePendingSwitch(): PendingSwitch | null {
  return useSyncExternalStore(subscribe, pendingSwitchOf, pendingSwitchOf);
}
