/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  getCurrentConversation,
  setCurrentConversation,
} from '@renderer/pages/conversation/explorer/currentConversationStore';
import {
  hydrateSucceeded,
  resetConversationRuntimeViewStoreForTest,
} from '@renderer/pages/conversation/runtime/conversationRuntimeViewStore';
import { beginSwitch, openConversationBusy, resetPendingSwitch } from '@renderer/host/busySwitch';
import { markLiveQuestion, resetLiveQuestions } from '@renderer/host/pendingInteraction';
import HostSwitchGuard from '@renderer/host/HostSwitchGuard';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));

const CONVERSATION = 'c1.7.636c61756465.73657373696f6e2d31';

/** React's `act` hands back whatever the callback returned; the store's own answer is what this reads. */
const actResult = (change: () => boolean): boolean => {
  let value = false;
  act(() => {
    value = change();
  });
  return value;
};

const runtime = (fields: Record<string, unknown>) =>
  ({
    state: 'idle',
    can_send_message: true,
    has_task: false,
    is_processing: false,
    pending_confirmations: 0,
    task_status: 'finished',
    ...fields,
  }) as never;

beforeEach(() => {
  resetPendingSwitch();
  resetLiveQuestions();
  resetConversationRuntimeViewStoreForTest();
  setCurrentConversation(null);
});

afterEach(() => cleanup());

describe('the reading a switch away from the open conversation is decided by', () => {
  it('does nothing while that conversation is idle', () => {
    setCurrentConversation(CONVERSATION);
    hydrateSucceeded(CONVERSATION, runtime({}));
    const run = vi.fn();
    expect(openConversationBusy().running).toBe(false);
    expect(actResult(() => beginSwitch(run))).toBe(false);
    expect(run).not.toHaveBeenCalled();
    expect(screen.queryByTestId('host-switch-confirm')).not.toBeInTheDocument();
  });

  it('holds the switch while a turn runs, and runs it only when the user confirms', () => {
    setCurrentConversation(CONVERSATION);
    hydrateSucceeded(CONVERSATION, runtime({ state: 'running', is_processing: true, has_task: true }));
    render(<HostSwitchGuard />);

    const run = vi.fn();
    expect(actResult(() => beginSwitch(run))).toBe(true);
    expect(run).not.toHaveBeenCalled();
    expect(screen.getByTestId('host-switch-reason-switchBusyRunning')).toBeInTheDocument();
    expect(screen.getByTestId('host-switch-confirm')).toHaveTextContent('conversation.hostTools.switchBusyConfirm');

    fireEvent.click(screen.getByTestId('host-switch-confirm'));
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('drops the held switch when the user stays, so nothing is stopped', () => {
    setCurrentConversation(CONVERSATION);
    hydrateSucceeded(CONVERSATION, runtime({ is_processing: true }));
    render(<HostSwitchGuard />);
    const run = vi.fn();
    expect(actResult(() => beginSwitch(run))).toBe(true);

    fireEvent.click(screen.getByTestId('host-switch-cancel'));
    expect(run).not.toHaveBeenCalled();
    expect(screen.queryByTestId('host-switch-confirm')).not.toBeInTheDocument();
    expect(openConversationBusy().running).toBe(true);
  });

  it('counts a drawn question and a pending approval as work in progress', () => {
    setCurrentConversation(CONVERSATION);
    hydrateSucceeded(CONVERSATION, runtime({ pending_confirmations: 1 }));
    render(<HostSwitchGuard />);
    expect(actResult(() => beginSwitch(vi.fn()))).toBe(true);
    expect(screen.getByTestId('host-switch-reason-switchBusyApproval')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('host-switch-cancel'));

    resetConversationRuntimeViewStoreForTest();
    hydrateSucceeded(CONVERSATION, runtime({}));
    let release = () => {};
    act(() => {
      release = markLiveQuestion(CONVERSATION);
    });
    expect(openConversationBusy().question).toBe(true);
    expect(actResult(() => beginSwitch(vi.fn()))).toBe(true);
    expect(screen.getByTestId('host-switch-reason-switchBusyQuestion')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('host-switch-cancel'));

    // An answered question releases its claim: the conversation is free again.
    act(() => {
      release();
    });
    expect(openConversationBusy().question).toBe(false);
    expect(actResult(() => beginSwitch(vi.fn()))).toBe(false);
  });

  it('names a new session as what it is, so the button does not claim a switch it will not make', () => {
    setCurrentConversation(CONVERSATION);
    hydrateSucceeded(CONVERSATION, runtime({ is_processing: true }));
    render(<HostSwitchGuard />);
    expect(actResult(() => beginSwitch(vi.fn(), 'newSession'))).toBe(true);
    expect(screen.getByTestId('host-switch-confirm')).toHaveTextContent(
      'conversation.hostTools.switchBusyNewSessionConfirm'
    );
  });

  it('never holds a switch when no conversation is open', () => {
    expect(getCurrentConversation()).toBeNull();
    expect(actResult(() => beginSwitch(vi.fn()))).toBe(false);
  });
});
