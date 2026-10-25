/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { hostAttachmentFailureText, uploadHostAttachment } from '@/common/host/hostAttachments';
import { HostActionError } from '@/common/host/hostTransport';

const { action, alive } = vi.hoisted(() => ({ action: vi.fn(), alive: vi.fn() }));

vi.mock('@/common/host/hostTransport', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/common/host/hostTransport')>()),
  hostAction: action,
  captureHostDocument: () => alive,
}));

const text = (key: string, options?: Record<string, unknown>) =>
  options ? `${key}(${Object.values(options).join('|')})` : key;

const file = (bytes: number[], name = 'probe.txt') =>
  ({ name, type: 'text/plain', size: bytes.length, arrayBuffer: async () => new Uint8Array(bytes).buffer }) as File;

/** The host side of one upload: it names the chunk size, acks each offset and answers the stored path. */
const hostChunks = (chunkBytes = 2, attachmentRef = '/data/uploads/probe.txt') =>
  action.mockImplementation(async (name: string, _conversationId: string, body: unknown) => {
    if (name === 'attachment.begin') return { uploadId: 'upload-1', chunkBytes };
    if (name === 'attachment.chunk') {
      const chunk = body as { offset: number; data: string };
      return { offset: chunk.offset + atob(chunk.data).length };
    }
    if (name === 'attachment.finish') return { attachmentRef };
    if (name === 'attachment.abort') return { aborted: true };
    throw new Error(`unexpected action ${name}`);
  });

afterEach(() => {
  vi.unstubAllGlobals();
  action.mockReset();
  alive.mockReset();
});

describe('native attachment upload', () => {
  it('uploads the file in the chunks the host names and returns the path it answered', async () => {
    hostChunks();
    const progress = vi.fn();
    const path = await uploadHostAttachment(file([1, 2, 3]), 'conv-probe-1', progress, 'probe.txt');

    expect(path).toBe('/data/uploads/probe.txt');
    expect(action.mock.calls.map((call) => call[0])).toEqual([
      'attachment.begin',
      'attachment.chunk',
      'attachment.chunk',
      'attachment.finish',
    ]);
    expect(action.mock.calls[0][2]).toMatchObject({ name: 'probe.txt', mime: 'text/plain', size: 3 });
    // The digest is what the host verifies the reassembled bytes against.
    expect((action.mock.calls[0][2] as { sha256: string }).sha256).toMatch(/^[a-f0-9]{64}$/);
    expect((action.mock.calls[1][2] as { offset: number; data: string }).offset).toBe(0);
    expect((action.mock.calls[2][2] as { offset: number }).offset).toBe(2);
    expect(progress).toHaveBeenLastCalledWith(100);
  });

  it('cancels the upload at the boundary when the caller already aborted', async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(
      uploadHostAttachment(file([1]), 'conv-probe-1', undefined, undefined, controller.signal)
    ).rejects.toThrow('Upload aborted');
    expect(action).not.toHaveBeenCalled();
  });

  it('tells the host to abort a started upload when the caller cancels it mid-transfer', async () => {
    const acks: ((value: { offset: number }) => void)[] = [];
    action.mockImplementation(async (name: string, _conversationId: string, body: unknown) => {
      if (name === 'attachment.begin') return { uploadId: 'upload-1', chunkBytes: 2 };
      if (name === 'attachment.chunk') {
        const chunk = body as { offset: number; data: string };
        // The first chunk's answer is held back, so the cancellation lands mid-transfer.
        if (chunk.offset === 0) return new Promise<{ offset: number }>((resolve) => acks.push(resolve));
        return { offset: chunk.offset + atob(chunk.data).length };
      }
      if (name === 'attachment.abort') return { aborted: true };
      throw new Error(`unexpected action ${name}`);
    });

    const controller = new AbortController();
    const uploading = uploadHostAttachment(file([1, 2, 3, 4]), 'conv-probe-1', undefined, undefined, controller.signal);
    await vi.waitFor(() => expect(acks).toHaveLength(1));
    controller.abort();
    acks[0]({ offset: 2 });

    await expect(uploading).rejects.toThrow('Upload aborted');
    expect(action).toHaveBeenCalledWith('attachment.abort', 'conv-probe-1', { uploadId: 'upload-1' });
    expect(action.mock.calls.map((call) => call[0])).not.toContain('attachment.finish');
  });

  it('refuses a file over the size bound before anything reaches the host', async () => {
    const oversized = {
      name: 'big.bin',
      type: '',
      size: 8 * 1024 * 1024 + 1,
      arrayBuffer: async () => new ArrayBuffer(0),
    };
    await expect(uploadHostAttachment(oversized as File, 'conv-probe-1')).rejects.toThrow('FILE_TOO_LARGE');
    expect(action).not.toHaveBeenCalled();
  });

  it('reports the host refusal with its own code and reason instead of a generic failure', async () => {
    const refusal = new HostActionError('attachment.begin', 413, 'UPLOAD_TOO_LARGE', 'the file exceeds 8 MiB');
    action.mockRejectedValueOnce(refusal);

    await expect(uploadHostAttachment(file([1]), 'conv-probe-1')).rejects.toThrow('UPLOAD_TOO_LARGE');
    expect(hostAttachmentFailureText(refusal, text)).toBe(
      'conversation.hostTools.uploadFailed(UPLOAD_TOO_LARGE|the file exceeds 8 MiB)'
    );
  });

  it('names a transport failure by its own code and keeps an unknown one generic', () => {
    expect(hostAttachmentFailureText(new Error('HOST_TIMEOUT: attachment.chunk; result unknown'), text)).toBe(
      'conversation.hostTools.uploadFailed(HOST_TIMEOUT|attachment.chunk; result unknown)'
    );
    expect(hostAttachmentFailureText(new Error('something else'), text)).toBe('common.fileAttach.failed');
    expect(hostAttachmentFailureText(new HostActionError('attachment.finish', 500, '', ''), text)).toBe(
      'conversation.hostTools.uploadFailed(HTTP 500|settings.hosts.reasonUnknown)'
    );
  });
});
