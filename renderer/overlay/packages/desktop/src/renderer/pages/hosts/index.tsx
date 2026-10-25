/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { SavedHost } from '@/common/host/hostTransport';
import { Button, Empty, Spin } from '@arco-design/web-react';
import { useLayoutContext } from '@renderer/hooks/context/LayoutContext';
import classNames from 'classnames';
import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import HostCard from './HostCard';
import HostEditorDialog from './HostEditorDialog';
import { deleteHost, hostFailureText, listHosts, refreshDetection } from './hostsApi';

/**
 * The host page: the product's own saved SSH hosts, each with the Agent target it runs and the probe verdict
 * that host already holds. Every host request of this page is one the host itself answers — a refusal shows
 * the code and reason it returned, and a failed probe keeps the verdicts that were there before.
 */
const HostsPage: React.FC = () => {
  const { t } = useTranslation();
  const layout = useLayoutContext();
  const isMobile = layout?.isMobile ?? false;
  const [hosts, setHosts] = useState<SavedHost[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailure, setLoadFailure] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState<Record<string, boolean>>({});
  const [failures, setFailures] = useState<Record<string, string>>({});
  const [editor, setEditor] = useState<{ visible: boolean; host: SavedHost | null }>({ visible: false, host: null });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setHosts(await listHosts());
      setLoadFailure(null);
    } catch (error) {
      setLoadFailure(hostFailureText(error, t));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const setFailure = (hostId: string, text: string | null) =>
    setFailures((current) => {
      const next = { ...current };
      if (text) next[hostId] = text;
      else delete next[hostId];
      return next;
    });

  const probe = async (host: SavedHost) => {
    setRefreshing((current) => ({ ...current, [host.id]: true }));
    setFailure(host.id, null);
    try {
      const answer = await refreshDetection(host.id);
      if (!answer?.detection || !Array.isArray(answer.detection.agents)) throw new Error('HOST_DETECTION_INVALID');
      setHosts((current) =>
        current.map((row) =>
          row.id === host.id
            ? { ...row, target: answer.target, target_supported: answer.target_supported, detection: answer.detection }
            : row
        )
      );
    } catch (error) {
      // The row keeps what it showed before: a failed probe replaces no verdict with an invented one.
      setFailure(host.id, hostFailureText(error, t));
    } finally {
      setRefreshing((current) => ({ ...current, [host.id]: false }));
    }
  };

  const remove = async (host: SavedHost) => {
    setFailure(host.id, null);
    try {
      await deleteHost(host.id);
      setHosts((current) => current.filter((row) => row.id !== host.id));
    } catch (error) {
      setFailure(host.id, hostFailureText(error, t));
    }
  };

  const saved = (host: SavedHost) => {
    setHosts((current) =>
      current.some((row) => row.id === host.id)
        ? current.map((row) => (row.id === host.id ? host : row))
        : [...current, host]
    );
    setEditor({ visible: false, host: null });
  };

  return (
    <div className='box-border flex h-full w-full min-h-0 flex-col overflow-hidden bg-1'>
      <div
        className={classNames(
          'shrink-0 bg-1',
          isMobile ? 'px-16px pt-14px pb-14px' : 'px-12px pt-14px pb-14px md:px-40px md:pt-32px md:pb-16px'
        )}
      >
        {/* One column on a phone: the title, the description and the only action stack. */}
        <div className='mx-auto flex w-full max-w-800px flex-col gap-10px sm:flex-row sm:items-center sm:justify-between'>
          <div className='min-w-0'>
            <h1 className='m-0 text-22px leading-[1.2] font-bold text-t-primary'>{t('settings.hosts.title')}</h1>
            <p className='m-0 mt-4px text-13px leading-20px text-t-secondary'>{t('settings.hosts.description')}</p>
          </div>
          <Button
            type='primary'
            long={isMobile}
            className='shrink-0'
            onClick={() => setEditor({ visible: true, host: null })}
          >
            {t('settings.hosts.add')}
          </Button>
        </div>
      </div>

      <div
        className={classNames(
          'min-h-0 flex-1 overflow-y-auto overscroll-contain',
          isMobile ? 'px-16px pb-14px' : 'px-12px pb-24px md:px-40px md:pb-32px'
        )}
      >
        <div className='mx-auto flex w-full max-w-800px flex-col gap-12px'>
          {loading ? (
            <div className='flex min-h-220px items-center justify-center rounded-16px border border-dashed border-border-2 bg-fill-1'>
              <Spin />
            </div>
          ) : loadFailure ? (
            <div className='flex min-h-220px flex-col items-center justify-center gap-10px rounded-16px border border-dashed border-border-2 bg-fill-1 px-16px text-center'>
              <p role='alert' className='m-0 break-words text-13px leading-18px text-danger-6'>
                {loadFailure}
              </p>
              <Button size='small' onClick={() => void load()}>
                {t('settings.hosts.retry')}
              </Button>
            </div>
          ) : hosts.length === 0 ? (
            <div className='flex min-h-220px flex-col items-center justify-center gap-10px rounded-16px border border-dashed border-border-2 bg-fill-1 px-16px text-center'>
              <Empty description={t('settings.hosts.empty')} />
              <Button type='primary' onClick={() => setEditor({ visible: true, host: null })}>
                {t('settings.hosts.add')}
              </Button>
            </div>
          ) : (
            hosts.map((host) => (
              <HostCard
                key={host.id}
                host={host}
                refreshing={Boolean(refreshing[host.id])}
                failure={failures[host.id] ?? null}
                onRefresh={() => void probe(host)}
                onEdit={() => setEditor({ visible: true, host })}
                onDelete={() => void remove(host)}
              />
            ))
          )}
        </div>
      </div>

      <HostEditorDialog
        visible={editor.visible}
        host={editor.host}
        onClose={() => setEditor({ visible: false, host: null })}
        onSaved={saved}
      />
    </div>
  );
};

export default HostsPage;
