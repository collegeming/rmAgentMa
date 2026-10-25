/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { httpRequest } from '@/common/adapter/httpBridge';
import type { SavedHost } from '@/common/host/hostTransport';
import { Button, Input, Modal, Select, Tooltip } from '@arco-design/web-react';
import { Plus } from '@icon-park/react';
import { hostFailureText, listHosts } from '@/renderer/pages/hosts/hostsApi';
import { isAbsoluteRemotePath } from '@renderer/host/remotePath';
import { getSelectedHostId, setSelectedHostId } from '@renderer/host/selectedHost';
import type { SiderTooltipProps } from '@renderer/utils/ui/siderTooltip';
import classNames from 'classnames';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';

/** The identity a created conversation answers with; an answer without one is a failure, not a session. */
function conversationIdOf(answer: unknown): string {
  const row = answer as { id?: unknown; conversation_id?: unknown } | null;
  const id = typeof row?.id === 'string' ? row.id : typeof row?.conversation_id === 'string' ? row.conversation_id : '';
  if (!id) throw new Error('CONVERSATION_CREATE_INVALID');
  return id;
}

type NewConversationDialogProps = { visible: boolean; onClose: () => void };

/**
 * A new conversation is created host first: the host is chosen, then one of its probed Agents, then the
 * working directory on that host. The directory is typed rather than browsed, because this phone cannot
 * open a folder on the host; a relative path is refused here instead of on the far end.
 */
const NewConversationDialog: React.FC<NewConversationDialogProps> = ({ visible, onClose }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [hosts, setHosts] = useState<SavedHost[]>([]);
  const [loading, setLoading] = useState(true);
  const [hostId, setHostId] = useState(() => getSelectedHostId() ?? '');
  const [agentId, setAgentId] = useState('');
  const [cwd, setCwd] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    if (!visible) return undefined;
    let alive = true;
    setLoading(true);
    void listHosts()
      .then((rows) => {
        if (!alive) return;
        setHosts(rows);
        setHostId((current) => (rows.some((row) => row.id === current) ? current : (rows[0]?.id ?? '')));
        setFailure(null);
      })
      .catch((error) => {
        if (alive) setFailure(hostFailureText(error, t));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [visible, t]);

  // Only the Agents this host's own probe found runnable are offered; a host whose probe has not run has none.
  const agents = useMemo(() => {
    const host = hosts.find((row) => row.id === hostId);
    if (!host?.detection?.checked) return [];
    return host.detection.agents.filter((agent) => agent.state === 'available');
  }, [hosts, hostId]);

  useEffect(() => {
    setAgentId(agents[0]?.id ?? '');
  }, [agents]);

  const create = useCallback(async () => {
    const path = cwd.trim();
    if (!hostId) {
      setFailure(t('settings.escape.hostRequired'));
      return;
    }
    if (!agentId) {
      setFailure(t('settings.newConversation.agentNone'));
      return;
    }
    if (!path) {
      setFailure(t('settings.escape.cwdRequired'));
      return;
    }
    if (!isAbsoluteRemotePath(path)) {
      setFailure(t('settings.escape.cwdNotAbsolute'));
      return;
    }
    setBusy(true);
    setFailure(null);
    try {
      const answer = await httpRequest<unknown>('POST', '/api/conversations', {
        host_id: hostId,
        agent: agentId,
        cwd: path,
      });
      const id = conversationIdOf(answer);
      setSelectedHostId(hostId);
      setHosts([]);
      setCwd('');
      onClose();
      void navigate(`/conversation/${id}`);
    } catch (error) {
      // The host's own code and reason stay on screen; nothing is reported as created.
      setFailure(hostFailureText(error, t));
    } finally {
      setBusy(false);
    }
  }, [agentId, cwd, hostId, navigate, onClose, t]);

  return (
    <Modal visible={visible} onCancel={onClose} title={t('settings.newConversation.title')} footer={null}>
      <div className='flex flex-col gap-14px' data-testid='new-conversation'>
        <div className='flex flex-col gap-4px'>
          <label className='text-13px text-t-secondary' htmlFor='new-conversation-host'>
            {t('settings.escape.host')}
          </label>
          <Select
            id='new-conversation-host'
            value={hostId || undefined}
            disabled={busy || loading}
            placeholder={t('settings.escape.host')}
            onChange={(value) => setHostId(typeof value === 'string' ? value : '')}
            options={hosts.map((row) => ({ label: row.nickname || row.hostname, value: row.id }))}
          />
          {!loading && hosts.length === 0 && (
            <span className='text-12px text-t-tertiary'>{t('settings.escape.hostEmpty')}</span>
          )}
        </div>
        <div className='flex flex-col gap-4px'>
          <label className='text-13px text-t-secondary' htmlFor='new-conversation-agent'>
            {t('settings.newConversation.agent')}
          </label>
          <Select
            id='new-conversation-agent'
            value={agentId || undefined}
            disabled={busy || agents.length === 0}
            placeholder={t('settings.newConversation.agent')}
            onChange={(value) => setAgentId(typeof value === 'string' ? value : '')}
            options={agents.map((agent) => ({
              label: agent.version ? `${agent.id} · ${agent.version}` : agent.id,
              value: agent.id,
            }))}
          />
          {agents.length === 0 && (
            <span className='text-12px text-t-tertiary'>{t('settings.newConversation.agentNone')}</span>
          )}
        </div>
        <div className='flex flex-col gap-4px'>
          <label className='text-13px text-t-secondary' htmlFor='new-conversation-cwd'>
            {t('settings.escape.cwd')}
          </label>
          <Input
            id='new-conversation-cwd'
            value={cwd}
            disabled={busy}
            placeholder={t('settings.escape.cwdPlaceholder')}
            onChange={setCwd}
          />
          <span className='text-12px text-t-tertiary'>{t('settings.escape.cwdHint')}</span>
        </div>
        {failure && (
          <p role='alert' className='m-0 break-words text-13px leading-18px text-danger-6'>
            {failure}
          </p>
        )}
        <div className='flex justify-end gap-8px'>
          <Button onClick={onClose} disabled={busy}>
            {t('common.cancel')}
          </Button>
          <Button type='primary' loading={busy} onClick={() => void create()}>
            {t('settings.newConversation.create')}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

type SiderNewConversationEntryProps = {
  isMobile: boolean;
  collapsed: boolean;
  siderTooltipProps: SiderTooltipProps;
};

/** The conversation list's own new-conversation entry, beside the host list it creates the session on. */
const SiderNewConversationEntry: React.FC<SiderNewConversationEntryProps> = ({
  isMobile,
  collapsed,
  siderTooltipProps,
}) => {
  const { t } = useTranslation();
  const [visible, setVisible] = useState(false);
  const label = t('settings.newConversation.title');

  const entry = (
    <div
      className={classNames(
        'box-border group h-34px w-full flex items-center rd-0.5rem cursor-pointer shrink-0 transition-all text-t-primary',
        collapsed ? 'justify-center' : 'justify-start gap-8px ps-10px pe-8px',
        isMobile && 'sider-action-btn-mobile',
        'hover:bg-fill-3 active:bg-fill-4'
      )}
      onClick={() => setVisible(true)}
    >
      <span className='size-22px flex items-center justify-center shrink-0 text-t-primary'>
        <Plus theme='outline' size='16' fill='currentColor' className='block leading-none' style={{ lineHeight: 0 }} />
      </span>
      <span className='collapsed-hidden text-t-primary text-14px font-[500] leading-24px'>{label}</span>
    </div>
  );

  return (
    <>
      <Tooltip {...siderTooltipProps} content={label} position='right'>
        {entry}
      </Tooltip>
      <NewConversationDialog visible={visible} onClose={() => setVisible(false)} />
    </>
  );
};

export default SiderNewConversationEntry;
