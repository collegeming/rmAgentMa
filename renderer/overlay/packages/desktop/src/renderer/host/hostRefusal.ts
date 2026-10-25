/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { isBackendHttpError } from '@/common/adapter/httpBridge';
import type { TFunction } from 'i18next';

/** The code the host answers when the conversation's working directory is one it cannot use. */
export const CWD_MISSING = 'CWD_MISSING';

/**
 * The host's own refusal of a send into a directory it cannot use, said as the code and the reason it gave.
 * This is the one refusal the send path shows verbatim: it is a fact about the conversation, not a transient
 * conflict, and the same code is what the row above the chat already carries.
 */
export function cwdRefusalText(error: unknown, t: TFunction): string | null {
  if (!isBackendHttpError(error) || error.code !== CWD_MISSING) return null;
  return t('conversation.hostTools.requestRefused', {
    code: error.code,
    reason: error.backendMessage || t('settings.hosts.reasonUnknown'),
  });
}

/** Why a send into that conversation cannot be offered at all, as the row stated it. */
export function cwdMissingSendReason(note: string, t: TFunction): string {
  return note
    ? t('conversation.hostTools.cwdMissingSendRefused', { code: CWD_MISSING, reason: note })
    : t('conversation.hostTools.cwdMissingSendRefusedNoNote', { code: CWD_MISSING });
}
