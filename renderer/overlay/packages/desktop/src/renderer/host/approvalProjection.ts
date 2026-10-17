/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { IMessageAcpPermission, TMessage } from '@/common/chat/chatLib';

export function approvalProjectionKey(message: TMessage): string | undefined {
  if (message.type !== 'acp_permission') return undefined;
  const { request_id, epoch } = message.content ?? {};
  if (
    !message.conversation_id ||
    !request_id ||
    !(Number.isSafeInteger(epoch) || (typeof epoch === 'string' && epoch.length > 0))
  )
    return undefined;
  return JSON.stringify([message.conversation_id, typeof epoch, epoch, request_id]);
}

export function mergeApprovalProjection(list: TMessage[], incoming: IMessageAcpPermission): TMessage[] {
  const key = approvalProjectionKey(incoming);
  if (!key || incoming.content.readonly) return list.concat(incoming);
  const candidates = list.filter(
    (message): message is IMessageAcpPermission =>
      message.type === 'acp_permission' && !message.content?.readonly && approvalProjectionKey(message) === key
  );
  const existing = candidates.find((message) => !message.id.startsWith('confirmation:')) ?? candidates[0];
  if (!existing) return list.concat(incoming);
  return list.flatMap((message) => {
    if (message === existing)
      return [{ ...existing, content: { ...existing.content, ...incoming.content, readonly: false } }];
    if (message.type === 'acp_permission' && !message.content?.readonly && approvalProjectionKey(message) === key)
      return [];
    return [message];
  });
}
