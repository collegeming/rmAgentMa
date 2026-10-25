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
    await waitFor(() => expect(host).toHaveBeenCalledWith('draft.save', 'draft-race', { text: '新输入' }));
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

  it('restores the upload refs a draft came back with and saves its text alone after they are removed', async () => {
    const path = 'probe-upload:12345678-1234-1234-1234-123456789abc';
    host.mockImplementation((action: string) =>
      Promise.resolve(
        action === 'draft.load' ? { text: '附件草稿', files: [{ kind: 'upload', path }] } : { saved: true }
      )
    );
    const { result } = renderHook(() => useObservedDraft('draft-files'), { wrapper });
    await waitFor(() => expect(result.current.data?.uploadFile).toEqual([path]));
    act(() => result.current.mutate((old) => ({ ...old, uploadFile: [] })));
    await waitFor(() => expect(host).toHaveBeenCalledWith('draft.save', 'draft-files', { text: '附件草稿' }));
  });

  it('saves the send box draft that holds an upload as its text alone, and reports it saved', async () => {
    // The ref the host's own attachment.finish answered, held by the send box the attach flow put it in.
    const ref = '/home/ci/work/probe.txt';
    host.mockImplementation((action: string) =>
      Promise.resolve(action === 'draft.load' ? { text: '', files: [] } : { saved: true })
    );
    const { getDraftSaveStatus } = await import('@/renderer/host/nativeDrafts');
    const { result } = renderHook(() => useObservedDraft('draft-box-upload'), { wrapper });
    await waitFor(() => expect(host).toHaveBeenCalledWith('draft.load', 'draft-box-upload'));

    act(() => result.current.mutate((old) => ({ ...old, content: '带附件的草稿', uploadFile: [ref] })));
    expect(getDraftSaveStatus('draft-box-upload')).toBe('saving');
    await waitFor(() => expect(getDraftSaveStatus('draft-box-upload')).toBe('saved'));
    // No file list travels: the host refuses one, and the attachment stays with this session's own draft.
    expect(host).toHaveBeenLastCalledWith('draft.save', 'draft-box-upload', { text: '带附件的草稿' });
    expect(result.current.data?.uploadFile).toEqual([ref]);
  });

  it('round-trips the text across module recreation and keeps the ref with the session it was added to', async () => {
    const path = 'probe-upload:12345678-1234-1234-1234-123456789abc';
    let saved = { text: '', files: [] as { kind: 'upload'; path: string }[] };
    host.mockImplementation(async (action: string, _id: string, body: { text: string }) => {
      if (action === 'draft.save') saved = { text: body.text, files: [] };
      return saved;
    });
    const first = await import('@/renderer/host/nativeDrafts');
    await first.loadNativeDraft('module-draft');
    await first.saveNativeDraft('module-draft', { text: '上传草稿', files: [{ kind: 'upload', path }] });
    expect(host).toHaveBeenLastCalledWith('draft.save', 'module-draft', { text: '上传草稿' });
    expect((await first.loadNativeDraft('module-draft')).files).toEqual([{ kind: 'upload', path }]);
    // A reloaded page keeps the text the host stored and comes back without the attachment it never stored.
    vi.resetModules();
    const second = await import('@/renderer/host/nativeDrafts');
    expect(await second.loadNativeDraft('module-draft')).toEqual({ text: '上传草稿', files: [] });
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

  it('saves the text of a draft that holds the upload reference the host answered, and never the file list', async () => {
    const { loadNativeDraft, saveNativeDraft, getDraftSaveStatus } = await import('@/renderer/host/nativeDrafts');
    // The product host answers the absolute remote path its own attachment.finish wrote and refuses any draft
    // that carries a file list (422 ATTACHMENT_UNSUPPORTED), so the request is the text alone.
    const ref = '/home/ci/work/probe.txt';
    host.mockImplementation((action: string) =>
      Promise.resolve(action === 'draft.load' ? { text: '', files: [] } : { saved: true })
    );

    await loadNativeDraft('draft-upload-ref');
    await saveNativeDraft('draft-upload-ref', { text: '带附件的草稿', files: [{ kind: 'upload', path: ref }] });

    expect(getDraftSaveStatus('draft-upload-ref')).toBe('saved');
    expect(host).toHaveBeenLastCalledWith('draft.save', 'draft-upload-ref', { text: '带附件的草稿' });
    // The reference stays with this session's draft, which is the only place it is kept: no file content and no
    // file list ever reaches the host.
    expect((await loadNativeDraft('draft-upload-ref')).files).toEqual([{ kind: 'upload', path: ref }]);
  });

  it('accepts the two reference shapes an upload can have and refuses every other one', async () => {
    const { loadNativeDraft, saveNativeDraft, getDraftSaveStatus, validateNativeDraft } =
      await import('@/renderer/host/nativeDrafts');
    const probe = 'probe-upload:12345678-1234-1234-1234-123456789abc';
    expect(
      validateNativeDraft({
        text: '两个引用',
        files: [
          { kind: 'upload', path: probe },
          { kind: 'upload', path: 'C:\\Users\\ci\\probe.txt' },
        ],
      })
    ).toEqual({
      text: '两个引用',
      files: [
        { kind: 'upload', path: probe },
        { kind: 'upload', path: 'C:\\Users\\ci\\probe.txt' },
      ],
    });

    // A relative path names no file on the host, a control character cannot be one, and a reference longer
    // than one bridge message carries is refused as an unusable reference rather than stored as text.
    for (const path of ['relative/file.txt', '/home/ci/\u0000probe.txt', `/${'a'.repeat(4096)}`]) {
      expect(() => validateNativeDraft({ text: 'x', files: [{ kind: 'upload', path }] })).toThrow('DRAFT_INVALID');
    }

    host.mockResolvedValue({ text: '', files: [] });
    await loadNativeDraft('draft-bad-ref');
    await expect(
      saveNativeDraft('draft-bad-ref', { text: 'x', files: [{ kind: 'upload', path: 'relative/file.txt' }] })
    ).rejects.toThrow('DRAFT_INVALID');
    expect(getDraftSaveStatus('draft-bad-ref')).toBe('unsaved');
    expect(host.mock.calls.some(([action]) => action === 'draft.save')).toBe(false);
  });
});
