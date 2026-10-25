/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { BackendHttpError } from '@/common/adapter/httpBridge';
import { CWD_MISSING, cwdMissingSendReason, cwdRefusalText } from '@renderer/host/hostRefusal';
import type { TFunction } from 'i18next';
import { describe, expect, it } from 'vitest';

const t = ((key: string, options?: Record<string, unknown>) =>
  options ? `${key}(${Object.values(options).join('|')})` : key) as unknown as TFunction;

const refusal = (code: string, error: string) =>
  new BackendHttpError({
    method: 'POST',
    path: '/api/conversations/conv-probe-1/messages',
    status: 409,
    body: { code, error },
  });

describe('the refusal of a send into a directory the host cannot use', () => {
  it("shows the host's own code and reason for that one code", () => {
    expect(cwdRefusalText(refusal(CWD_MISSING, '目录已被删除。'), t)).toBe(
      'conversation.hostTools.requestRefused(CWD_MISSING|目录已被删除。)'
    );
    // Every other refusal keeps the message it already had.
    expect(cwdRefusalText(refusal('TURN_BUSY', '上一个回合尚未结束。'), t)).toBeNull();
    expect(cwdRefusalText(new Error('boom'), t)).toBeNull();
  });

  it('says why the send box is held back, with the code and the reason the row carried', () => {
    expect(cwdMissingSendReason('目录已被删除', t)).toBe(
      'conversation.hostTools.cwdMissingSendRefused(CWD_MISSING|目录已被删除)'
    );
    expect(cwdMissingSendReason('', t)).toBe('conversation.hostTools.cwdMissingSendRefusedNoNote(CWD_MISSING)');
  });
});
