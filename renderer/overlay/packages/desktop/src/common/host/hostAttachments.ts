/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { TFunction } from 'i18next';
import { captureHostDocument, hostAction, HostActionError } from './hostTransport';

/**
 * This host receives attachments: `uploadHostAttachment` below is the whole entry side, and every upload
 * entry point in the renderer reads this switch before it offers one.
 */
export const HOST_ATTACHMENTS_SUPPORTED = true;

/** A stable code the failure itself carries, e.g. `HOST_TIMEOUT` or `FILE_TOO_LARGE`. */
const STABLE_CODE = /^[A-Z][A-Z0-9_]*/;

/**
 * The visible text of a failed upload: the host's own stable code and reason when it answered with one, the
 * transport's own code when the request never completed, and only then the generic upload failure. A failure
 * is never reported as a success.
 */
export function hostAttachmentFailureText(error: unknown, t: TFunction): string {
  if (error instanceof HostActionError)
    return t('conversation.hostTools.uploadFailed', {
      code: error.code || `HTTP ${error.status}`,
      reason: error.reason || t('settings.hosts.reasonUnknown'),
    });
  const message = error instanceof Error ? error.message : String(error);
  const code = STABLE_CODE.exec(message)?.[0];
  if (code && code !== 'HOST_ATTACHMENTS_UNSUPPORTED')
    return t('conversation.hostTools.uploadFailed', {
      code,
      reason: message.slice(code.length).replace(/^[:\s]+/, '') || t('settings.hosts.reasonUnknown'),
    });
  return t('common.fileAttach.failed');
}

export async function uploadHostAttachment(
  file: File,
  conversationId: string,
  onProgress?: (percent: number) => void,
  name = file.name,
  signal?: AbortSignal
): Promise<string> {
  // 边界兜底：开关关掉时，任何绕过入口的上传在这里被拒绝，且带一个稳定的错误码。
  // The boundary: with the switch off, any upload that slips past the entries is refused here with a stable
  // code instead of a confusing transport error.
  if (!HOST_ATTACHMENTS_SUPPORTED) throw new Error('HOST_ATTACHMENTS_UNSUPPORTED');
  const documentAlive = captureHostDocument();
  const check = () => {
    if (signal?.aborted) throw new Error('Upload aborted');
    documentAlive();
  };
  check();
  if (file.size > 8 * 1024 * 1024) throw new Error('FILE_TOO_LARGE');
  const bytes = new Uint8Array(await file.arrayBuffer());
  check();
  const sha256 = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
  const started = await hostAction<{ uploadId: string; chunkBytes: number }>('attachment.begin', conversationId, {
    name,
    mime: file.type || 'text/plain',
    size: file.size,
    sha256,
  });
  let finished = false;
  try {
    check();
    if (
      !started.uploadId ||
      !Number.isSafeInteger(started.chunkBytes) ||
      started.chunkBytes < 1 ||
      started.chunkBytes > 12288
    )
      throw new Error('HOST_UPLOAD_INVALID');
    for (let offset = 0; offset < bytes.length; offset += started.chunkBytes) {
      check();
      const chunk = bytes.subarray(offset, Math.min(bytes.length, offset + started.chunkBytes));
      const data = btoa(String.fromCharCode(...chunk));
      const ack = await hostAction<{ offset: number }>('attachment.chunk', conversationId, {
        uploadId: started.uploadId,
        offset,
        data,
      });
      if (ack.offset !== offset + chunk.length) throw new Error('HOST_UPLOAD_OFFSET_INVALID');
      onProgress?.(Math.min(99, Math.round((ack.offset / bytes.length) * 100)));
    }
    check();
    const result = await hostAction<{ attachmentRef: string }>('attachment.finish', conversationId, {
      uploadId: started.uploadId,
    });
    finished = true;
    check();
    if (!result.attachmentRef) throw new Error('HOST_UPLOAD_INVALID');
    onProgress?.(100);
    return result.attachmentRef;
  } catch (error) {
    if (!finished)
      await hostAction('attachment.abort', conversationId, { uploadId: started.uploadId }).catch((): void => undefined);
    throw error;
  }
}
