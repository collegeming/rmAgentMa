/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { hostFailureText } from '@renderer/pages/hosts/hostsApi';
import { retryHostEvents } from '@/common/host/hostTransport';
import {
  markHostInterrupted,
  markHostLive,
  hostConnectionSnapshot,
  subscribeHostConnection,
} from '@/common/host/hostConnection';
import { isBackendHttpError, wsEmitter } from '@/common/adapter/httpBridge';
import { Button } from '@arco-design/web-react';
import { LinkOne } from '@icon-park/react';
import React, { useEffect, useState, useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';

/** The code the host answers when the conversation a caller named is not in its index. */
export const CONVERSATION_NOT_FOUND = 'CONVERSATION_NOT_FOUND';

/**
 * True when the answer says the host holds no such conversation. That is the empty state of a fresh install —
 * the renderer's built-in entry conversation before anything was ever indexed — and not a broken connection.
 */
export function isConversationMissing(error: unknown): boolean {
  return isBackendHttpError(error) && error.code === CONVERSATION_NOT_FOUND;
}

/**
 * What a fresh install opens on: the entry conversation the renderer boots into is not in the host's index, so
 * there is no session to read and nothing to retry into. This is an empty state with the one entry that works —
 * creating a conversation — and it creates none by itself, because whether a session should exist is the user's
 * decision, not this page's.
 */
const HostConversationEmptyState: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();

  return (
    <div
      data-testid='conversation-empty-state'
      className='flex size-full flex-col items-center justify-center gap-12px px-24px text-center'
    >
      <p className='m-0 max-w-560px text-15px leading-22px text-t-primary'>{t('conversation.hostTools.emptyTitle')}</p>
      <p className='m-0 max-w-560px break-words text-13px leading-20px text-t-secondary'>
        {t('conversation.hostTools.emptyHint')}
      </p>
      <Button type='primary' data-testid='conversation-empty-new' onClick={() => navigate('/guid')}>
        {t('settings.newConversation.title')}
      </Button>
    </div>
  );
};

/**
 * A conversation that could not be read: the host's own code and reason, and the one action that is worth
 * offering — asking again. Nothing is drawn as an empty session, and the failure is never rounded off into
 * "not found"; a conversation the host really does not hold is the empty state above instead of this page.
 */
export const HostConversationFailure: React.FC<{ error: unknown; onRetry: () => void }> = ({ error, onRetry }) => {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);

  if (isConversationMissing(error)) return <HostConversationEmptyState />;

  return (
    <div className='flex size-full flex-col items-center justify-center gap-12px px-24px text-center'>
      <p role='alert' className='m-0 max-w-560px break-words text-13px leading-20px text-danger-6'>
        {t('conversation.hostTools.openFailed', { reason: hostFailureText(error, t) })}
      </p>
      <Button
        type='primary'
        loading={busy}
        data-testid='conversation-open-retry'
        onClick={() => {
          setBusy(true);
          try {
            onRetry();
          } finally {
            setBusy(false);
          }
        }}
      >
        {t('conversation.hostTools.retry')}
      </Button>
    </div>
  );
};

/**
 * The connection-lost strip. It follows the host's own link channel: `realtime.disconnected` says the link is
 * lost (or is waiting on the user), `realtime.reconnected` says it is usable again — the same frames history
 * and approvals reconcile on, so nothing here polls. A request that merely ran past its own window does not
 * raise this state: the host may still be working on it, and the action that ran out of time reports itself.
 * The button covers the one case the stream cannot: a session whose events were never subscribed.
 */
export const HostReconnectBanner: React.FC = () => {
  const { t } = useTranslation();
  const connection = useSyncExternalStore(subscribeHostConnection, hostConnectionSnapshot, hostConnectionSnapshot);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    const offDisconnected = wsEmitter<{ state?: unknown; link_state?: unknown }>('realtime.disconnected').on(
      (payload) => {
        const linkState = typeof payload?.link_state === 'string' ? payload.link_state : '';
        const state = typeof payload?.state === 'string' ? payload.state : '';
        markHostInterrupted(new Error(linkState || state || 'realtime.disconnected'));
      }
    );
    const offReconnected = wsEmitter('realtime.reconnected').on(() => markHostLive());
    return () => {
      offDisconnected();
      offReconnected();
    };
  }, []);

  if (connection.state === 'live') return null;

  const retry = async () => {
    setBusy(true);
    setFailure(null);
    try {
      await retryHostEvents();
    } catch (error) {
      setFailure(hostFailureText(error, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      role='status'
      data-testid='host-reconnect-banner'
      className='flex shrink-0 flex-wrap items-center justify-center gap-8px bg-[rgba(var(--warning-6),0.12)] px-12px py-6px text-12px leading-18px text-t-secondary'
    >
      <LinkOne theme='outline' size='14' className='shrink-0' />
      <span className='min-w-0 break-words'>{t('conversation.hostTools.reconnecting')}</span>
      <span className='min-w-0 break-all text-t-tertiary'>{connection.reason}</span>
      {failure && (
        <span role='alert' className='min-w-0 break-all text-danger-6'>
          {failure}
        </span>
      )}
      <Button size='mini' loading={busy} onClick={() => void retry()}>
        {t('conversation.hostTools.retry')}
      </Button>
    </div>
  );
};
