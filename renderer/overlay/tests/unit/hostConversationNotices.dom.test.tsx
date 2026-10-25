/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  HISTORY_COMPLETE,
  HISTORY_PARTIAL,
  HISTORY_RETRY_EVENT,
  HISTORY_UNAVAILABLE,
  publishHistoryReading,
  resetHistoryReadings,
} from '@/common/host/historyState';
import { HostCwdMissingNotice, HostHistoryNotice, HostRecoveryNotice } from '@renderer/host/HostConversationNotices';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) =>
      options ? `${key}(${Object.values(options).join('|')})` : key,
  }),
}));

beforeEach(() => resetHistoryReadings());
afterEach(() => cleanup());

describe('the history a page says the host could not read whole', () => {
  it('says so with the host reason and offers one retry of the same window', () => {
    publishHistoryReading('conv-probe-1', { state: HISTORY_PARTIAL, note: '索引缺一段' });
    const retried: string[] = [];
    const listener = (event: Event) =>
      retried.push((event as CustomEvent<{ conversation_id: string }>).detail.conversation_id);
    window.addEventListener(HISTORY_RETRY_EVENT, listener);

    render(<HostHistoryNotice conversationId='conv-probe-1' />);
    expect(screen.getByTestId('host-history-notice')).toHaveTextContent('conversation.hostTools.historyPartial');
    expect(screen.getByTestId('host-history-note')).toHaveTextContent('conversation.hostTools.historyNote(索引缺一段)');
    fireEvent.click(screen.getByTestId('host-history-retry'));
    expect(retried).toEqual(['conv-probe-1']);

    window.removeEventListener(HISTORY_RETRY_EVENT, listener);
  });

  it('names an unavailable history as unavailable, never as a complete one', () => {
    publishHistoryReading('conv-probe-1', { state: HISTORY_UNAVAILABLE, note: '' });
    render(<HostHistoryNotice conversationId='conv-probe-1' />);
    expect(screen.getByTestId('host-history-notice')).toHaveTextContent('conversation.hostTools.historyUnavailable');
    expect(screen.queryByTestId('host-history-note')).not.toBeInTheDocument();
  });

  it('draws nothing while the host holds the whole history, or never described it', () => {
    publishHistoryReading('conv-probe-1', { state: HISTORY_COMPLETE, note: '' });
    const complete = render(<HostHistoryNotice conversationId='conv-probe-1' />);
    expect(screen.queryByTestId('host-history-notice')).not.toBeInTheDocument();
    complete.unmount();

    // A history the host never described is not drawn as whole either: this strip is not a claim, and it is
    // also not drawn when nothing was said.
    render(<HostHistoryNotice conversationId='conv-probe-2' />);
    expect(screen.queryByTestId('host-history-notice')).not.toBeInTheDocument();
  });
});

describe('what became of the session behind a conversation', () => {
  it('states a session that is gone, and one whose execution state nobody knows', () => {
    const gone = render(<HostRecoveryNotice recovery={{ state: 'gone', note: '会话已删除' }} />);
    expect(screen.getByTestId('host-recovery-notice')).toHaveAttribute('data-recovery-state', 'gone');
    expect(screen.getByTestId('host-recovery-note')).toHaveTextContent(
      'conversation.hostTools.historyNote(会话已删除)'
    );
    gone.unmount();

    render(<HostRecoveryNotice recovery={{ state: 'unknown', note: '' }} />);
    expect(screen.getByTestId('host-recovery-notice')).toHaveAttribute('data-recovery-state', 'unknown');
    expect(screen.getByTestId('host-recovery-notice')).toHaveTextContent('conversation.hostTools.recoveryUnknown');
  });

  it('draws nothing for a session the host reports as attached', () => {
    render(<HostRecoveryNotice recovery={{ state: 'attached', note: '' }} />);
    expect(screen.queryByTestId('host-recovery-notice')).not.toBeInTheDocument();
  });
});

describe('a conversation whose working directory the host cannot use', () => {
  it('states the directory and the host reason above the chat', () => {
    render(
      <HostCwdMissingNotice
        conversation={{ id: 'conv-probe-1', cwd_missing: true, cwd_note: '目录已被删除' } as never}
      />
    );
    expect(screen.getByTestId('host-cwd-notice')).toHaveTextContent('conversation.hostTools.cwdMissing');
    expect(screen.getByTestId('host-cwd-note')).toHaveTextContent('目录已被删除');
  });

  it('draws nothing for a conversation whose directory is usable', () => {
    render(<HostCwdMissingNotice conversation={{ id: 'conv-probe-1' } as never} />);
    expect(screen.queryByTestId('host-cwd-notice')).not.toBeInTheDocument();
  });
});
