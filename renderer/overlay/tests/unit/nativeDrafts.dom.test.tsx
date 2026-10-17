/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import { SWRConfig } from 'swr';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getSendBoxDraftHook } from '@/renderer/hooks/chat/useSendBoxDraft';

const { host } = vi.hoisted(() => ({ host: vi.fn() }));
vi.mock('@/common/host/hostTransport', () => ({ isNativeHost: () => true, hostAction: host }));
const useDraft = getSendBoxDraftHook('acp', { _type: 'acp', content: '', atPath: [], uploadFile: [] });
const useObservedDraft = (id: string) => {
  const draft = useDraft(id);
  return { data: draft.data, mutate: draft.mutate, saveStatus: draft.saveStatus };
};
const wrapper = ({ children }: React.PropsWithChildren) => {
  const [cache] = React.useState(() => new Map());
  return <SWRConfig value={{ provider: () => cache, dedupingInterval: 0, errorRetryCount: 0 }}>{children}</SWRConfig>;
};
beforeEach(() => host.mockReset());

describe('native SendBox draft persistence', () => {
  it('restores a host draft scoped to the product conversation', async () => {
    host.mockResolvedValue({ conversation_id: 'draft-load', text: '持久中文草稿' });
    const { result } = renderHook(() => useObservedDraft('draft-load'), { wrapper });
    await waitFor(() => expect(result.current.data?.content).toBe('持久中文草稿'));
    expect(host).toHaveBeenCalledWith('draft.load', 'draft-load');
    expect(host.mock.calls.some(([action]) => action === 'draft.save')).toBe(false);
  });

  it('does not overwrite new input or persist empty text while host loading is pending', async () => {
    let finish!: (value: unknown) => void;
    host.mockImplementation((action: string) =>
      action === 'draft.load'
        ? new Promise((resolve) => {
            finish = resolve;
          })
        : Promise.resolve({ saved: true })
    );
    const { result } = renderHook(() => useObservedDraft('draft-race'), { wrapper });
    await waitFor(() => expect(host).toHaveBeenCalledWith('draft.load', 'draft-race'));
    act(() => result.current.mutate((old) => ({ ...old, content: '新输入' })));
    await waitFor(() => expect(result.current.data?.content).toBe('新输入'));
    expect(host.mock.calls.some(([action]) => action === 'draft.save')).toBe(false);
    await act(async () => finish({ conversation_id: 'draft-race', text: '旧草稿' }));
    await waitFor(() => expect(host).toHaveBeenCalledWith('draft.save', 'draft-race', { text: '新输入', files: [] }));
    expect(result.current.data?.content).toBe('新输入');
  });

  it('never applies one conversation draft to a different conversation', async () => {
    host.mockImplementation((_action: string, conversation: string) =>
      Promise.resolve({ conversation_id: conversation, text: conversation })
    );
    const { result, rerender } = renderHook(({ id }) => useDraft(id), { wrapper, initialProps: { id: 'draft-a' } });
    await waitFor(() => expect(result.current.data?.content).toBe('draft-a'));
    rerender({ id: 'draft-b' });
    await waitFor(() => expect(result.current.data?.content).toBe('draft-b'));
    expect(host).toHaveBeenCalledWith('draft.load', 'draft-b');
  });

  it('retains local text and propagates native save failures with an unsaved status', async () => {
    const { loadNativeDraft, saveNativeDraft, getDraftSaveStatus } = await import('@/renderer/host/nativeDrafts');
    host.mockResolvedValueOnce({ text: '' }).mockRejectedValueOnce(new Error('disk unavailable'));
    await loadNativeDraft('draft-save-fails');
    await expect(saveNativeDraft('draft-save-fails', { text: '保留草稿', files: [] })).rejects.toThrow(
      'disk unavailable'
    );
    expect((await loadNativeDraft('draft-save-fails')).text).toBe('保留草稿');
    expect(getDraftSaveStatus('draft-save-fails')).toBe('unsaved');
  });

  it('restores native upload refs and explicitly saves an empty array after removing them', async () => {
    const path = 'probe-upload:12345678-1234-1234-1234-123456789abc';
    host.mockImplementation((action: string) =>
      Promise.resolve(
        action === 'draft.load' ? { text: '附件草稿', files: [{ kind: 'upload', path }] } : { saved: true }
      )
    );
    const { result } = renderHook(() => useObservedDraft('draft-files'), { wrapper });
    await waitFor(() => expect(result.current.data?.uploadFile).toEqual([path]));
    act(() => result.current.mutate((old) => ({ ...old, uploadFile: [] })));
    await waitFor(() =>
      expect(host).toHaveBeenCalledWith('draft.save', 'draft-files', { text: '附件草稿', files: [] })
    );
  });

  it('round-trips files across module recreation and removal without reviving native refs', async () => {
    const path = 'probe-upload:12345678-1234-1234-1234-123456789abc';
    let saved = { text: '上传草稿', files: [{ kind: 'upload' as const, path }] };
    host.mockImplementation(async (action: string, _id: string, body: typeof saved) => {
      if (action === 'draft.save') saved = body;
      return saved;
    });
    const first = await import('@/renderer/host/nativeDrafts');
    await first.loadNativeDraft('module-draft');
    await first.saveNativeDraft('module-draft', saved);
    vi.resetModules();
    const second = await import('@/renderer/host/nativeDrafts');
    expect((await second.loadNativeDraft('module-draft')).files).toEqual([{ kind: 'upload', path }]);
    await second.saveNativeDraft('module-draft', { text: '只保留文字', files: [] });
    vi.resetModules();
    const third = await import('@/renderer/host/nativeDrafts');
    expect(await third.loadNativeDraft('module-draft')).toEqual({ text: '只保留文字', files: [] });
  });

  it('saves a 16k Chinese draft and rejects over-limit input without reporting success', async () => {
    const { loadNativeDraft, saveNativeDraft, getDraftSaveStatus } = await import('@/renderer/host/nativeDrafts');
    host.mockResolvedValue({ text: '', files: [] });
    await loadNativeDraft('draft-boundary');
    await saveNativeDraft('draft-boundary', { text: '中'.repeat(16384), files: [] });
    expect(getDraftSaveStatus('draft-boundary')).toBe('saved');
    await expect(saveNativeDraft('draft-boundary', { text: '中'.repeat(16385), files: [] })).rejects.toThrow(
      'DRAFT_INVALID'
    );
    expect(getDraftSaveStatus('draft-boundary')).toBe('unsaved');
  });
});
