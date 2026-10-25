/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { SavedHost } from '@/common/host/hostTransport';
import { Button, Empty, Select, Spin } from '@arco-design/web-react';
import { EscapeHatchForm } from '@renderer/host/EscapeHatch';
import { getSelectedHostId, setSelectedHostId } from '@renderer/host/selectedHost';
import { hostFailureText, listHosts } from '@renderer/pages/hosts/hostsApi';
import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import SettingsPageWrapper from './components/SettingsPageWrapper';

/**
 * The settings half of the escape hatch: the same two entries as the conversation view, addressed to a
 * saved host instead of the open session. Every request is answered by the host — a refusal shows the code
 * the host returned, and a host that is not reachable is stated as itself.
 */
const EscapeHatchSettings: React.FC = () => {
  const { t } = useTranslation();
  const [hosts, setHosts] = useState<SavedHost[]>([]);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<string | null>(null);
  const [hostId, setHostId] = useState(() => getSelectedHostId() ?? '');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const rows = await listHosts();
      setHosts(rows);
      setHostId((current) => (rows.some((row) => row.id === current) ? current : (rows[0]?.id ?? '')));
      setFailure(null);
    } catch (error) {
      setFailure(hostFailureText(error, t));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <SettingsPageWrapper contentClassName='md:max-w-640px'>
      <h1 className='m-0 text-22px leading-[1.2] font-bold text-t-primary'>{t('settings.escape.title')}</h1>
      <p className='m-0 mt-6px text-13px leading-20px text-t-secondary'>{t('settings.escape.description')}</p>

      <div className='mt-16px flex flex-col gap-14px'>
        {loading ? (
          <div className='flex min-h-160px items-center justify-center'>
            <Spin />
          </div>
        ) : failure ? (
          <div className='flex min-h-160px flex-col items-center justify-center gap-10px rounded-16px border border-dashed border-border-2 bg-fill-1 px-16px text-center'>
            <p role='alert' className='m-0 break-words text-13px leading-18px text-danger-6'>
              {failure}
            </p>
            <Button size='small' onClick={() => void load()}>
              {t('settings.hosts.retry')}
            </Button>
          </div>
        ) : hosts.length === 0 ? (
          <div className='flex min-h-160px items-center justify-center rounded-16px border border-dashed border-border-2 bg-fill-1 px-16px text-center'>
            <Empty description={t('settings.escape.hostEmpty')} />
          </div>
        ) : (
          <>
            <div className='flex flex-col gap-4px'>
              <label className='text-13px text-t-secondary' htmlFor='escape-hatch-host'>
                {t('settings.escape.host')}
              </label>
              <Select
                id='escape-hatch-host'
                value={hostId || undefined}
                placeholder={t('settings.escape.host')}
                onChange={(value) => {
                  const next = typeof value === 'string' ? value : '';
                  setHostId(next);
                  setSelectedHostId(next || null);
                }}
                options={hosts.map((row) => ({ label: row.nickname || row.hostname, value: row.id }))}
              />
            </div>
            {hostId ? (
              <EscapeHatchForm
                key={hostId}
                target={{ hostId }}
                defaultCwd={null}
                onDone={() => setSelectedHostId(hostId)}
              />
            ) : (
              <p className='m-0 text-13px text-t-secondary'>{t('settings.escape.hostRequired')}</p>
            )}
          </>
        )}
      </div>
    </SettingsPageWrapper>
  );
};

export default EscapeHatchSettings;
