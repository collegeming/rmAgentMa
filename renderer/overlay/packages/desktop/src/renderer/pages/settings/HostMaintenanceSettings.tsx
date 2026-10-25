/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { httpRequest } from '@/common/adapter/httpBridge';
import { hostFailureText } from '@renderer/pages/hosts/hostsApi';
import { Button, Modal } from '@arco-design/web-react';
import React, { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import SettingsPageWrapper from './components/SettingsPageWrapper';
/** What `POST /api/index/clear` answers: what the index held before the clear, and what it holds after. */
type IndexClearAnswer = { sessionsBefore: number; sessions: number };

/** What `POST /api/history/clear` answers: how many conversations' history the host dropped. */
type HistoryClearAnswer = { conversations: number };

/**
 * The counts the host named, read as they came. An answer that does not carry both of them is a failure:
 * a clear is never reported from an answer this page could not read.
 */
export function parseIndexClearAnswer(payload: unknown): IndexClearAnswer {
  const row = (payload ?? {}) as { cleared?: unknown; sessions_before?: unknown; sessions?: unknown };
  if (row.cleared !== true || typeof row.sessions_before !== 'number' || typeof row.sessions !== 'number')
    throw new Error('HOST_INDEX_CLEAR_INVALID');
  return { sessionsBefore: row.sessions_before, sessions: row.sessions };
}

/**
 * The same reading for the history store. It is a different store from the session index, and the host names
 * how many conversations it dropped there — a count this page never fills in from the request it made.
 */
export function parseHistoryClearAnswer(payload: unknown): HistoryClearAnswer {
  const row = (payload ?? {}) as { cleared?: unknown; conversations?: unknown };
  if (row.cleared !== true || typeof row.conversations !== 'number') throw new Error('HOST_HISTORY_CLEAR_INVALID');
  return { conversations: row.conversations };
}

/**
 * The host's own maintenance page: what this phone's host process holds, and the two actions that drop one of
 * its caches each. The session index and the history store are separate, so they are separate buttons; each
 * confirmation is the user's, each count is the host's, and a refusal shows the code and reason the host
 * returned instead of a clear that never happened.
 */
const HostMaintenanceSettings: React.FC = () => {
  const { t } = useTranslation();
  const [confirming, setConfirming] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [confirmingHistory, setConfirmingHistory] = useState(false);
  const [clearingHistory, setClearingHistory] = useState(false);
  const [historyFailure, setHistoryFailure] = useState<string | null>(null);
  const [historyDone, setHistoryDone] = useState<string | null>(null);

  const clear = useCallback(async () => {
    setConfirming(false);
    setClearing(true);
    setFailure(null);
    setDone(null);
    try {
      const answer = parseIndexClearAnswer(await httpRequest<unknown>('POST', '/api/index/clear', {}));
      setDone(t('settings.hostMaintenance.clearIndexDone', { before: answer.sessionsBefore, after: answer.sessions }));
    } catch (error) {
      setFailure(hostFailureText(error, t));
    } finally {
      setClearing(false);
    }
  }, [t]);

  const clearHistory = useCallback(async () => {
    setConfirmingHistory(false);
    setClearingHistory(true);
    setHistoryFailure(null);
    setHistoryDone(null);
    try {
      const answer = parseHistoryClearAnswer(await httpRequest<unknown>('POST', '/api/history/clear', {}));
      setHistoryDone(t('settings.hostMaintenance.clearHistoryDone', { conversations: answer.conversations }));
    } catch (error) {
      setHistoryFailure(hostFailureText(error, t));
    } finally {
      setClearingHistory(false);
    }
  }, [t]);

  return (
    <SettingsPageWrapper contentClassName='md:max-w-640px'>
      <h1 className='m-0 text-22px leading-[1.2] font-bold text-t-primary'>{t('settings.hostMaintenance.title')}</h1>
      <p className='m-0 mt-6px text-13px leading-20px text-t-secondary'>{t('settings.hostMaintenance.description')}</p>

      <div className='mt-16px flex flex-col gap-10px rounded-16px border border-solid border-[var(--color-border-2)] bg-fill-1 p-16px'>
        <span className='text-14px leading-20px font-medium text-t-primary'>
          {t('settings.hostMaintenance.clearIndex')}
        </span>
        <p className='m-0 text-12px leading-18px text-t-secondary'>{t('settings.hostMaintenance.clearIndexHint')}</p>
        <Button
          status='danger'
          className='self-start'
          loading={clearing}
          disabled={clearing}
          data-testid='clear-index-cache'
          onClick={() => setConfirming(true)}
        >
          {t('settings.hostMaintenance.clearIndex')}
        </Button>
        {done && (
          <p
            role='status'
            data-testid='clear-index-done'
            className='m-0 break-words text-13px leading-18px text-success'
          >
            {done}
          </p>
        )}
        {failure && (
          <p role='alert' className='m-0 break-words text-13px leading-18px text-danger-6'>
            {failure}
          </p>
        )}
      </div>

      <div className='mt-12px flex flex-col gap-10px rounded-16px border border-solid border-[var(--color-border-2)] bg-fill-1 p-16px'>
        <span className='text-14px leading-20px font-medium text-t-primary'>
          {t('settings.hostMaintenance.clearHistory')}
        </span>
        <p className='m-0 text-12px leading-18px text-t-secondary'>{t('settings.hostMaintenance.clearHistoryHint')}</p>
        <Button
          status='danger'
          className='self-start'
          loading={clearingHistory}
          disabled={clearingHistory}
          data-testid='clear-history-cache'
          onClick={() => setConfirmingHistory(true)}
        >
          {t('settings.hostMaintenance.clearHistory')}
        </Button>
        {historyDone && (
          <p
            role='status'
            data-testid='clear-history-done'
            className='m-0 break-words text-13px leading-18px text-success'
          >
            {historyDone}
          </p>
        )}
        {historyFailure && (
          <p
            role='alert'
            data-testid='clear-history-failure'
            className='m-0 break-words text-13px leading-18px text-danger-6'
          >
            {historyFailure}
          </p>
        )}
      </div>

      {/* The confirmation is the second step the host asks for: nothing is asked of it before this. */}
      <Modal
        visible={confirming}
        title={t('settings.hostMaintenance.clearIndex')}
        onCancel={() => setConfirming(false)}
        style={{ width: 'min(480px, 92vw)' }}
        footer={
          <div className='flex justify-end gap-10px'>
            <Button disabled={clearing} onClick={() => setConfirming(false)}>
              {t('common.cancel')}
            </Button>
            <Button status='danger' data-testid='clear-index-confirm' onClick={() => void clear()}>
              {t('settings.hostMaintenance.clearIndexOk')}
            </Button>
          </div>
        }
      >
        <p className='m-0 break-words text-13px leading-20px text-t-secondary'>
          {t('settings.hostMaintenance.clearIndexConfirm')}
        </p>
      </Modal>

      {/* Dropping the history cache is its own decision: the index is not what this one deletes. */}
      <Modal
        visible={confirmingHistory}
        title={t('settings.hostMaintenance.clearHistory')}
        onCancel={() => setConfirmingHistory(false)}
        style={{ width: 'min(480px, 92vw)' }}
        footer={
          <div className='flex justify-end gap-10px'>
            <Button disabled={clearingHistory} onClick={() => setConfirmingHistory(false)}>
              {t('common.cancel')}
            </Button>
            <Button status='danger' data-testid='clear-history-confirm' onClick={() => void clearHistory()}>
              {t('settings.hostMaintenance.clearHistoryOk')}
            </Button>
          </div>
        }
      >
        <p className='m-0 break-words text-13px leading-20px text-t-secondary'>
          {t('settings.hostMaintenance.clearHistoryConfirm')}
        </p>
      </Modal>
    </SettingsPageWrapper>
  );
};

export default HostMaintenanceSettings;
