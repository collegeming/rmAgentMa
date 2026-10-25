/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { Button, Modal } from '@arco-design/web-react';
import React from 'react';
import { useTranslation } from 'react-i18next';
import { cancelSwitch, takeConfirmedSwitch, usePendingSwitch, type BusyReading } from './busySwitch';

/** The reasons the held switch states, one line each, so the user reads what would stop. */
function reasonKeys(reading: BusyReading): string[] {
  const keys: string[] = [];
  if (reading.running) keys.push('conversation.hostTools.switchBusyRunning');
  if (reading.question) keys.push('conversation.hostTools.switchBusyQuestion');
  if (reading.approvals > 0) keys.push('conversation.hostTools.switchBusyApproval');
  return keys;
}

/**
 * The confirmation a switch away from a working conversation needs. Nothing the user asked for runs while the
 * question is open: the answer "stop and switch" runs the held action, and closing the dialog drops it, leaving
 * the user where they were. No cancel is sent from here — the switch itself is what stops the session, and only
 * the user's own button starts it.
 */
const HostSwitchGuard: React.FC = () => {
  const { t } = useTranslation();
  const pending = usePendingSwitch();

  if (!pending) return null;

  return (
    <Modal
      visible
      title={t('conversation.hostTools.switchBusyTitle')}
      onCancel={() => cancelSwitch()}
      closable
      maskClosable={false}
      autoFocus={false}
      style={{ width: 'min(480px, 92vw)' }}
      footer={
        <div className='flex justify-end gap-10px'>
          <Button data-testid='host-switch-cancel' onClick={() => cancelSwitch()}>
            {t('conversation.hostTools.switchBusyStay')}
          </Button>
          <Button
            type='primary'
            status='danger'
            data-testid='host-switch-confirm'
            onClick={() => {
              const confirmed = takeConfirmedSwitch();
              if (confirmed) confirmed.run();
            }}
          >
            {t(
              pending.kind === 'newSession'
                ? 'conversation.hostTools.switchBusyNewSessionConfirm'
                : 'conversation.hostTools.switchBusyConfirm'
            )}
          </Button>
        </div>
      }
    >
      <div className='flex flex-col gap-6px text-13px leading-20px text-t-secondary'>
        {reasonKeys(pending.reading).map((key) => (
          <p key={key} data-testid={`host-switch-reason-${key.split('.').pop()}`} className='m-0 break-words'>
            {t(key)}
          </p>
        ))}
        <p className='m-0 break-words text-t-tertiary'>{t('conversation.hostTools.switchBusyHint')}</p>
      </div>
    </Modal>
  );
};

export default HostSwitchGuard;
