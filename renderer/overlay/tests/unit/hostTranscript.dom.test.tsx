/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The host's answer is replaced wholesale rather than wrapped: a full factory needs no import of the module
 * it replaces, so this file keeps the same behaviour under the build's runner and under a plain runner run.
 * The request queue lives on `globalThis` only because a mock factory cannot close over this module's names.
 */
vi.mock('@/common/adapter/httpBridge', () => {
  class BackendHttpError extends Error {
    readonly code: string;
    readonly backendMessage: string;
    constructor(code: string, backendMessage = '') {
      super(code);
      this.code = code;
      this.backendMessage = backendMessage;
    }
  }
  const state = { answers: [] as unknown[], calls: [] as unknown[][] };
  (globalThis as unknown as Record<string, unknown>).__hostTranscriptMock = state;
  return {
    BackendHttpError,
    isBackendHttpError: (error: unknown) => error instanceof BackendHttpError,
    httpRequest: async (method: string, path: string, body?: unknown) => {
      state.calls.push([method, path, body]);
      const next = state.answers.shift();
      if (next instanceof Error) throw next;
      return next;
    },
  };
});

import { BackendHttpError } from '@/common/adapter/httpBridge';
import HostTranscriptPanel from '@renderer/host/HostTranscriptPanel';
import {
  MAX_TRANSCRIPT_ROW_CHARS,
  projectTranscript,
  stripTranscriptText,
  transcriptFailureText,
  type TranscriptItem,
} from '@renderer/host/hostTranscript';

type MockState = { answers: unknown[]; calls: unknown[][] };
const mockState = (): MockState => (globalThis as unknown as Record<string, MockState>).__hostTranscriptMock;

const item = (overrides: Partial<TranscriptItem> = {}): TranscriptItem => ({
  type: 'user',
  text: '继续',
  id: 'row-1',
  title: '',
  status: '',
  ...overrides,
});

const page = (overrides: Record<string, unknown> = {}) => ({
  conversation_id: 'conv-9',
  items: [item()],
  next_cursor: null,
  has_more: false,
  count: 1,
  ...overrides,
});

const refusal = (code: string, reason = '') => new BackendHttpError(code, reason);

beforeEach(() => {
  const state = mockState();
  state.answers.length = 0;
  state.calls.length = 0;
});

afterEach(() => cleanup());

describe('the projection of one conversation’s history', () => {
  it('labels each kind and folds the ones that carry detail', () => {
    const rows = projectTranscript([
      item({ type: 'user', text: '你好' }),
      item({ type: 'assistant', text: '在' }),
      item({ type: 'tool_call', text: 'ls -la', title: 'read_file', status: 'ok' }),
      item({ type: 'thinking', text: '先看目录' }),
      item({ type: 'notice', text: '系统提示' }),
    ]);

    expect(rows.map((row) => row.kind)).toEqual(['user', 'assistant', 'tool', 'thinking', 'other']);
    expect(rows.map((row) => row.label)).toEqual(['你', '助手', '工具', '思考', '其他']);
    expect(rows.map((row) => row.collapsed)).toEqual([false, false, true, true, false]);
    expect(rows[2].meta).toBe('read_file · ok');
  });

  it('removes control characters, colour sequences and folds every line onto one', () => {
    const text = stripTranscriptText('\u001b[31m红\u001b[0m\u0000色\n第二行\t结束\u202e');

    // A control inside a line becomes a space, so two runs of text never fuse into a new word.
    expect(text).toBe('红 色 第二行 结束');
    expect([...text].map((character) => character.codePointAt(0) ?? 0).some((code) => code < 0x20)).toBe(false);
    expect(text).not.toContain('\u202e');
  });

  it('cuts a line past the limit and says it was cut', () => {
    const long = 'x'.repeat(MAX_TRANSCRIPT_ROW_CHARS + 20);
    const [row] = projectTranscript([item({ type: 'assistant', text: long })]);

    expect(row.text.endsWith('…')).toBe(true);
    expect([...row.text]).toHaveLength(MAX_TRANSCRIPT_ROW_CHARS + 1);
    expect(row.truncated).toBe(true);
    expect(projectTranscript([item({ text: '短' })])[0].truncated).toBe(false);
  });

  it('gives a row without an id a key that differs from the next row’s', () => {
    const rows = projectTranscript([item({ id: '', type: 'user' }), item({ id: '', type: 'user' })], 10);
    expect(rows[0].key).not.toBe(rows[1].key);
  });
});

describe('the sentence each refusal is shown as', () => {
  it('names the four codes the read route answers', () => {
    expect(transcriptFailureText(refusal('CWD_MISSING'))).toBe('这个会话的工作目录未知，读不到它的历史。');
    expect(transcriptFailureText(refusal('TRANSCRIPT_UNSUPPORTED'))).toBe('这个 Agent 没有可读取的历史。');
    expect(transcriptFailureText(refusal('TRANSCRIPT_UNREADABLE'))).toBe('远端读取这段历史失败。');
    expect(transcriptFailureText(refusal('INVALID_LIMIT'))).toBe('读取参数非法，请重试。');
  });

  it('keeps a code this build does not know, and never claims an empty history', () => {
    expect(transcriptFailureText(refusal('INVALID_CURSOR'))).toBe('继续读取的位置已失效，请重新打开这段历史。');
    expect(transcriptFailureText(refusal('HOST_LOST', '链接断开'))).toBe('读取失败（HOST_LOST）：链接断开');
    expect(transcriptFailureText(new Error('boom'))).toBe('读取失败，请重试。');
  });
});

describe('the read-only panel', () => {
  const open = (overrides: Record<string, unknown> = {}) =>
    render(
      <HostTranscriptPanel
        visible
        target={{ conversationId: 'conv-9', title: '别人的会话', hostId: 'host-3', agent: 'kimi' }}
        onClose={() => {}}
        {...overrides}
      />
    );

  it('reads the first page on open and states that it never switches the conversation', async () => {
    mockState().answers.push(page());
    open();

    await screen.findByTestId('host-transcript-row-user');
    expect(screen.getByTestId('host-transcript-readonly')).toHaveTextContent('只读，不会切换当前会话');
    expect(mockState().calls[0]).toEqual(['GET', '/api/conversations/conv-9/transcript?limit=50', undefined]);
  });

  it('offers one older page only when the host gave a continuation', async () => {
    mockState().answers.push(page({ next_cursor: 'c2', has_more: true, count: 50 }));
    open();

    await screen.findByTestId('host-transcript-row-user');
    const earlier = screen.getByTestId('host-transcript-load-earlier');
    mockState().answers.push(page({ items: [item({ id: 'row-0', text: '更早的一行' })] }));
    fireEvent.click(earlier);

    await screen.findByText('更早的一行');
    expect(mockState().calls[1]).toEqual(['GET', '/api/conversations/conv-9/transcript?limit=50&cursor=c2', undefined]);
    // The second page gave no continuation, so the button is gone rather than pointing at nothing.
    await waitFor(() => expect(screen.queryByTestId('host-transcript-load-earlier')).toBeNull());
  });

  it('draws no load button for has_more without a cursor to follow', async () => {
    mockState().answers.push(page({ has_more: true }));
    open();

    await screen.findByTestId('host-transcript-row-user');
    expect(screen.queryByTestId('host-transcript-load-earlier')).toBeNull();
  });

  it('shows the host’s refusal and can ask for the same page again', async () => {
    mockState().answers.push(refusal('TRANSCRIPT_UNREADABLE'));
    open();

    expect(await screen.findByTestId('host-transcript-error')).toHaveTextContent('远端读取这段历史失败。');
    mockState().answers.push(page());
    fireEvent.click(screen.getByTestId('host-transcript-retry'));

    await screen.findByTestId('host-transcript-row-user');
    expect(screen.queryByTestId('host-transcript-error')).toBeNull();
  });

  it('hands a message to the existing queue and says so', async () => {
    mockState().answers.push(page(), {});
    open();
    await screen.findByTestId('host-transcript-row-user');

    fireEvent.change(screen.getByLabelText('发消息给这个会话'), { target: { value: ' 继续这个话题 ' } });
    fireEvent.click(screen.getByTestId('host-transcript-send'));

    expect(await screen.findByTestId('host-transcript-sent')).toHaveTextContent(
      '已加入队列，该会话下次打开且空闲时自动发送。'
    );
    expect(mockState().calls[1]).toEqual([
      'POST',
      '/api/queue',
      {
        conversation_id: 'conv-9',
        host_id: 'host-3',
        agent: 'kimi',
        session_id: null,
        kind: 'request',
        text: '继续这个话题',
      },
    ]);
  });

  it('keeps the text and the failure so the same message can be sent again', async () => {
    mockState().answers.push(page(), refusal('QUEUE_REJECTED', '队列已满'));
    open();
    await screen.findByTestId('host-transcript-row-user');

    fireEvent.change(screen.getByLabelText('发消息给这个会话'), { target: { value: '再试一次' } });
    fireEvent.click(screen.getByTestId('host-transcript-send'));

    expect(await screen.findByTestId('host-transcript-send-error')).toHaveTextContent(
      '发送失败（QUEUE_REJECTED）：队列已满'
    );
    expect(screen.getByLabelText('发消息给这个会话')).toHaveValue('再试一次');

    mockState().answers.push({});
    fireEvent.click(screen.getByTestId('host-transcript-send'));

    expect(await screen.findByTestId('host-transcript-sent')).toBeInTheDocument();
  });

  it('refuses a read answer that names another conversation instead of drawing it', async () => {
    mockState().answers.push(page({ conversation_id: 'conv-other' }));
    open();

    // The answer is not this conversation's page, so it is named as a failed read, not drawn as a history.
    expect(await screen.findByTestId('host-transcript-error')).toHaveTextContent(
      '读取失败（HOST_TRANSCRIPT_MISMATCH）'
    );
    expect(screen.queryByTestId('host-transcript-row-user')).toBeNull();
  });
});
