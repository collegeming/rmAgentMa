/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AcpModelSelector from '@/renderer/components/agent/AcpModelSelector';
const { setter, select, success, error } = vi.hoisted(() => ({
  setter: vi.fn(),
  select: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));
vi.mock('@/renderer/hooks/context/LayoutContext', () => ({ useLayoutContext: () => ({ isMobile: true }) }));
vi.mock('@/renderer/utils/model/agentLogo', () => ({
  getModelDisplayLabel: ({ selectedLabel }: { selectedLabel: string }) => selectedLabel,
}));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock('@/renderer/hooks/agent/useAcpModelInfo', () => ({
  useAcpModelInfo: () => ({
    model_info: {
      current_model_id: 'one',
      current_model_label: 'Current model',
      available_models: [
        { id: 'one', label: 'Current model' },
        { id: 'two', label: 'A long model name that must remain selectable on a narrow phone' },
      ],
    },
    canSwitch: true,
    isRuntimeReady: true,
    setStatus: { state: 'idle' },
    pendingValues: { model: 'two' },
    selectModel: select,
    setConfigOption: setter,
    thoughtLevel: {
      id: 'thought_level',
      currentValue: 'high',
      options: [
        { value: 'low', label: 'Low' },
        { value: 'high', label: 'High' },
      ],
    },
  }),
}));
vi.mock('@arco-design/web-react', async (original) => ({
  ...(await original<typeof import('@arco-design/web-react')>()),
  Message: { success, error },
}));
beforeEach(() => {
  setter.mockReset();
  success.mockClear();
  error.mockClear();
  select.mockClear();
});

describe('mobile header runtime settings', () => {
  it('opens a single column configuration sheet and does not close it before the setter resolves', async () => {
    let finish!: (value: unknown) => void;
    setter.mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      })
    );
    render(<AcpModelSelector conversation_id='conv-probe-1' />);
    fireEvent.click(screen.getByTestId('acp-model-selector'));
    expect(await screen.findByTestId('mobile-runtime-config')).toBeVisible();
    expect(screen.getByTestId('mobile-config-pending')).toHaveTextContent('agentMode.switchPendingNextTurn');
    fireEvent.click(screen.getByRole('button', { name: 'Low' }));
    expect(setter).toHaveBeenCalledWith('thought_level', 'low');
    expect(screen.getByTestId('mobile-runtime-config')).toBeVisible();
    await act(async () => finish([{ id: 'thought_level', current_value: 'high' }]));
    await waitFor(() => expect(setter).toHaveBeenCalledTimes(1));
    expect(success).not.toHaveBeenCalled();
  });

  it('exposes long model names as full-width buttons, without horizontal submenu navigation', async () => {
    render(<AcpModelSelector conversation_id='conv-probe-1' />);
    fireEvent.click(screen.getByTestId('acp-model-selector'));
    fireEvent.click(
      await screen.findByRole('button', { name: 'A long model name that must remain selectable on a narrow phone' })
    );
    expect(select).toHaveBeenCalledWith('two');
    expect(screen.getByTestId('mobile-runtime-config')).toBeVisible();
  });
});
