/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { Button, Empty, Input, Select, Spin } from '@arco-design/web-react';
import { Search } from '@icon-park/react';
import { loadHostDirectory, hostLabelOf, useHostDirectory } from '@renderer/host/hostDirectory';
import { searchSessions, type SearchCoverage, type SessionSearchHit } from '@renderer/host/hostActions';
import { HOST_FILTER_ALL } from '@renderer/host/HostHistoryFilterBar';
import { hostFailureText } from '@renderer/pages/hosts/hostsApi';
import { useLayoutContext } from '@renderer/hooks/context/LayoutContext';
import classNames from 'classnames';
import React, { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';

const joinParts = (parts: (string | null)[]): string => parts.filter(Boolean).join(' · ');

type SearchState = { hits: SessionSearchHit[]; coverage: SearchCoverage } | null;

/**
 * Content search across every saved host: the query goes to the host, which searches the sessions it indexes
 * and reports what it covered. The header says whether that coverage is complete, and names any host that
 * could not be searched — a partial answer is shown as partial, never as an empty result.
 */
const HostSearchPage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const layout = useLayoutContext();
  const isMobile = layout?.isMobile ?? false;
  const directory = useHostDirectory();
  const [query, setQuery] = useState('');
  const [hostId, setHostId] = useState(HOST_FILTER_ALL);
  const [agentId, setAgentId] = useState(HOST_FILTER_ALL);
  const [result, setResult] = useState<SearchState>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const agentIds = useMemo(() => {
    const ids = new Set<string>();
    for (const host of directory.hosts) {
      for (const agent of host.detection?.agents ?? []) ids.add(agent.id);
    }
    return [...ids];
  }, [directory.hosts]);

  const run = useCallback(async () => {
    const q = query.trim();
    if (!q) {
      setFailure(t('conversation.hostTools.searchQueryRequired'));
      return;
    }
    setBusy(true);
    setFailure(null);
    try {
      const answer = await searchSessions({
        q,
        hostId: hostId || undefined,
        agent: agentId || undefined,
        limit: 50,
      });
      setResult(answer);
    } catch (error) {
      setResult(null);
      setFailure(hostFailureText(error, t));
    } finally {
      setBusy(false);
    }
  }, [agentId, hostId, query, t]);

  const openHit = (hit: SessionSearchHit) => {
    if (!hit.conversationId || !hit.openable) return;
    void navigate(`/conversation/${encodeURIComponent(hit.conversationId)}`);
  };

  return (
    <div className='box-border flex h-full w-full min-h-0 flex-col overflow-hidden bg-1'>
      <div className={classNames('shrink-0 bg-1', isMobile ? 'px-16px pt-14px pb-10px' : 'px-40px pt-24px pb-10px')}>
        <div className='mx-auto flex w-full max-w-800px flex-col gap-10px'>
          <h1 className='m-0 text-20px leading-[1.2] font-bold text-t-primary'>
            {t('conversation.hostTools.searchTitle')}
          </h1>
          <Input
            value={query}
            size='large'
            prefix={<Search theme='outline' size='16' />}
            placeholder={t('conversation.hostTools.searchPlaceholder')}
            data-testid='host-search-input'
            onChange={setQuery}
            onPressEnter={() => void run()}
          />
          <div className='flex items-center gap-8px'>
            <Select
              size='small'
              className='min-w-0 flex-1'
              aria-label={t('conversation.hostTools.filterHost')}
              value={hostId}
              onVisibleChange={(visible) => {
                if (visible) void loadHostDirectory(t);
              }}
              onChange={(value) => setHostId(typeof value === 'string' ? value : HOST_FILTER_ALL)}
              getPopupContainer={() => document.body}
              options={[
                { label: t('conversation.hostTools.filterAll'), value: HOST_FILTER_ALL },
                ...directory.hosts.map((host) => ({ label: host.nickname || host.hostname, value: host.id })),
              ]}
            />
            <Select
              size='small'
              className='min-w-0 flex-1'
              aria-label={t('conversation.hostTools.filterAgent')}
              value={agentId}
              onChange={(value) => setAgentId(typeof value === 'string' ? value : HOST_FILTER_ALL)}
              getPopupContainer={() => document.body}
              options={[
                { label: t('conversation.hostTools.filterAll'), value: HOST_FILTER_ALL },
                ...agentIds.map((id) => ({ label: id, value: id })),
              ]}
            />
            <Button
              type='primary'
              size='small'
              loading={busy}
              data-testid='host-search-submit'
              onClick={() => void run()}
            >
              {t('conversation.hostTools.searchAction')}
            </Button>
          </div>
        </div>
      </div>

      <div
        className={classNames(
          'min-h-0 flex-1 overflow-y-auto overscroll-contain',
          isMobile ? 'px-16px pb-20px' : 'px-40px pb-24px'
        )}
      >
        <div className='mx-auto flex w-full max-w-800px flex-col gap-8px'>
          {failure && (
            <p role='alert' className='m-0 break-words text-13px leading-18px text-danger-6'>
              {failure}
            </p>
          )}
          {result && <SearchCoverageHeader coverage={result.coverage} />}
          {busy && !result ? (
            <div className='flex min-h-180px items-center justify-center'>
              <Spin />
            </div>
          ) : result && result.hits.length === 0 ? (
            <div className='flex min-h-180px items-center justify-center'>
              <Empty description={t('conversation.hostTools.searchEmpty')} />
            </div>
          ) : (
            result?.hits.map((hit, index) => {
              // The host decides openability: a hit whose conversation is no longer held is shown, not
              // made clickable, so no row here can only fail.
              const openable = Boolean(hit.conversationId) && hit.openable;
              return (
                <div
                  key={`${hit.conversationId || hit.title}-${index}`}
                  role={openable ? 'button' : undefined}
                  tabIndex={openable ? 0 : undefined}
                  className={classNames(
                    'box-border flex flex-col gap-4px rounded-12px border border-solid border-[var(--color-border-2)] bg-fill-1 p-12px',
                    openable && 'cursor-pointer hover:bg-fill-2'
                  )}
                  onClick={() => openHit(hit)}
                  onKeyDown={(event) => {
                    if (openable && (event.key === 'Enter' || event.key === ' ')) {
                      event.preventDefault();
                      openHit(hit);
                    }
                  }}
                >
                  <span className='break-words text-14px leading-20px font-medium text-t-primary'>
                    {hit.title || t('conversation.hostTools.searchUntitled')}
                  </span>
                  <span className='break-all text-11px leading-16px text-t-tertiary'>
                    {joinParts([hostLabelOf(hit.hostId) ?? (hit.hostId || null), hit.agent || null, hit.cwd || null])}
                  </span>
                  {hit.snippet && (
                    <span className='break-words text-12px leading-18px text-t-secondary'>{hit.snippet}</span>
                  )}
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
};

/** Whether the answer covered everything, and what stayed unread. */
const SearchCoverageHeader: React.FC<{ coverage: SearchCoverage }> = ({ coverage }) => {
  const { t } = useTranslation();
  const failed = coverage.failedSources
    .map((source) => joinParts([hostLabelOf(source.hostId) ?? source.hostId, source.reason || null]))
    .join(' | ');
  const complete = coverage.complete && !coverage.truncated;

  return (
    <div
      role='status'
      data-testid='host-search-coverage'
      className='flex flex-col gap-2px rounded-8px bg-fill-2 px-10px py-6px text-12px leading-18px text-t-secondary'
    >
      <span>
        {t(complete ? 'conversation.hostTools.searchCoverageComplete' : 'conversation.hostTools.searchCoveragePartial')}
      </span>
      {coverage.truncated && <span>{t('conversation.hostTools.searchCoverageTruncated')}</span>}
      {failed && (
        <span className='break-words'>{t('conversation.hostTools.searchCoverageFailed', { sources: failed })}</span>
      )}
      {coverage.problems.length > 0 && (
        <span className='break-words'>
          {t('conversation.hostTools.searchCoverageProblems', { problems: coverage.problems.join(', ') })}
        </span>
      )}
    </div>
  );
};

export default HostSearchPage;
