/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { hostAction } from '@/common/host/hostTransport';

export type NativeDraft = { text: string; files: { kind: 'upload'; path: string }[] };
export type DraftSaveStatus = 'saved' | 'saving' | 'unsaved';
type DraftState = {
  revision: number;
  draft: NativeDraft;
  loading?: Promise<NativeDraft>;
  loaded: boolean;
  saving: Promise<void>;
  status: DraftSaveStatus;
};
const drafts = new Map<string, DraftState>();
const listeners = new Map<string, Set<() => void>>();
const stateFor = (conversationId: string): DraftState => {
  let state = drafts.get(conversationId);
  if (!state) {
    state = { revision: 0, draft: { text: '', files: [] }, loaded: false, saving: Promise.resolve(), status: 'saved' };
    drafts.set(conversationId, state);
  }
  return state;
};
const notify = (id: string) => listeners.get(id)?.forEach((listener) => listener());
export const getDraftSaveStatus = (id: string): DraftSaveStatus => stateFor(id).status;
export function subscribeDraftSaveStatus(id: string, listener: () => void): () => void {
  const set = listeners.get(id) ?? new Set();
  set.add(listener);
  listeners.set(id, set);
  return () => {
    set.delete(listener);
  };
}
export function validateNativeDraft(value: unknown): NativeDraft {
  if (!value || typeof value !== 'object') throw new Error('DRAFT_INVALID');
  const draft = value as NativeDraft;
  if (
    typeof draft.text !== 'string' ||
    draft.text.length > 16384 ||
    !Array.isArray(draft.files) ||
    draft.files.length > 4 ||
    !draft.files.every((ref) => ref?.kind === 'upload' && /^probe-upload:[a-f0-9-]{36}$/.test(ref.path))
  )
    throw new Error('DRAFT_INVALID');
  return { text: draft.text, files: draft.files.map((ref) => ({ kind: 'upload', path: ref.path })) };
}
export async function loadNativeDraft(conversationId: string): Promise<NativeDraft> {
  const state = stateFor(conversationId);
  if (state.loaded) return state.draft;
  if (state.loading) return state.loading;
  const revision = state.revision;
  state.loading = hostAction<NativeDraft & { conversation_id?: string }>('draft.load', conversationId)
    .then((result) => {
      if (result.conversation_id && result.conversation_id !== conversationId) throw new Error('DRAFT_SCOPE_MISMATCH');
      const draft = validateNativeDraft({ ...result, files: result.files ?? [] });
      if (revision === 0 && state.revision === revision) state.draft = draft;
      state.loaded = true;
      return state.draft;
    })
    .finally(() => {
      state.loading = undefined;
    });
  return state.loading;
}
export function saveNativeDraft(conversationId: string, draft: NativeDraft): Promise<void> {
  const state = stateFor(conversationId);
  state.revision++;
  state.draft = draft;
  state.status = 'saving';
  notify(conversationId);
  const revision = state.revision;
  const operation = state.saving
    .catch((): void => undefined)
    .then(async () => {
      await loadNativeDraft(conversationId);
      if (state.revision !== revision) return;
      const validated = validateNativeDraft(draft);
      await hostAction('draft.save', conversationId, validated);
      if (state.revision === revision) {
        state.status = 'saved';
        notify(conversationId);
      }
    })
    .catch((error: unknown) => {
      if (state.revision === revision) {
        state.status = 'unsaved';
        notify(conversationId);
      }
      throw error;
    });
  state.saving = operation;
  return operation;
}
