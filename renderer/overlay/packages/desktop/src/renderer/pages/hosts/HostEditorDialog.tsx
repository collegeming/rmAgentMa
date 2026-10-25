/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import {
  HOST_AUTH_KEY,
  HOST_AUTH_PASSWORD,
  HOST_OS_LINUX,
  HOST_OS_WINDOWS,
  type SavedHost,
} from '@/common/host/hostTransport';
import { AionModal } from '@renderer/components/base';
import { Button, Input, Radio } from '@arco-design/web-react';
import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { createHost, hostFailureText, updateHost } from './hostsApi';
import {
  DRAFT_PROBLEM_KEYS,
  draftFromHost,
  draftProblem,
  emptyDraft,
  hostFields,
  validWslDistribution,
  type HostDraft,
} from './hostModel';

type Props = {
  visible: boolean;
  /** The host being edited, or null when the editor creates a new one. */
  host: SavedHost | null;
  onClose: () => void;
  onSaved: (saved: SavedHost) => void;
};

/** One labelled field; the whole row is the label, so a tap on the text reaches the control. */
const field = (label: string, control: React.ReactNode) => (
  <label className='flex flex-col gap-6px'>
    <span className='text-13px leading-18px text-t-secondary'>{label}</span>
    {control}
  </label>
);

const HostEditorDialog: React.FC<Props> = ({ visible, host, onClose, onSaved }) => {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<HostDraft>(emptyDraft);
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!visible) return;
    setDraft(host ? draftFromHost(host) : emptyDraft());
    setFailure(null);
    setSaving(false);
  }, [visible, host]);

  const patch = (values: Partial<HostDraft>) => setDraft((current) => ({ ...current, ...values }));
  const distributionValid = validWslDistribution(draft.distribution.trim());

  const save = async () => {
    const blocked = draftProblem(draft, Boolean(host));
    if (blocked) {
      setFailure(t(DRAFT_PROBLEM_KEYS[blocked.kind]));
      return;
    }
    setSaving(true);
    setFailure(null);
    try {
      const fields = hostFields(draft);
      onSaved(host ? await updateHost(host.id, fields) : await createHost(fields));
    } catch (error) {
      setFailure(hostFailureText(error, t));
    } finally {
      setSaving(false);
    }
  };

  return (
    <AionModal
      visible={visible}
      onCancel={onClose}
      maskClosable={false}
      autoFocus={false}
      style={{ width: 'min(560px, 94vw)' }}
      header={{ title: host ? t('settings.hosts.editTitle') : t('settings.hosts.createTitle') }}
      footer={
        <div className='flex justify-end gap-10px'>
          <Button className='min-w-80px' disabled={saving} onClick={onClose}>
            {t('settings.hosts.cancel')}
          </Button>
          <Button type='primary' className='min-w-80px' loading={saving} disabled={saving} onClick={() => void save()}>
            {saving ? t('settings.hosts.saving') : t('settings.hosts.save')}
          </Button>
        </div>
      }
    >
      <div className='box-border flex flex-col gap-14px p-20px'>
        {field(
          t('settings.hosts.nickname'),
          <Input
            aria-label={t('settings.hosts.nickname')}
            value={draft.nickname}
            onChange={(value) => patch({ nickname: value })}
          />
        )}
        {field(
          t('settings.hosts.address'),
          <Input
            aria-label={t('settings.hosts.address')}
            placeholder='192.168.1.10'
            value={draft.hostname}
            onChange={(value) => patch({ hostname: value })}
          />
        )}
        {field(
          t('settings.hosts.port'),
          <Input
            aria-label={t('settings.hosts.port')}
            inputMode='numeric'
            value={draft.port}
            onChange={(value) => patch({ port: value })}
          />
        )}
        {field(
          t('settings.hosts.username'),
          <Input
            aria-label={t('settings.hosts.username')}
            value={draft.username}
            onChange={(value) => patch({ username: value })}
          />
        )}
        {field(
          t('settings.hosts.osType'),
          <Radio.Group
            aria-label={t('settings.hosts.osType')}
            value={draft.osType}
            onChange={(value: string) =>
              patch({ osType: value, target: value === HOST_OS_LINUX ? 'native' : draft.target })
            }
          >
            <Radio value={HOST_OS_LINUX}>{t('settings.hosts.osLinux')}</Radio>
            <Radio value={HOST_OS_WINDOWS}>{t('settings.hosts.osWindows')}</Radio>
          </Radio.Group>
        )}
        {field(
          t('settings.hosts.agentTarget'),
          draft.osType === HOST_OS_WINDOWS ? (
            <div className='flex flex-col gap-8px'>
              <Radio.Group
                aria-label={t('settings.hosts.agentTarget')}
                value={draft.target}
                onChange={(value: 'native' | 'wsl') => patch({ target: value })}
              >
                <Radio value='native'>{t('settings.hosts.targetWindowsNative')}</Radio>
                <Radio value='wsl'>{t('settings.hosts.targetWsl')}</Radio>
              </Radio.Group>
              {draft.target === 'native' ? (
                // Windows itself is a real target now; whether an Agent answers there is the probe's question.
                <p className='m-0 text-12px leading-18px text-t-tertiary'>{t('settings.hosts.targetWindowsHint')}</p>
              ) : (
                <>
                  <Input
                    aria-label={t('settings.hosts.wslDistribution')}
                    placeholder='Ubuntu-22.04'
                    value={draft.distribution}
                    onChange={(value) => patch({ distribution: value })}
                  />
                  <p
                    className={
                      distributionValid
                        ? 'm-0 text-12px leading-18px text-t-tertiary'
                        : 'm-0 text-12px leading-18px text-danger-6'
                    }
                  >
                    {t(
                      distributionValid ? 'settings.hosts.wslDistributionHint' : 'settings.hosts.wslDistributionInvalid'
                    )}
                  </p>
                </>
              )}
            </div>
          ) : (
            <div className='flex flex-col gap-4px'>
              <span className='text-14px text-t-primary'>{t('settings.hosts.targetNative')}</span>
              <span className='text-12px leading-18px text-t-tertiary'>{t('settings.hosts.targetLinuxHint')}</span>
            </div>
          )
        )}
        {field(
          t('settings.hosts.authMethod'),
          <div className='flex flex-col gap-8px'>
            <Radio.Group
              aria-label={t('settings.hosts.authMethod')}
              value={draft.authMethod}
              onChange={(value: string) => patch({ authMethod: value })}
            >
              <Radio value={HOST_AUTH_KEY}>{t('settings.hosts.authKey')}</Radio>
              <Radio value={HOST_AUTH_PASSWORD}>{t('settings.hosts.authPassword')}</Radio>
            </Radio.Group>
            {draft.authMethod === HOST_AUTH_PASSWORD ? (
              <div className='flex flex-col gap-4px'>
                {/* The field is write-only: the stored password is never sent back, so an empty box keeps it. */}
                <Input.Password
                  aria-label={t('settings.hosts.password')}
                  autoComplete='new-password'
                  value={draft.password}
                  placeholder={host?.has_password ? t('settings.hosts.passwordKeep') : ''}
                  onChange={(value) => patch({ password: value })}
                />
                <p className='m-0 text-12px leading-18px text-t-tertiary'>
                  {host?.has_password ? t('settings.hosts.passwordStored') : t('settings.hosts.passwordHint')}
                </p>
              </div>
            ) : (
              <p className='m-0 text-12px leading-18px text-t-tertiary'>
                {host?.key_name || host?.key_id
                  ? t('settings.hosts.authKeyCurrent', { key: host.key_name || host.key_id })
                  : t('settings.hosts.authKeyHint')}
              </p>
            )}
          </div>
        )}
        {failure && (
          <p role='alert' className='m-0 break-words text-13px leading-18px text-danger-6'>
            {failure}
          </p>
        )}
      </div>
    </AionModal>
  );
};

export default HostEditorDialog;
