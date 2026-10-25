/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The interactions of one conversation that are waiting for the user right now. A live question card is the
 * fact itself: the card knows whether it is still answerable, so it registers here while it is drawn and
 * releases on unmount or once an answer went through. Nothing else claims a question is pending.
 */
const liveQuestions = new Map<string, number>();

/** Registers one drawn, unanswered question of `conversationId`; call the answer to release it. */
export function markLiveQuestion(conversationId: string): () => void {
  if (!conversationId) return () => {};
  liveQuestions.set(conversationId, (liveQuestions.get(conversationId) ?? 0) + 1);
  let released = false;
  return () => {
    if (released) return;
    released = true;
    const next = (liveQuestions.get(conversationId) ?? 1) - 1;
    if (next > 0) liveQuestions.set(conversationId, next);
    else liveQuestions.delete(conversationId);
  };
}

/** True while a question of that conversation is drawn and still unanswered. */
export const hasLiveQuestion = (conversationId: string): boolean => (liveQuestions.get(conversationId) ?? 0) > 0;

/** Test hook: forget every registered question. */
export const resetLiveQuestions = (): void => {
  liveQuestions.clear();
};
