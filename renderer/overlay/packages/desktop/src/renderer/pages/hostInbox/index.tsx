/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { Button, Empty, Spin } from '@arco-design/web-react';
import { Refresh } from '@icon-park/react';
import { listInbox, type InboxItem } from '@renderer/host/hostActions';
import { hostLabelOf } from '@renderer/host/hostDirectory';
import { hostFailureText } from '@renderer/pages/hosts/hostsApi';
import { useLayoutContext } from '@renderer/hooks/context/LayoutContext';
import { formatRelativeTime } from '@/renderer/utils/chat/relativeTime';
import classNames from 'classnames';
import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';

/** The reason codes the host names, each with its own label; an unknown code is shown as it came. */
const KIND_KEYS: Record<string, string> = {
  approval: 'conversation.hostTools.inboxKindApproval',
  question: 'conversation.hostTools.inboxKindQuestion',
  failure: 'conversation.hostTools.inboxKindFailure',
  busy: 'conversation.hostTools.inboxKindBusy',
};

const KIND_CLASS: Record<string, string> = {
  approval: 'text-warning',
  question: 'text-warning',
  failure: 'text-danger-6',
  busy: 'text-t-secondary',
};

/**
 * Everything waiting on this person, as the host's own attention projection reports it: approvals, questions,
 * a failed turn and a running one. The host ranks the list and this page keeps that order — it never re-ranks
 * what the host already ranked. The host projects the session its link knows about, so the scope it reports is
 * stated as it came, and tapping a row returns to the conversation the row names.
 */
const HostInboxPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const layout = useLayoutContext();
  const isMobile = layout?.isMobile ?? false;
  const [items, setItems] = useState<InboxItem[] | null>(null);
  const [scope, setScope] = useState('');
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const answer = await listInbox();
      setItems(answer.items);
      setScope(answer.scope);
      setFailure(null);
    } catch (error) {
      setItems(null);
      setFailure(hostFailureText(error, t));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className='box-border flex h-full w-full min-h-0 flex-col overflow-hidden bg-1'>
      <div className={classNames('shrink-0 bg-1', isMobile ? 'px-16px pt-14px pb-10px' : 'px-40px pt-24px pb-10px')}>
        <div className='mx-auto flex w-full max-w-800px flex-col gap-6px'>
          <div className='flex items-center justify-between gap-10px'>
            <h1 className='m-0 text-20px leading-[1.2] font-bold text-t-primary'>
              {t('conversation.hostTools.inboxTitle')}
            </h1>
            <Button
              size='small'
              icon={<Refresh theme='outline' size='14' />}
              loading={loading}
              onClick={() => void load()}
            >
              {t('conversation.hostTools.inboxRefresh')}
            </Button>
          </div>
          {/* What the host's projection covers, exactly as it reported it. */}
          {scope && (
            <span className='text-12px leading-18px text-t-tertiary' data-testid='host-inbox-scope'>
              {t('conversation.hostTools.inboxScope', { scope })}
            </span>
          )}
        </div>
      </div>

      <div
        className={classNames(
          'min-h-0 flex-1 overflow-y-auto overscroll-contain',
          isMobile ? 'px-16px pb-20px' : 'px-40px pb-24px'
        )}
      >
        <div className='mx-auto flex w-full max-w-800px flex-col gap-8px'>
          {loading && items === null ? (
            <div className='flex min-h-180px items-center justify-center'>
              <Spin />
            </div>
          ) : failure ? (
            <div className='flex min-h-180px flex-col items-center justify-center gap-10px px-16px text-center'>
              <p role='alert' className='m-0 break-words text-13px leading-18px text-danger-6'>
                {failure}
              </p>
              <Button size='small' onClick={() => void load()}>
                {t('conversation.hostTools.retry')}
              </Button>
            </div>
          ) : items && items.length === 0 ? (
            <div className='flex min-h-180px items-center justify-center'>
              <Empty description={t('conversation.hostTools.inboxEmpty')} />
            </div>
          ) : (
            items?.map((item, index) => {
              const openable = Boolean(item.conversationId);
              return (
                <div
                  key={`${item.conversationId}-${index}`}
                  role={openable ? 'button' : undefined}
                  tabIndex={openable ? 0 : undefined}
                  data-testid='host-inbox-item'
                  className={classNames(
                    'box-border flex flex-col gap-4px rounded-12px border border-solid border-[var(--color-border-2)] bg-fill-1 p-12px',
                    openable && 'cursor-pointer hover:bg-fill-2'
                  )}
                  onClick={() => {
                    if (openable) void navigate(`/conversation/${encodeURIComponent(item.conversationId)}`);
                  }}
                  onKeyDown={(event) => {
                    if (openable && (event.key === 'Enter' || event.key === ' ')) {
                      event.preventDefault();
                      void navigate(`/conversation/${encodeURIComponent(item.conversationId)}`);
                    }
                  }}
                >
                  <div className='flex min-w-0 flex-wrap items-center gap-6px'>
                    {item.reasons.map((reason) => (
                      <span
                        key={reason}
                        className={classNames(
                          'shrink-0 rounded-4px bg-fill-2 px-5px py-1px text-11px leading-16px font-medium',
                          KIND_CLASS[reason] ?? 'text-t-secondary'
                        )}
                      >
                        {KIND_KEYS[reason] ? t(KIND_KEYS[reason]) : reason}
                      </span>
                    ))}
                    {(item.pendingApprovals > 0 || item.pendingQuestions > 0) && (
                      <span className='shrink-0 text-11px leading-16px text-t-tertiary'>
                        {t('conversation.hostTools.inboxPending', {
                          approvals: item.pendingApprovals,
                          questions: item.pendingQuestions,
                        })}
                      </span>
                    )}
                    {item.updatedAt !== null && (
                      <span className='ms-auto shrink-0 text-11px leading-16px text-t-tertiary'>
                        {formatRelativeTime(item.updatedAt, i18n.language)}
                      </span>
                    )}
                  </div>
                  {item.summary && (
                    <span className='break-words text-13px leading-20px text-t-primary'>{item.summary}</span>
                  )}
                  {item.lastError && (
                    <span className='break-words text-12px leading-18px text-danger-6'>{item.lastError}</span>
                  )}
                  <span className='break-all text-11px leading-16px text-t-tertiary'>
                    {[hostLabelOf(item.hostId) ?? item.hostId, item.agent || ''].filter(Boolean).join(' · ')}
                  </span>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
};

export default HostInboxPage;
