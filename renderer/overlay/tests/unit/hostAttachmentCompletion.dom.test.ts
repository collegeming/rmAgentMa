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
  // 宿主尚不支持接收附件：上传止步于边界，既不碰传输、也不报进度。
  // This host cannot receive attachments, so the upload stops at that boundary: no frame reaches the
  // transport and no progress is ever reported.
  it.each(['signal', 'document'])('stops before the transport after a %s cancellation', async (kind) => {
    const controller = new AbortController();
    const progress = vi.fn();
    if (kind === 'signal') controller.abort();
    else
      alive.mockImplementation(() => {
        throw new Error('HOST_DOCUMENT_CLOSED');
      });
    const file = {
      name: 'probe.txt',
      type: 'text/plain',
      size: 1,
      arrayBuffer: async () => new Uint8Array([1]).buffer,
    } as File;
    await expect(uploadHostAttachment(file, 'conv-probe-1', progress, undefined, controller.signal)).rejects.toThrow(
      'HOST_ATTACHMENTS_UNSUPPORTED'
    );
    expect(progress).not.toHaveBeenCalled();
    expect(alive).not.toHaveBeenCalled();
    expect(action).not.toHaveBeenCalled();
  });
});
