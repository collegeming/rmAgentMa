/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { httpRequest, wsEmitter } from '@/common/adapter/httpBridge';
import { useEffect, useState } from 'react';

type Approval = {
  request_id: string;
  conversation_id: string;
  epoch: number | string;
  options: { option_id: string }[];
};

export function matchesPendingApproval(
  value: unknown,
  conversationId: string,
  requestId: string | undefined,
  epoch: number | string | undefined
): value is Approval {
  if (
    !requestId ||
    !(Number.isSafeInteger(epoch) || (typeof epoch === 'string' && epoch.length > 0)) ||
    !value ||
    typeof value !== 'object'
  )
    return false;
  const item = value as Approval;
  return (
    item.request_id === requestId &&
    item.conversation_id === conversationId &&
    item.epoch === epoch &&
    Array.isArray(item.options)
  );
}

export async function readPendingApproval(
  conversationId: string,
  requestId: string | undefined,
  epoch: number | string | undefined
): Promise<Approval | undefined> {
  const items = await httpRequest<unknown[]>(
    'GET',
    `/api/conversations/${encodeURIComponent(conversationId)}/confirmations`
  );
  return items.find((item) => matchesPendingApproval(item, conversationId, requestId, epoch)) as Approval | undefined;
}

export async function submitPendingApproval(
  conversationId: string,
  requestId: string,
  epoch: number | string,
  msgId: string,
  option: string
): Promise<void> {
  const current = await readPendingApproval(conversationId, requestId, epoch);
  if (!current?.options.some((item) => item.option_id === option)) throw new Error('APPROVAL_NOT_PENDING');
  const result = await httpRequest<{ state?: string }>(
    'POST',
    `/api/conversations/${encodeURIComponent(conversationId)}/confirmations/${encodeURIComponent(requestId)}/confirm`,
    { msg_id: msgId, data: option, epoch }
  );
  if (result?.state === 'native_review_required') throw new Error('NATIVE_REVIEW_PENDING');
}

export function usePendingApproval(
  conversationId: string,
  requestId: string | undefined,
  epoch: number | string | undefined
): Approval | undefined {
  const [pending, setPending] = useState<Approval>();
  useEffect(() => {
    let active = true;
    let version = 0;
    const refresh = () => {
      const current = ++version;
      setPending(undefined);
      void readPendingApproval(conversationId, requestId, epoch)
        .then((item) => {
          if (active && current === version) setPending(item);
        })
        .catch(() => {
          if (active && current === version) setPending(undefined);
        });
    };
    refresh();
    const off = ['message.stream', 'confirmation.remove', 'turn.completed', 'realtime.reconnected'].map((name) =>
      wsEmitter(name).on(refresh)
    );
    const offline = () => {
      version++;
      setPending(undefined);
    };
    window.addEventListener('offline', offline);
    return () => {
      active = false;
      off.forEach((dispose) => dispose());
      window.removeEventListener('offline', offline);
    };
  }, [conversationId, requestId, epoch]);
  return pending;
}
