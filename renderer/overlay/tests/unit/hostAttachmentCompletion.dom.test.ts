/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { uploadHostAttachment } from '@/common/host/hostAttachments';
const { action, alive } = vi.hoisted(() => ({ action: vi.fn(), alive: vi.fn() }));
vi.mock('@/common/host/hostTransport', () => ({ hostAction: action, captureHostDocument: () => alive }));
afterEach(() => {
  vi.unstubAllGlobals();
  action.mockReset();
  alive.mockReset();
});

describe('native attachment completion guard', () => {
  it.each(['signal', 'document'])('does not return finish result after %s cancellation', async (kind) => {
    const controller = new AbortController();
    const progress = vi.fn();
    vi.stubGlobal('crypto', { subtle: { digest: async () => new ArrayBuffer(32) } });
    const file = {
      name: 'probe.txt',
      type: 'text/plain',
      size: 1,
      arrayBuffer: async () => new Uint8Array([1]).buffer,
    } as File;
    action.mockImplementation(async (name: string) => {
      if (name === 'attachment.begin') return { uploadId: 'upload-1', chunkBytes: 1 };
      if (name === 'attachment.chunk') return { offset: 1 };
      if (name === 'attachment.finish') {
        if (kind === 'signal') controller.abort();
        else
          alive.mockImplementation(() => {
            throw new Error('HOST_DOCUMENT_CLOSED');
          });
        return { attachmentRef: 'probe-upload:12345678-1234-1234-1234-123456789abc' };
      }
      return {};
    });
    await expect(uploadHostAttachment(file, 'conv-probe-1', progress, undefined, controller.signal)).rejects.toThrow(
      kind === 'signal' ? 'Upload aborted' : 'HOST_DOCUMENT_CLOSED'
    );
    expect(progress.mock.calls.filter(([value]) => value === 100)).toHaveLength(0);
    expect(action.mock.calls.some(([name]) => name === 'attachment.abort')).toBe(false);
  });
});
