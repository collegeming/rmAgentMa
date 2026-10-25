/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { TChatConversation } from '@/common/config/storage';
import { Button, Select } from '@arco-design/web-react';
import { loadHostDirectory, hostLabelOf, useHostDirectory } from '@renderer/host/hostDirectory';
import { refreshHostRow, useHostRefreshReading } from '@renderer/host/hostRefresh';
import { filterOptionIds } from '@renderer/host/hostRows';
import { probeLabelKey, probeReading } from '@renderer/pages/hosts/hostModel';
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
 *
 * Choosing one host is also what refreshes it: that host is probed again and its session index is rescanned,
 * so the rows this sidebar shows under it are the host's own. The verdicts it answered and the state of a
 * refresh that failed (with the reason it gave) are drawn right here, under the two selectors.
 */
const HostHistoryFilterBar: React.FC<Props> = ({ conversations, hostId, agentId, onHostChange, onAgentChange }) => {
  const { t } = useTranslation();
  const directory = useHostDirectory();
  const { hostIds, agentIds } = filterOptionIds(conversations);
  const failure = directory.failure;
  const selectedHost = directory.hosts.find((host) => host.id === hostId) ?? null;
  const reading = useHostRefreshReading(hostId);

  useEffect(() => {
    if (hostIds.length > 0) void loadHostDirectory(t);
  }, [hostIds.length, t]);

  const openHostSelector = useCallback(() => {
    void loadHostDirectory(t);
  }, [t]);

  const selectHost = useCallback(
    (value: string) => {
      onHostChange(value);
      // Naming one host refreshes that host; `All` names none and asks nothing of any host.
      if (value) void refreshHostRow(value, t);
    },
    [onHostChange, t]
  );

  const all = { label: t('conversation.hostTools.filterAll'), value: HOST_FILTER_ALL };
  const refreshing = reading.state === 'refreshing';

  return (
    <div className='flex flex-col gap-4px px-10px py-6px' data-testid='host-history-filters'>
      <div className='flex items-center gap-6px'>
        <Select
          size='mini'
          className='min-w-0 flex-1'
          aria-label={t('conversation.hostTools.filterHost')}
          value={hostId}
          onChange={(value) => selectHost(typeof value === 'string' ? value : HOST_FILTER_ALL)}
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
      {hostId && (
        <div
          data-testid='host-refresh-state'
          data-host-id={hostId}
          data-refresh-state={reading.state}
          className='flex min-w-0 flex-wrap items-center gap-6px text-11px leading-16px text-t-tertiary'
        >
          <span className='min-w-0 truncate'>{t('conversation.hostTools.refreshStateHost')}</span>
          {refreshing && <span className='shrink-0'>{t('settings.hosts.refreshing')}</span>}
          {!refreshing && reading.sessions !== null && (
            <span className='shrink-0' data-testid='host-refresh-sessions'>
              {t('settings.hosts.sessionCount', { total: reading.sessions })}
            </span>
          )}
          {/* One chip per Agent this host's own probe answered for; a host nobody probed keeps its own reading. */}
          {(selectedHost?.detection?.agents ?? []).map((probe) => (
            <span
              key={probe.id}
              data-testid={`host-refresh-probe-${probe.id}`}
              data-probe-state={probe.state}
              className='shrink-0 rounded-4px bg-fill-2 px-4px'
            >
              {probe.id}：{t(probeLabelKey(probeReading(probe, refreshing)))}
            </span>
          ))}
        </div>
      )}
      {hostId && reading.state === 'failed' && (
        <div role='alert' data-testid='host-refresh-failure' className='flex min-w-0 items-center gap-6px'>
          <span className='min-w-0 break-all text-11px leading-16px text-danger-6'>{reading.failure}</span>
          <Button
            size='mini'
            data-testid='host-refresh-retry'
            onClick={() => {
              // The verdicts the row already holds stay drawn: only a new answer replaces them.
              void refreshHostRow(hostId, t);
            }}
          >
            {t('conversation.hostTools.retry')}
          </Button>
        </div>
      )}
    </div>
  );
};

export default HostHistoryFilterBar;
