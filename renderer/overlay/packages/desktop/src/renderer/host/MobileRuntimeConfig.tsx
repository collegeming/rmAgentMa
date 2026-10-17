/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { AcpModelInfo } from '@/common/types/platform/acpTypes';
import type { AcpDerivedOption } from '@/renderer/hooks/agent/useAcpConfigOptions';
import { Button, Drawer } from '@arco-design/web-react';
import React from 'react';
import { useTranslation } from 'react-i18next';

type Props = {
  visible: boolean;
  onClose: () => void;
  models: AcpModelInfo;
  thoughtLevel: AcpDerivedOption | null;
  pendingValues: Record<string, string>;
  busy: boolean;
  selectModel: (value: string) => void;
  selectThought: (value: string) => Promise<void>;
};

export default function MobileRuntimeConfig({
  visible,
  onClose,
  models,
  thoughtLevel,
  pendingValues,
  busy,
  selectModel,
  selectThought,
}: Props) {
  const { t } = useTranslation();
  const section = (
    label: string,
    current: string | null,
    pending: string | undefined,
    options: { value: string; label: string }[],
    select: (value: string) => void
  ) => (
    <section className='flex flex-col gap-8px' aria-label={label}>
      <h3 className='text-t-primary'>{label}</h3>
      {pending && (
        <div role='status' data-testid='mobile-config-pending'>
          {t('agentMode.switchPendingNextTurn')}: {options.find((item) => item.value === pending)?.label ?? pending}
        </div>
      )}
      {options.map((item) => (
        <Button
          key={item.value}
          long
          disabled={busy || item.value === current}
          type={item.value === current ? 'primary' : 'secondary'}
          className='!h-auto !min-h-44px !whitespace-normal !break-words !text-left'
          onClick={() => select(item.value)}
        >
          {item.label}
        </Button>
      ))}
    </section>
  );
  return (
    <Drawer
      visible={visible}
      onCancel={onClose}
      placement='bottom'
      height='70vh'
      footer={null}
      title={t('common.model')}
    >
      <div data-testid='mobile-runtime-config' className='flex flex-col gap-16px overflow-y-auto'>
        {section(
          t('common.model'),
          models.current_model_id,
          pendingValues.model,
          models.available_models.map((item) => ({ value: item.id, label: item.label || item.id })),
          selectModel
        )}
        {thoughtLevel &&
          section(
            t('agent.thoughtLevel.label'),
            thoughtLevel.currentValue,
            pendingValues[thoughtLevel.id],
            thoughtLevel.options,
            (value) => {
              void selectThought(value);
            }
          )}
      </div>
    </Drawer>
  );
}
