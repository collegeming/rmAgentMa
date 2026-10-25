/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { hostAction } from '@/common/host/hostTransport';
import { isAbsoluteRemotePath } from './remotePath';

export type NativeDraft = { text: string; files: { kind: 'upload'; path: string }[] };
export type DraftSaveStatus = 'saved' | 'saving' | 'unsaved';

/** The reference shape the renderer probe's own host fixture answers for a finished upload. */
const PROBE_UPLOAD_REF = /^probe-upload:[a-f0-9-]{36}$/;
/** A reference bound: a real absolute path fits well inside it, and one bridge message still carries it. */
const MAX_REFERENCE_LENGTH = 4096;

/** Whether the value carries a control character, which no path on any host holds. */
function hasControlCharacter(value: string): boolean {
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0;
    if (code < 0x20 || code === 0x7f) return true;
  }
  return false;
}

/**
 * Whether one attachment reference belongs in a draft. The product host answers the absolute remote path its own
 * `attachment.finish` wrote, so a path this build cannot read as an absolute one is refused instead of kept beside
 * the text. Nothing of it reaches the host — a draft is saved as its text alone, because a message's files travel
 * with that message — and no file content is held here either: the reference is the whole of what a draft keeps.
 */
function isDraftFileRef(path: string): boolean {
  if (path.length > MAX_REFERENCE_LENGTH || hasControlCharacter(path)) return false;
  return PROBE_UPLOAD_REF.test(path) || isAbsoluteRemotePath(path);
}

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
    !draft.files.every((ref) => ref?.kind === 'upload' && typeof ref.path === 'string' && isDraftFileRef(ref.path))
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
/**
 * Keeps the draft in this session's own memory and saves its text as the host's draft. The attachment references
 * stay here — the host stores text alone and refuses a file list, because a file a message names travels with that
 * message — so a draft that holds an upload is saved as its text and keeps its attachment while this page lives.
 */
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
      // The request carries the text alone: a file list is refused by the host, and the attachment references
      // stay in this session's memory (state.draft above) until the message that names them is sent.
      await hostAction('draft.save', conversationId, { text: validated.text });
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
