/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { Button, Input, Modal } from '@arco-design/web-react';
import { Terminal } from '@icon-park/react';
import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { openSshTerminal, startTuiCommand, type EscapeResult, type EscapeTarget } from './escapeHatch';
import { isAbsoluteRemotePath } from './remotePath';
import { hostFailureText } from '@/renderer/pages/hosts/hostsApi';

type EscapeRequest = (target: EscapeTarget, cwd: string | null) => Promise<EscapeResult>;

type FormProps = {
  target: EscapeTarget;
  defaultCwd?: string | null;
  /** Runs once the host accepted a request; a dialog closes here. */
  onDone?: () => void;
};

/**
 * The two escape hatches themselves: one SSH terminal, and the Agent's own TUI command started in a
 * terminal. Both take a working directory on the target host — typed, never picked from this machine,
 * because the directory belongs to the host the session runs on.
 */
export const EscapeHatchForm: React.FC<FormProps> = ({ target, defaultCwd, onDone }) => {
  const { t } = useTranslation();
  const [cwd, setCwd] = useState(defaultCwd ?? '');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [opened, setOpened] = useState<string | null>(null);

  useEffect(() => {
    setCwd(defaultCwd ?? '');
  }, [defaultCwd]);

  const run = useCallback(
    async (request: EscapeRequest, label: string) => {
      const path = cwd.trim();
      if (!path) {
        setOpened(null);
        setFailure(t('settings.escape.cwdRequired'));
        return;
      }
      if (!isAbsoluteRemotePath(path)) {
        setOpened(null);
        setFailure(t('settings.escape.cwdNotAbsolute'));
        return;
      }
      setBusy(true);
      setFailure(null);
      setOpened(null);
      try {
        await request(target, path);
        setOpened(label);
        onDone?.();
      } catch (error) {
        // A failure is shown as the host's own code and reason, never as a silent no-op.
        setFailure(hostFailureText(error, t));
      } finally {
        setBusy(false);
      }
    },
    [cwd, onDone, t, target]
  );

  return (
    <div className='flex flex-col gap-12px' data-testid='escape-hatch'>
      <div className='flex flex-col gap-4px'>
        <label className='text-13px text-t-secondary' htmlFor='escape-hatch-cwd'>
          {t('settings.escape.cwd')}
        </label>
        <Input
          id='escape-hatch-cwd'
          value={cwd}
          disabled={busy}
          placeholder={t('settings.escape.cwdPlaceholder')}
          onChange={setCwd}
        />
        <span className='text-12px text-t-tertiary'>{t('settings.escape.cwdHint')}</span>
      </div>
      <div className='flex flex-wrap items-center gap-8px'>
        <Button
          type='primary'
          loading={busy}
          onClick={() => void run(openSshTerminal, t('settings.escape.openTerminal'))}
        >
          {t('settings.escape.openTerminal')}
        </Button>
        <Button loading={busy} onClick={() => void run(startTuiCommand, t('settings.escape.startTui'))}>
          {t('settings.escape.startTui')}
        </Button>
      </div>
      {failure && (
        <p role='alert' className='m-0 break-words text-13px leading-18px text-danger-6'>
          {failure}
        </p>
      )}
      {opened && (
        <p role='status' className='m-0 break-words text-13px leading-18px text-t-secondary'>
          {t('settings.escape.opened', { action: opened })}
        </p>
      )}
    </div>
  );
};

type DialogProps = FormProps & { visible: boolean; onClose: () => void; title?: string };

export const EscapeHatchDialog: React.FC<DialogProps> = ({ visible, onClose, title, target, defaultCwd }) => {
  const { t } = useTranslation();

  return (
    <Modal
      visible={visible}
      onCancel={onClose}
      footer={null}
      title={title ?? t('settings.escape.title')}
      className='w-[min(560px,calc(100vw-32px))]'
    >
      {/* Mounted only while open, so every opening starts from the target's own working directory. */}
      {visible && <EscapeHatchForm target={target} defaultCwd={defaultCwd} onDone={onClose} />}
    </Modal>
  );
};

type TriggerProps = FormProps & { label?: string };

/** The conversation view's entry: one header button that opens both escape hatches for this session. */
export const EscapeHatchTrigger: React.FC<TriggerProps> = ({ label, target, defaultCwd }) => {
  const { t } = useTranslation();
  const [visible, setVisible] = useState(false);
  const title = label ?? t('settings.escape.title');

  return (
    <>
      <button
        type='button'
        className='app-titlebar__button app-titlebar__button--mobile'
        onClick={() => setVisible(true)}
        aria-label={title}
        title={title}
      >
        <Terminal theme='outline' size={18} fill='currentColor' className='block leading-none' />
      </button>
      <EscapeHatchDialog visible={visible} onClose={() => setVisible(false)} target={target} defaultCwd={defaultCwd} />
    </>
  );
};
