/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { captureHostDocument, hostAction } from './hostTransport';

/**
 * Sending attachments is not part of this host yet. Every upload entry point in the renderer reads this and
 * says so, rather than offering a button whose request can only fail.
 */
export const HOST_ATTACHMENTS_SUPPORTED = false;

export async function uploadHostAttachment(
  file: File,
  conversationId: string,
  onProgress?: (percent: number) => void,
  name = file.name,
  signal?: AbortSignal
): Promise<string> {
  // 边界兜底：入口都已禁用并写明原因，任何绕过入口的上传在这里被拒绝，且带一个稳定的错误码。
  // The boundary: every entry point is disabled and says why, and any upload that slips past them is refused
  // here with a stable code instead of a confusing transport error.
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
