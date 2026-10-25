/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { TChatConversation } from '@/common/config/storage';
import { historyIncomplete, requestHistoryRetry, useHistoryReading } from '@/common/host/historyState';
import { Button } from '@arco-design/web-react';
import { Attention, FolderClose, History } from '@icon-park/react';
import React from 'react';
import { useTranslation } from 'react-i18next';
import { cwdNoteOf, isCwdMissing, recoveryStateName, type RecoveryReading } from './hostRows';

/**
 * What the host stated about one conversation that the screen would otherwise get wrong: a history it could
 * not read whole, a session it can no longer find, a working directory it cannot use. Each notice carries the
 * host's own reason and offers only the action that exists — asking for the history again.
 */

const noticeClass =
  'flex shrink-0 flex-wrap items-center gap-8px border-b border-solid border-[var(--color-border-2)] bg-[rgba(var(--warning-6),0.12)] px-12px py-6px text-12px leading-18px text-t-secondary';

/**
 * The history this conversation's pages say the host could not read whole. The page the chat drew is what the
 * host holds; this strip is what keeps it from reading as all of it, and the retry asks the same window again.
 */
export const HostHistoryNotice: React.FC<{ conversationId: string }> = ({ conversationId }) => {
  const { t } = useTranslation();
  const reading = useHistoryReading(conversationId);
  if (!historyIncomplete(reading)) return null;

  const state = reading?.state ?? '';
  return (
    <div role='alert' data-testid='host-history-notice' className={noticeClass}>
      <History theme='outline' size='14' className='shrink-0' />
      <span className='min-w-0 break-words'>
        {state === 'unavailable'
          ? t('conversation.hostTools.historyUnavailable')
          : t('conversation.hostTools.historyPartial')}
      </span>
      {reading?.note ? (
        <span data-testid='host-history-note' className='min-w-0 break-all text-t-tertiary'>
          {t('conversation.hostTools.historyNote', { note: reading.note })}
        </span>
      ) : null}
      <Button size='mini' data-testid='host-history-retry' onClick={() => requestHistoryRetry(conversationId)}>
        {t('conversation.hostTools.retry')}
      </Button>
    </div>
  );
};

/**
 * What the host said about the session behind this conversation. Only `attached` is a session that is really
 * there; `gone` and `unknown` are stated as themselves, because a conversation whose execution state nobody
 * knows must never be drawn as if it were running.
 */
export const HostRecoveryNotice: React.FC<{ recovery: RecoveryReading | null }> = ({ recovery }) => {
  const { t } = useTranslation();
  const state = recovery ? recoveryStateName(recovery.state) : null;
  if (state !== 'gone' && state !== 'unknown') return null;

  return (
    <div role='status' data-testid='host-recovery-notice' data-recovery-state={state} className={noticeClass}>
      <Attention theme='outline' size='14' className='shrink-0' />
      <span className='min-w-0 break-words'>
        {state === 'gone' ? t('conversation.hostTools.recoveryGone') : t('conversation.hostTools.recoveryUnknown')}
      </span>
      {recovery?.note ? (
        <span data-testid='host-recovery-note' className='min-w-0 break-all text-t-tertiary'>
          {t('conversation.hostTools.historyNote', { note: recovery.note })}
        </span>
      ) : null}
    </div>
  );
};

/**
 * A conversation whose working directory the host cannot use. Its history stays readable here, and every send
 * or new session in it is refused by the host — so the reason is stated once, above the chat, instead of being
 * discovered from a failed send.
 */
export const HostCwdMissingNotice: React.FC<{ conversation: TChatConversation | undefined }> = ({ conversation }) => {
  const { t } = useTranslation();
  const note = cwdNoteOf(conversation);
  // The row's own reading, so the badge a row shows and this strip are the same fact.
  if (!isCwdMissing(conversation)) return null;

  return (
    <div role='status' data-testid='host-cwd-notice' className={noticeClass}>
      <FolderClose theme='outline' size='14' className='shrink-0' />
      <span className='min-w-0 break-words'>{t('conversation.hostTools.cwdMissing')}</span>
      {note ? (
        <span data-testid='host-cwd-note' className='min-w-0 break-all text-t-tertiary'>
          {t('conversation.hostTools.historyNote', { note })}
        </span>
      ) : null}
      <span className='min-w-0 break-words text-t-tertiary'>{t('conversation.hostTools.cwdMissingHint')}</span>
    </div>
  );
};
