// SPDX-License-Identifier: Apache-2.0
import { createHash, randomUUID } from 'node:crypto';

/**
 * The offline attachment store of the harness, mirroring the host library's own upload store
 * (`renderer/contracts/host-v1.json` extraActions `attachment.begin`, `attachment.chunk`,
 * `attachment.finish` and `attachment.abort`). The codes, limits and answer fields follow the host's
 * shared probe store so a check that passes offline names the same facts a device would.
 *
 * shortcut: bytes stay in memory and the host's 64 MiB storage budget is not modelled, add it when the
 * harness uploads large fixtures.
 */
export const CHUNK_BYTES = 12288;
export const MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024;
export const UPLOAD_WINDOW_MS = 300000;
export const MAX_PENDING_UPLOADS = 4;
export const ATTACHMENT_MIME_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'text/plain', 'application/pdf'];
export const ATTACHMENT_REFERENCE_PATTERN = /^probe-upload:[a-f0-9-]{36}$/;

export function attachmentProblem(status, code, message) {
  // The code leads the message so a check can name the refusal it expected without unwrapping the error.
  return Object.assign(new Error(message ? `${code}: ${message}` : code), { status, code });
}

/** One upload in progress: the bytes received so far, and the declaration the caller must match. */
class PendingUpload {
  constructor({ documentId, name, mime, size, sha256, deadline }) {
    this.documentId = documentId;
    this.name = name;
    this.mime = mime;
    this.size = size;
    this.sha256 = sha256;
    this.deadline = deadline;
    this.chunks = [];
    this.length = 0;
  }

  bytes() {
    return Buffer.concat(this.chunks);
  }
}

export class AttachmentStore {
  constructor({ now = () => Date.now() } = {}) {
    this.now = now;
    this.pending = new Map();
    this.finished = new Map();
  }

  /** `attachment.begin`: opens one upload and answers the window the caller may chunk into. */
  begin(documentId, body) {
    if (this.pending.size >= MAX_PENDING_UPLOADS) throw attachmentProblem(429, 'UPLOAD_LIMIT', 'Too many uploads are open');
    const name = body?.name;
    if (typeof name !== 'string' || !name || name.length > 160 || name.includes('/') || name.includes('\\') || /[\u0000-\u001f]/.test(name))
      throw attachmentProblem(400, 'INVALID_REQUEST', 'name must be one bounded file name');
    const mime = body?.mime;
    if (typeof mime !== 'string' || !ATTACHMENT_MIME_TYPES.includes(mime)) throw attachmentProblem(415, 'UNSUPPORTED_MIME', 'Unsupported attachment MIME type');
    const size = body?.size;
    if (!Number.isSafeInteger(size) || size < 0 || size > MAX_ATTACHMENT_BYTES) throw attachmentProblem(413, 'UPLOAD_TOO_LARGE', 'Attachment exceeds the upload limit');
    const sha256 = body?.sha256;
    if (typeof sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(sha256)) throw attachmentProblem(400, 'INVALID_REQUEST', 'sha256 must be 64 lowercase hex digits');
    const uploadId = randomUUID();
    this.pending.set(uploadId, new PendingUpload({ documentId, name, mime, size, sha256, deadline: this.now() + UPLOAD_WINDOW_MS }));
    return { uploadId, chunkBytes: CHUNK_BYTES, expiresInMs: UPLOAD_WINDOW_MS };
  }

  /** `attachment.chunk`: appends one base64 chunk at the offset the caller holds, and echoes the new offset. */
  chunk(documentId, body) {
    const upload = this.find(documentId, body);
    const offset = body?.offset;
    if (!Number.isSafeInteger(offset) || offset !== upload.length) throw attachmentProblem(409, 'UPLOAD_OFFSET_CONFLICT', 'Chunk offset does not continue this upload');
    const data = body?.data;
    if (typeof data !== 'string' || data.length > 22000) throw attachmentProblem(400, 'INVALID_REQUEST', 'data must be one base64 chunk');
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(data)) throw attachmentProblem(400, 'INVALID_CHUNK', 'data is not base64');
    const bytes = Buffer.from(data, 'base64');
    if (!bytes.length || bytes.length > CHUNK_BYTES || upload.length + bytes.length > upload.size)
      throw attachmentProblem(413, 'CHUNK_TOO_LARGE', 'Chunk is empty, over the window, or past the declared size');
    upload.chunks.push(bytes);
    upload.length += bytes.length;
    return { offset: upload.length };
  }

  /** `attachment.finish`: verifies the whole upload against its declaration and answers its reference. */
  finish(documentId, body) {
    const upload = this.find(documentId, body);
    if (upload.length !== upload.size) throw attachmentProblem(409, 'UPLOAD_INCOMPLETE', 'Upload is shorter than its declared size');
    const bytes = upload.bytes();
    const digest = createHash('sha256').update(bytes).digest('hex');
    const uploadId = body.uploadId;
    if (digest !== upload.sha256) {
      this.pending.delete(uploadId);
      throw attachmentProblem(422, 'UPLOAD_HASH_MISMATCH', 'Received bytes do not hash to the declared sha256');
    }
    const attachmentRef = `probe-upload:${uploadId}`;
    const metadata = { attachmentRef, path: attachmentRef, name: upload.name, mime: upload.mime, size: upload.size, sha256: digest, source: 'mobile-upload' };
    this.finished.set(attachmentRef, { ...metadata, bytes });
    this.pending.delete(uploadId);
    return metadata;
  }

  /** `attachment.abort`: drops one open upload, so nothing can finish it later. */
  abort(documentId, body) {
    const upload = this.find(documentId, body);
    this.pending.delete(body.uploadId);
    upload.chunks = [];
    return { aborted: true };
  }

  /** Drops every upload one document opened: a document that went away leaves nothing half-written behind. */
  invalidate(documentId) {
    for (const [uploadId, upload] of this.pending) if (upload.documentId === documentId) this.pending.delete(uploadId);
  }

  /**
   * The files one message may reference: exactly the uploads this store finished, re-read and re-hashed, so a
   * reference whose bytes changed is refused instead of being sent as its declaration.
   */
  validate(files) {
    if (!Array.isArray(files) || files.length > MAX_PENDING_UPLOADS) throw attachmentProblem(413, 'UPLOAD_LIMIT', 'Too many attachments');
    for (const file of files) {
      if (!file || typeof file !== 'object' || file.kind !== 'upload') throw attachmentProblem(422, 'ATTACHMENT_SOURCE_NOT_ALLOWED', 'Only finished uploads may be referenced');
      const path = typeof file.path === 'string' ? file.path : '';
      if (!ATTACHMENT_REFERENCE_PATTERN.test(path)) throw attachmentProblem(422, 'INVALID_ATTACHMENT', 'Attachment reference is not one this store issued');
      const saved = this.finished.get(path);
      if (!saved) throw attachmentProblem(410, 'ATTACHMENT_NOT_FOUND', 'Attachment is no longer stored');
      if (saved.size !== saved.bytes.length || createHash('sha256').update(saved.bytes).digest('hex') !== saved.sha256)
        throw attachmentProblem(422, 'ATTACHMENT_CORRUPT', 'Stored attachment does not match its declaration');
    }
  }

  find(documentId, body) {
    const uploadId = body?.uploadId;
    const upload = typeof uploadId === 'string' ? this.pending.get(uploadId) : undefined;
    if (!upload || upload.deadline <= this.now()) {
      if (upload) this.pending.delete(uploadId);
      throw attachmentProblem(410, 'UPLOAD_EXPIRED', 'No open upload has this id');
    }
    if (upload.documentId !== documentId) throw attachmentProblem(409, 'STALE_UPLOAD', 'Upload belongs to another document');
    return upload;
  }
}
