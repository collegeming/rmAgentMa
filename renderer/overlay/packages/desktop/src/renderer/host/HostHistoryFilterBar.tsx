/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { TChatConversation } from '@/common/config/storage';
import { Select } from '@arco-design/web-react';
import { loadHostDirectory, hostLabelOf, useHostDirectory } from '@renderer/host/hostDirectory';
import { filterOptionIds } from '@renderer/host/hostRows';
import React, { useCallback, useEffect } from 'react';
import { useTranslation } from 'react-i18next';

export const HOST_FILTER_ALL = '';

type Props = {
  conversations: TChatConversation[];
  hostId: string;
  agentId: string;
  onHostChange: (hostId: string) => void;
  onAgentChange: (agentId: string) => void;
};

/**
 * The sidebar's two filters: the host a workspace group runs on, and the Agent it runs. Both default to
 * `All`, which filters nothing at all — a named host hides the rows that name none, rather than guessing one.
 * The host list is read once the sidebar holds host rows at all: without it a group could not be titled with
 * the host it runs on. A list with no host row behind it costs no request.
 */
const HostHistoryFilterBar: React.FC<Props> = ({ conversations, hostId, agentId, onHostChange, onAgentChange }) => {
  const { t } = useTranslation();
  const directory = useHostDirectory();
  const { hostIds, agentIds } = filterOptionIds(conversations);
  const failure = directory.failure;

  useEffect(() => {
    if (hostIds.length > 0) void loadHostDirectory(t);
  }, [hostIds.length, t]);

  const openHostSelector = useCallback(() => {
    void loadHostDirectory(t);
  }, [t]);

  const all = { label: t('conversation.hostTools.filterAll'), value: HOST_FILTER_ALL };

  return (
    <div className='flex items-center gap-6px px-10px py-6px' data-testid='host-history-filters'>
      <Select
        size='mini'
        className='min-w-0 flex-1'
        aria-label={t('conversation.hostTools.filterHost')}
        value={hostId}
        onChange={(value) => onHostChange(typeof value === 'string' ? value : HOST_FILTER_ALL)}
        onVisibleChange={(visible) => {
          if (visible) openHostSelector();
        }}
        getPopupContainer={() => document.body}
        options={[all, ...hostIds.map((id) => ({ label: hostLabelOf(id) ?? id, value: id }))]}
      />
      <Select
        size='mini'
        className='min-w-0 flex-1'
        aria-label={t('conversation.hostTools.filterAgent')}
        value={agentId}
        onChange={(value) => onAgentChange(typeof value === 'string' ? value : HOST_FILTER_ALL)}
        getPopupContainer={() => document.body}
        options={[all, ...agentIds.map((id) => ({ label: id, value: id }))]}
      />
      {hostId && failure && (
        <span role='alert' className='min-w-0 truncate text-11px leading-16px text-danger-6'>
          {failure}
        </span>
      )}
    </div>
  );
};

export default HostHistoryFilterBar;
