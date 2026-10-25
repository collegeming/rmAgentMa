/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { SavedHost } from '@/common/host/hostTransport';
import { Button, Popconfirm } from '@arco-design/web-react';
import React from 'react';
import { useTranslation } from 'react-i18next';
import { capabilityLabels, osText, probeLabelKey, probeReading, sessionCountText, targetLabel, type HostProbeReading } from './hostModel';

type Props = {
  host: SavedHost;
  /** True while this host's own probe runs: its verdicts read as refreshing until the answer arrives. */
  refreshing: boolean;
  failure: string | null;
  onRefresh: () => void;
  onEdit: () => void;
  onDelete: () => void;
};

const readingClass = (reading: HostProbeReading): string =>
  reading === 'available' ? 'text-success' : reading === 'refreshing' ? 'text-t-secondary' : 'text-danger-6';

const badge = 'shrink-0 rounded-4px bg-fill-2 px-5px py-1px text-11px leading-16px text-t-secondary';

const HostCard: React.FC<Props> = ({ host, refreshing, failure, onRefresh, onEdit, onDelete }) => {
  const { t } = useTranslation();
  const target = targetLabel(host);

  return (
    <article
      data-testid={`host-${host.id}`}
      className='box-border flex flex-col gap-8px rounded-12px border border-solid border-[var(--color-border-2)] bg-fill-1 p-12px'
    >
      <div className='flex min-w-0 flex-wrap items-center gap-6px'>
        <h2 className='m-0 min-w-0 flex-1 truncate text-15px leading-22px font-medium text-t-primary'>
          {host.nickname}
        </h2>
        <span className={badge}>{osText(host.os_type, t)}</span>
        <span className={badge}>{t(target.key, target.values)}</span>
      </div>
      <div className='truncate text-12px leading-18px text-t-secondary'>
        {host.username ? `${host.username}@` : ''}
        {host.hostname}:{host.port}
      </div>
      <div className='flex flex-wrap items-center gap-6px'>
        <span className='text-11px leading-16px text-t-tertiary'>{sessionCountText(host, t)}</span>
        {/* A target this build cannot run an Agent in says so, instead of being offered as if it worked. */}
        {host.target_supported === false && (
          <span role='alert' className='text-11px leading-16px text-danger-6'>
            {t('settings.hosts.targetUnsupportedNote')}
          </span>
        )}
      </div>

      {host.detection.agents.length > 0 ? (
        <div className='flex flex-col gap-6px'>
          {host.detection.agents.map((agent) => {
            const reading = probeReading(agent, refreshing);
            const capabilities = capabilityLabels(agent.capabilities);
            return (
              <div key={agent.id} className='flex flex-col gap-2px'>
                <div className='flex flex-wrap items-center gap-6px'>
                  <span className='text-12px leading-18px font-medium text-t-primary'>{agent.id}</span>
                  <span className={`text-12px leading-18px ${readingClass(reading)}`}>{t(probeLabelKey(reading))}</span>
                </div>
                {agent.executable && (
                  <span className='break-all text-11px leading-16px text-t-tertiary'>
                    {t('settings.hosts.executable', { path: agent.executable })}
                  </span>
                )}
                {agent.version && (
                  <span className='break-all text-11px leading-16px text-t-tertiary'>
                    {t('settings.hosts.version', { version: agent.version })}
                  </span>
                )}
                <span className='flex flex-wrap items-center gap-4px' data-testid={`host-capabilities-${host.id}-${agent.id}`}>
                  <span className='text-11px leading-16px text-t-tertiary'>{t('settings.hosts.capabilities')}</span>
                  {capabilities.length > 0 ? (
                    capabilities.map((capability) => (
                      <span key={capability} className={badge}>
                        {capability}
                      </span>
                    ))
                  ) : (
                    // No capability is a dash, never a capability this build invented.
                    <span className='text-11px leading-16px text-t-tertiary'>{t('settings.hosts.valueNone')}</span>
                  )}
                </span>
                {/* The probe's own reason stays readable, and is never shown as a verdict of this refresh. */}
                {agent.error && !refreshing && (
                  <span className='break-all text-11px leading-16px text-danger-6'>{agent.error}</span>
                )}
              </div>
            );
          })}
        </div>
      ) : (
        <span className='text-12px leading-18px text-t-tertiary'>{t(probeLabelKey('unchecked'))}</span>
      )}

      {failure && (
        <p role='alert' className='m-0 break-words text-12px leading-18px text-danger-6'>
          {failure}
        </p>
      )}

      <div className='flex flex-wrap items-center gap-8px'>
        <Button size='small' loading={refreshing} disabled={refreshing} onClick={onRefresh}>
          {t(refreshing ? 'settings.hosts.refreshing' : 'settings.hosts.refresh')}
        </Button>
        <Button size='small' disabled={refreshing} onClick={onEdit}>
          {t('settings.hosts.edit')}
        </Button>
        <Popconfirm
          title={t('settings.hosts.delete')}
          content={t('settings.hosts.deleteConfirm', { nickname: host.nickname })}
          okText={t('settings.hosts.delete')}
          cancelText={t('settings.hosts.cancel')}
          onOk={onDelete}
        >
          <Button size='small' status='danger' disabled={refreshing}>
            {t('settings.hosts.delete')}
          </Button>
        </Popconfirm>
      </div>
    </article>
  );
};

export default HostCard;
