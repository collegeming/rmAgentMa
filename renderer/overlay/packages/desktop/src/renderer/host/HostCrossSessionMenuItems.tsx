/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { isBackendHttpError } from '@/common/adapter/httpBridge';
import type { TChatConversation } from '@/common/config/storage';
import { useConversationListSync } from '@renderer/pages/conversation/GroupedHistory/hooks/useConversationListSync';
import {
  cancelQueuedMessage,
  listQueue,
  packReference,
  sendToQueue,
  type QueuedMessage,
  type QueueKind,
} from '@renderer/host/hostActions';
import { hostAgentOf, hostIdOf, hostWorkspaceOf } from '@renderer/host/hostRows';
import { hostFailureText } from '@renderer/pages/hosts/hostsApi';
import { formatRelativeTime } from '@/renderer/utils/chat/relativeTime';
import { Button, Input, Menu, Message, Modal, Popconfirm, Select, Spin } from '@arco-design/web-react';
import { MessageOne, Quote } from '@icon-park/react';
import React, { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';

type Props = { conversation: TChatConversation };

const itemClass = 'flex items-center gap-8px';

/**
 * The two cross-session actions of one conversation's menu, plus the messages still waiting for theirs.
 *
 * "Hand this to another session" queues a message for a target session instead of sending it: the host holds
 * it, and `notify` only shows it there while `request` sends it when that session is next opened. The same
 * dialog lists every message the host still holds in that queue, with a withdraw for each one it holds as
 * pending; a message its conversation already took is shown as that instead, with no withdraw offered.
 *
 * "Quote into a new conversation" asks the host to pack this conversation's context and opens the home page
 * with that text in the input box — filled in, never sent: the send button stays the user's.
 */
const HostCrossSessionMenuItems: React.FC<Props> = ({ conversation }) => {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const { conversations } = useConversationListSync();
  const [queueOpen, setQueueOpen] = useState(false);
  const [packing, setPacking] = useState(false);
  const [targetId, setTargetId] = useState('');
  const [kind, setKind] = useState<QueueKind>('notify');
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [queue, setQueue] = useState<QueuedMessage[] | null>(null);
  const [queueFailure, setQueueFailure] = useState<string | null>(null);
  const [loadingQueue, setLoadingQueue] = useState(false);
  const [withdrawing, setWithdrawing] = useState<Record<string, boolean>>({});
  const [rowFailure, setRowFailure] = useState<Record<string, string>>({});
  // A message the host refused to withdraw because its conversation already took it: no withdraw is offered
  // again for that row, and the host's own code and reason stay on it.
  const [taken, setTaken] = useState<Record<string, boolean>>({});

  const targets = useMemo(
    () => conversations.filter((row) => row.id !== conversation.id),
    [conversations, conversation.id]
  );

  const target = targets.find((row) => row.id === targetId) ?? null;

  const loadQueue = useCallback(async () => {
    setLoadingQueue(true);
    try {
      setQueue(await listQueue());
      setQueueFailure(null);
    } catch (error) {
      setQueue(null);
      setQueueFailure(hostFailureText(error, t));
    } finally {
      setLoadingQueue(false);
    }
  }, [t]);

  const targetName = useCallback(
    (row: QueuedMessage): string => {
      const held = conversations.find((item) => item.id === (row.conversationId || row.session));
      return held?.name || row.title || row.session || row.conversationId || row.queueId;
    },
    [conversations]
  );

  const kindLabel = (kindName: string): string =>
    kindName === 'request' || kindName === 'notify'
      ? t(kindName === 'request' ? 'conversation.hostTools.queueKindRequest' : 'conversation.hostTools.queueKindNotify')
      : kindName;

  const stateLabel = (row: QueuedMessage): string => {
    if (taken[row.queueId]) return t('conversation.hostTools.queueStateTaken');
    if (row.state === 'pending') return t('conversation.hostTools.queueStatePending');
    if (row.state === 'withdrawn') return t('conversation.hostTools.queueStateWithdrawn');
    // A state this build does not know is read as the host named it, never as a name of our own.
    return row.state;
  };

  const withdraw = useCallback(
    async (row: QueuedMessage) => {
      setWithdrawing((current) => ({ ...current, [row.queueId]: true }));
      setRowFailure((current) => {
        const next = { ...current };
        delete next[row.queueId];
        return next;
      });
      try {
        // The host answers `cancelled: true` only when it really withdrew the message; nothing else counts.
        await cancelQueuedMessage(row.queueId);
        setQueue(
          (rows) =>
            rows?.map((item) =>
              item.queueId === row.queueId ? { ...item, state: 'withdrawn', pending: false } : item
            ) ?? rows
        );
      } catch (error) {
        // The host's own code and reason stay on that row, and no withdrawal is reported.
        setRowFailure((current) => ({ ...current, [row.queueId]: hostFailureText(error, t) }));
        if (isBackendHttpError(error) && (error.code === 'QUEUE_NOT_PENDING' || error.code === 'QUEUE_NOT_FOUND'))
          setTaken((current) => ({ ...current, [row.queueId]: true }));
      } finally {
        setWithdrawing((current) => {
          const next = { ...current };
          delete next[row.queueId];
          return next;
        });
      }
    },
    [t]
  );

  const openQueue = useCallback(() => {
    setFailure(null);
    setText('');
    setRowFailure({});
    setTaken({});
    setTargetId((current) => (current && targets.some((row) => row.id === current) ? current : (targets[0]?.id ?? '')));
    setQueueOpen(true);
    void loadQueue();
  }, [loadQueue, targets]);

  const submitQueue = useCallback(async () => {
    const message = text.trim();
    if (!targetId) {
      setFailure(t('conversation.hostTools.queueTargetRequired'));
      return;
    }
    if (!message) {
      setFailure(t('conversation.hostTools.queueTextRequired'));
      return;
    }
    setBusy(true);
    setFailure(null);
    try {
      await sendToQueue({
        // The target conversation the product holds, plus the host and Agent it resolves to.
        conversationId: targetId,
        hostId: target ? (hostIdOf(target) ?? undefined) : undefined,
        agent: target ? (hostAgentOf(target) ?? undefined) : undefined,
        kind,
        text: message,
      });
      setText('');
      Message.success(t('conversation.hostTools.queueSent', { target: target?.name ?? targetId }));
      // What was just queued is what this dialog lists, so the list is read again rather than guessed at.
      await loadQueue();
    } catch (error) {
      // The host's own code and reason stay on screen; nothing is reported as queued.
      setFailure(hostFailureText(error, t));
    } finally {
      setBusy(false);
    }
  }, [kind, loadQueue, t, target, targetId, text]);

  const quoteIntoNewConversation = useCallback(async () => {
    setPacking(true);
    try {
      // The pack names its own host and directory, so the new session starts where the quoted one ran.
      const packed = await packReference(conversation.id);
      const workspace = hostWorkspaceOf(conversation);
      const hostId = packed.hostId || workspace?.hostId;
      const cwd = packed.cwd || workspace?.cwd;
      await navigate('/guid', {
        state: {
          prefillPrompt: packed.text,
          preservePrefillDraft: true,
          focusPrefill: true,
          ...(cwd ? { workspace: cwd } : {}),
          ...(hostId ? { hostId } : {}),
        },
      });
    } catch (error) {
      Message.error(hostFailureText(error, t));
    } finally {
      setPacking(false);
    }
  }, [conversation, navigate, t]);

  return (
    <>
      <Menu.Item key='queueSend' data-testid='conversation-menu-queue-send' onClick={openQueue}>
        <div className={itemClass}>
          <MessageOne theme='outline' size='14' />
          <span>{t('conversation.hostTools.queueMenu')}</span>
        </div>
      </Menu.Item>
      <Menu.Item
        key='referenceNewConversation'
        data-testid='conversation-menu-reference'
        disabled={packing}
        onClick={() => void quoteIntoNewConversation()}
      >
        <div className={itemClass}>
          <Quote theme='outline' size='14' />
          <span>{t('conversation.hostTools.referenceMenu')}</span>
        </div>
      </Menu.Item>

      <Modal
        visible={queueOpen}
        onCancel={() => setQueueOpen(false)}
        title={t('conversation.hostTools.queueTitle')}
        footer={null}
      >
        <div className='flex flex-col gap-12px' data-testid='queue-send-dialog'>
          <div className='flex flex-col gap-4px'>
            <label className='text-13px text-t-secondary' htmlFor='queue-target'>
              {t('conversation.hostTools.queueTarget')}
            </label>
            <Select
              id='queue-target'
              value={targetId || undefined}
              disabled={busy}
              placeholder={t('conversation.hostTools.queueTarget')}
              onChange={(value) => setTargetId(typeof value === 'string' ? value : '')}
              options={targets.map((row) => ({ label: row.name, value: row.id }))}
            />
            {targets.length === 0 && (
              <span className='text-12px text-t-tertiary'>{t('conversation.hostTools.queueTargetNone')}</span>
            )}
          </div>
          <div className='flex flex-col gap-4px'>
            <label className='text-13px text-t-secondary' htmlFor='queue-kind'>
              {t('conversation.hostTools.queueKind')}
            </label>
            <Select
              id='queue-kind'
              value={kind}
              disabled={busy}
              onChange={(value) => setKind(value === 'request' ? 'request' : 'notify')}
              options={[
                { label: t('conversation.hostTools.queueKindNotify'), value: 'notify' },
                { label: t('conversation.hostTools.queueKindRequest'), value: 'request' },
              ]}
            />
            {/* What the chosen kind means, in the host's own terms. */}
            <span className='text-12px leading-16px text-t-tertiary'>
              {t(
                kind === 'request'
                  ? 'conversation.hostTools.queueKindRequestHint'
                  : 'conversation.hostTools.queueKindNotifyHint'
              )}
            </span>
          </div>
          <div className='flex flex-col gap-4px'>
            <label className='text-13px text-t-secondary' htmlFor='queue-text'>
              {t('conversation.hostTools.queueText')}
            </label>
            <Input.TextArea
              id='queue-text'
              value={text}
              disabled={busy}
              autoSize={{ minRows: 2, maxRows: 6 }}
              placeholder={t('conversation.hostTools.queueTextPlaceholder')}
              onChange={setText}
            />
          </div>
          {failure && (
            <p role='alert' className='m-0 break-words text-13px leading-18px text-danger-6'>
              {failure}
            </p>
          )}
          <div className='flex justify-end gap-8px'>
            <Button onClick={() => setQueueOpen(false)} disabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button type='primary' loading={busy} data-testid='queue-send-submit' onClick={() => void submitQueue()}>
              {t('conversation.hostTools.queueSend')}
            </Button>
          </div>

          {/* What the host still holds in this queue, and the withdraw of whatever it holds as pending. */}
          <div className='flex flex-col gap-6px border-t border-solid border-[var(--color-border-2)] pt-12px'>
            <span className='text-13px text-t-secondary'>{t('conversation.hostTools.queuePending')}</span>
            {loadingQueue && queue === null ? (
              <div className='flex min-h-40px items-center justify-center'>
                <Spin />
              </div>
            ) : queueFailure ? (
              <div className='flex flex-col gap-6px'>
                <p role='alert' className='m-0 break-words text-13px leading-18px text-danger-6'>
                  {queueFailure}
                </p>
                <Button size='mini' className='self-start' data-testid='queue-reload' onClick={() => void loadQueue()}>
                  {t('conversation.hostTools.retry')}
                </Button>
              </div>
            ) : queue && queue.length === 0 ? (
              <span className='text-12px leading-16px text-t-tertiary'>
                {t('conversation.hostTools.queuePendingEmpty')}
              </span>
            ) : (
              queue?.map((row) => {
                const withdrawable = row.pending && !taken[row.queueId];
                return (
                  <div
                    key={row.queueId}
                    data-testid='queue-pending-item'
                    className='flex flex-col gap-4px rounded-8px bg-fill-2 p-10px'
                  >
                    <div className='flex min-w-0 flex-wrap items-center gap-6px'>
                      <span className='min-w-0 flex-1 truncate text-13px leading-18px text-t-primary'>
                        {`${t('conversation.hostTools.queueTarget')}：${targetName(row)}`}
                      </span>
                      {row.kind && (
                        <span className='shrink-0 text-11px leading-16px text-t-tertiary'>{kindLabel(row.kind)}</span>
                      )}
                      {stateLabel(row) && (
                        <span className='shrink-0 text-11px leading-16px text-t-tertiary'>{stateLabel(row)}</span>
                      )}
                    </div>
                    <span className='break-words text-13px leading-20px text-t-primary'>{row.text}</span>
                    {row.createdAt !== null && (
                      <span className='text-11px leading-16px text-t-tertiary'>
                        {formatRelativeTime(row.createdAt, i18n.language)}
                      </span>
                    )}
                    {rowFailure[row.queueId] && (
                      <p role='alert' className='m-0 break-words text-12px leading-18px text-danger-6'>
                        {rowFailure[row.queueId]}
                      </p>
                    )}
                    {withdrawable ? (
                      <Popconfirm
                        title={t('conversation.hostTools.queueWithdraw')}
                        content={t('conversation.hostTools.queueWithdrawConfirm')}
                        okText={t('conversation.hostTools.queueWithdraw')}
                        cancelText={t('common.cancel')}
                        onOk={() => withdraw(row)}
                      >
                        <Button
                          size='mini'
                          status='danger'
                          className='self-start'
                          loading={Boolean(withdrawing[row.queueId])}
                          disabled={Boolean(withdrawing[row.queueId])}
                          data-testid={`queue-withdraw-${row.queueId}`}
                        >
                          {t('conversation.hostTools.queueWithdraw')}
                        </Button>
                      </Popconfirm>
                    ) : (
                      // Nothing is offered for a message this conversation already took, and the reason says so.
                      <span className='text-11px leading-16px text-t-tertiary'>
                        {t('conversation.hostTools.queueNoWithdraw')}
                      </span>
                    )}
                  </div>
                );
              })
            )}
          </div>
        </div>
      </Modal>
    </>
  );
};

export default HostCrossSessionMenuItems;
