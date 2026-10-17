/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { matchesPendingApproval, submitPendingApproval, usePendingApproval } from '@/renderer/host/usePendingApproval';
import { useCompositionInput } from '@/renderer/hooks/chat/useCompositionInput';

const { request, listeners } = vi.hoisted(() => ({ request: vi.fn(), listeners: new Map<string, () => void>() }));
vi.mock('@/common/adapter/httpBridge', () => ({
  httpRequest: request,
  wsEmitter: (name: string) => ({
    on: (callback: () => void) => {
      listeners.set(name, callback);
      return () => {
        listeners.delete(name);
      };
    },
  }),
}));
const approval = {
  request_id: 'request-1',
  conversation_id: 'conv-probe-1',
  epoch: 'epoch-1',
  options: [{ option_id: 'allow-once' }],
};

beforeEach(() => {
  request.mockReset();
  listeners.clear();
});

describe('authoritative pending gate', () => {
  it('requires conversation, opaque request reference and runtime epoch', () => {
    expect(matchesPendingApproval(approval, 'conv-probe-1', 'request-1', 'epoch-1')).toBe(true);
    expect(matchesPendingApproval(approval, 'other', 'request-1', 'epoch-1')).toBe(false);
    expect(matchesPendingApproval(approval, 'conv-probe-1', 'request-1', 'old-epoch')).toBe(false);
    expect(matchesPendingApproval(approval, 'conv-probe-1', undefined, 'epoch-1')).toBe(false);
  });

  it('rechecks the live table immediately before submitting and preserves original option id', async () => {
    request.mockResolvedValueOnce([approval]).mockResolvedValueOnce({ state: 'answered' });
    await submitPendingApproval('conv-probe-1', 'request-1', 'epoch-1', 'message-1', 'allow-once');
    expect(request).toHaveBeenLastCalledWith(
      'POST',
      '/api/conversations/conv-probe-1/confirmations/request-1/confirm',
      { msg_id: 'message-1', data: 'allow-once', epoch: 'epoch-1' }
    );
  });

  it('does not authorize historical or expired requests and never falls back to a tool id', async () => {
    request.mockResolvedValue([]);
    await expect(
      submitPendingApproval('conv-probe-1', 'tool-call-1', 'epoch-1', 'message-1', 'allow-once')
    ).rejects.toThrow('APPROVAL_NOT_PENDING');
    expect(request).toHaveBeenCalledTimes(1);
  });

  it('does not report native review acceptance as a completed approval', async () => {
    request.mockResolvedValueOnce([approval]).mockResolvedValueOnce({ state: 'native_review_required' });
    await expect(
      submitPendingApproval('conv-probe-1', 'request-1', 'epoch-1', 'message-1', 'allow-once')
    ).rejects.toThrow('NATIVE_REVIEW_PENDING');
  });

  it('disables cached pending actions immediately on disconnect', async () => {
    request.mockResolvedValue([approval]);
    const { result } = renderHook(() => usePendingApproval('conv-probe-1', 'request-1', 'epoch-1'));
    await waitFor(() => expect(result.current).toEqual(approval));
    act(() => window.dispatchEvent(new Event('offline')));
    expect(result.current).toBeUndefined();
  });
});

describe('mobile composition input', () => {
  it('does not send composition-confirm Enter, keyCode 229 or plain mobile Enter', () => {
    const send = vi.fn();
    vi.stubGlobal('matchMedia', () => ({ matches: true }));
    const { result } = renderHook(() => useCompositionInput());
    const key = (extra = {}) =>
      ({
        key: 'Enter',
        shiftKey: false,
        ctrlKey: false,
        metaKey: false,
        keyCode: 13,
        nativeEvent: { isComposing: false },
        preventDefault: vi.fn(),
        ...extra,
      }) as unknown as React.KeyboardEvent;
    const handle = result.current.createKeyDownHandler(send);
    handle(key({ nativeEvent: { isComposing: true } }));
    handle(key({ keyCode: 229 }));
    handle(key());
    expect(send).not.toHaveBeenCalled();
    handle(key({ ctrlKey: true }));
    expect(send).toHaveBeenCalledTimes(1);
    vi.unstubAllGlobals();
  });
});
