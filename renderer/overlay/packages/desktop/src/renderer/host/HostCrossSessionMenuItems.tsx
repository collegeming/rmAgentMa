/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { TChatConversation } from '@/common/config/storage';
import { useConversationListSync } from '@renderer/pages/conversation/GroupedHistory/hooks/useConversationListSync';
import { packReference, sendToQueue, type QueueKind } from '@renderer/host/hostActions';
import { hostAgentOf, hostIdOf, hostWorkspaceOf } from '@renderer/host/hostRows';
import { hostFailureText } from '@renderer/pages/hosts/hostsApi';
import { Button, Input, Menu, Message, Modal, Select } from '@arco-design/web-react';
import { MessageOne, Quote } from '@icon-park/react';
import React, { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';

type Props = { conversation: TChatConversation };

const itemClass = 'flex items-center gap-8px';

/**
 * The two cross-session actions of one conversation's menu.
 *
 * "Hand this to another session" queues a message for a target session instead of sending it: the host holds
 * it, and `notify` only shows it there while `request` sends it when that session is next opened.
 *
 * "Quote into a new conversation" asks the host to pack this conversation's context and opens the home page
 * with that text in the input box — filled in, never sent: the send button stays the user's.
 */
const HostCrossSessionMenuItems: React.FC<Props> = ({ conversation }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { conversations } = useConversationListSync();
  const [queueOpen, setQueueOpen] = useState(false);
  const [packing, setPacking] = useState(false);
  const [targetId, setTargetId] = useState('');
  const [kind, setKind] = useState<QueueKind>('notify');
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const targets = useMemo(
    () => conversations.filter((row) => row.id !== conversation.id),
    [conversations, conversation.id]
  );

  const target = targets.find((row) => row.id === targetId) ?? null;

  const openQueue = useCallback(() => {
    setFailure(null);
    setText('');
    setTargetId((current) => (current && targets.some((row) => row.id === current) ? current : (targets[0]?.id ?? '')));
    setQueueOpen(true);
  }, [targets]);

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
      setQueueOpen(false);
      Message.success(t('conversation.hostTools.queueSent', { target: target?.name ?? targetId }));
    } catch (error) {
      // The host's own code and reason stay on screen; nothing is reported as queued.
      setFailure(hostFailureText(error, t));
    } finally {
      setBusy(false);
    }
  }, [kind, t, target, targetId, text]);

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
        </div>
      </Modal>
    </>
  );
};

export default HostCrossSessionMenuItems;
