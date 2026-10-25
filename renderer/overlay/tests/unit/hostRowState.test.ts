/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { TChatConversation } from '@/common/config/storage';
import {
  activityTimeOf,
  conversationTitleOf,
  cwdNoteOf,
  isCwdMissing,
  isSessionRecoveryGone,
  isSessionRecoveryUnknown,
  recoveryOf,
  sessionIdOfConversationId,
} from '@renderer/host/hostRows';
import { describe, expect, it } from 'vitest';

/** `c1.<hostId>.<hex agent>.<hex sessionId>`, the id the host derives for one session. */
const conversationId = (sessionId: string, agent = 'claude') =>
  `c1.7.${[...new TextEncoder().encode(agent)].map((byte) => byte.toString(16).padStart(2, '0')).join('')}.${[
    ...new TextEncoder().encode(sessionId),
  ]
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')}`;

const row = (fields: Record<string, unknown>): TChatConversation => fields as unknown as TChatConversation;

describe('what a host row is allowed to call a title', () => {
  it('reads the session id back out of the renderer conversation id', () => {
    expect(sessionIdOfConversationId(conversationId('session-1'))).toBe('session-1');
    expect(sessionIdOfConversationId('c1.7.636c61756465.61')).toBe('a');
    expect(sessionIdOfConversationId('c1.7.636c61756465.616')).toBeNull();
    expect(sessionIdOfConversationId('conv-probe-1')).toBeNull();
    // A payload that is not UTF-8 round-tripping text is not a session id this host could have derived.
    expect(sessionIdOfConversationId('c1.7.636c61756465.ff')).toBeNull();
  });

  it('does not put an identifier or a directory where a title belongs', () => {
    const id = conversationId('session-1');
    expect(conversationTitleOf(row({ id, name: 'session-1' }))).toBe('');
    expect(
      conversationTitleOf(row({ id, name: '/home/me/work', workspace: { host_id: 'h', cwd: '/home/me/work' } }))
    ).toBe('');
    expect(conversationTitleOf(row({ id, name: 'work', workspace: { host_id: 'h', cwd: '/home/me/work' } }))).toBe('');
    expect(conversationTitleOf(row({ id, name: 'C:\\work' }))).toBe('');
    expect(conversationTitleOf(row({ id, name: '   ' }))).toBe('');
    expect(conversationTitleOf(row({ id }))).toBe('');
    expect(conversationTitleOf(row({ id, name: 'Fix the failing build' }))).toBe('Fix the failing build');
  });

  it('shows the time the row holds, and nothing at all while it holds none', () => {
    expect(activityTimeOf(row({ modified_at: 0, created_at: 0 }))).toBeNull();
    expect(activityTimeOf(row({ modified_at: 0, created_at: 1000 }))).toBe(1000);
    expect(activityTimeOf(row({ modified_at: 5000, created_at: 1000 }))).toBe(5000);
    expect(activityTimeOf(row({}))).toBeNull();
  });
});

describe('the facts a conversation row states about itself', () => {
  it('reads the missing working directory and the reason the host gave', () => {
    const missing = row({ cwd_missing: true, cwd_note: '目录已被删除' });
    expect(isCwdMissing(missing)).toBe(true);
    expect(cwdNoteOf(missing)).toBe('目录已被删除');
    expect(isCwdMissing(row({}))).toBe(false);
    expect(cwdNoteOf(row({}))).toBe('');
  });

  it('reads the session recovery the host stated, on the row or inside its runtime', () => {
    expect(recoveryOf(row({ recovery: { state: 'gone', note: '会话已删除' } }))).toEqual({
      state: 'gone',
      note: '会话已删除',
    });
    expect(recoveryOf(row({ runtime: { recovery: { state: 'unknown' } } }))).toEqual({ state: 'unknown', note: '' });
    expect(recoveryOf(row({ recovery: { state: '' } }))).toBeNull();
    expect(recoveryOf(row({}))).toBeNull();
  });

  it('never reads a session as attached unless the host said so', () => {
    expect(isSessionRecoveryGone(row({ recovery: { state: 'gone' } }))).toBe(true);
    expect(isSessionRecoveryUnknown(row({ recovery: { state: 'unknown' } }))).toBe(true);
    expect(isSessionRecoveryGone(row({ recovery: { state: 'attached' } }))).toBe(false);
    expect(isSessionRecoveryUnknown(row({}))).toBe(false);
  });
});
