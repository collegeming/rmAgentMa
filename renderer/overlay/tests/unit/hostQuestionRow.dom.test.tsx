/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from '@arco-design/web-react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { transformMessage, type TMessage } from '@/common/chat/chatLib';
import type { IResponseMessage } from '@/common/adapter/ipcBridge';

const { request, t, list } = vi.hoisted(() => ({
  request: vi.fn(),
  t: (key: string, options?: Record<string, unknown>) =>
    options ? `${key}(${Object.values(options).join('|')})` : key,
  list: { messages: [] as unknown[] },
}));

vi.mock('@/common/host/hostTransport', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/common/host/hostTransport')>()),
  hostFetch: request,
}));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t, i18n: { language: 'zh-CN' } }) }));
// The stream around the row: its list, its paging, its own chrome. Only the row's own rendering is under test.
vi.mock('@/renderer/pages/conversation/Messages/hooks', () => ({
  useMessageList: () => list.messages,
  useMessageListLoading: () => false,
  useMessagePaginationState: () => ({ hasMoreBefore: false, hasMoreAfter: false, isLoadingBefore: false }),
  useLoadPreviousMessagePage: () => async () => false,
  useLoadAnchorMessageWindow: () => async () => false,
}));
vi.mock('@/renderer/pages/conversation/Messages/artifacts', () => ({ useConversationArtifacts: () => [] }));
vi.mock('@/renderer/pages/conversation/Messages/anchorRail', () => ({ MessageAnchorRail: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/components/SelectionReplyButton', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/useAutoScroll', () => ({
  useAutoScroll: () => ({
    handleScrollerRef: () => {},
    handleContentRef: () => {},
    handleScroll: () => {},
    handleWheel: () => {},
    handlePointerDown: () => {},
    showScrollButton: false,
    scrollToBottom: () => {},
    scrollElementIntoView: () => {},
    hideScrollButton: () => {},
  }),
}));
vi.mock('@/renderer/pages/conversation/runtime/useConversationRuntimeView', () => ({
  useConversationRuntimeView: () => ({ isProcessing: false, canSend: true }),
}));
vi.mock('@/renderer/hooks/context/ConversationContext', () => ({ useConversationContextSafe: () => undefined }));

import MessageList from '@/renderer/pages/conversation/Messages/MessageList';

const hostAnswer = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

/** The frame the host really publishes on `message.stream`: `type` plus the question it projected as `data`. */
const questionFrame = (data: Record<string, unknown> = {}): IResponseMessage => ({
  type: 'acp_question',
  msg_id: 'question-1',
  conversation_id: 'conv-probe-1',
  turn_id: 'turn-1',
  data: {
    session_id: 'conv-probe-1',
    conversation_id: 'conv-probe-1',
    question_id: 'req-1',
    request_id: 'req-1',
    epoch: 'epoch-1',
    turn_id: 'turn-1',
    title: '需要确认',
    message: '你希望使用哪个目录？',
    options: [],
    fields: [{ name: 'cwd', label: '目录', type: 'string', required: true, choices: [] }],
    single_string: true,
    requested_schema: { type: 'object', properties: { cwd: { type: 'string' } }, required: ['cwd'] },
    live: true,
    readonly: false,
    state: 'waiting',
    ...data,
  },
});

/** That same frame as the chat stream draws it: the row the renderer's own transform makes of it. */
const questionRow = (data: Record<string, unknown> = {}): TMessage => transformMessage(questionFrame(data)) as TMessage;

const renderList = () =>
  render(
    <ConfigProvider>
      <MemoryRouter>
        <MessageList />
      </MemoryRouter>
    </ConfigProvider>
  );

beforeEach(() => {
  request.mockReset();
  list.messages = [];
});

afterEach(() => {
  cleanup();
});

describe('a question the host published in the chat stream', () => {
  it('becomes the host question card from the frame alone, and its answer goes to the interaction route', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const frame = questionFrame();
    const row = transformMessage(frame);
    // The frame the host really sends must survive the renderer's own transform: a type the transform does not
    // know is dropped, and the card never appears.
    expect(row?.type).toBe('acp_question');
    expect(row?.content).toEqual(frame.data);
    expect(row?.conversation_id).toBe('conv-probe-1');
    expect(warn.mock.calls.flat().join(' ')).not.toContain('Unsupported message type');
    warn.mockRestore();

    list.messages = [row as TMessage];
    request.mockResolvedValueOnce(hostAnswer(200, { success: true, data: { state: 'answered' } }));
    renderList();

    const card = screen.getByTestId('host-question-card');
    expect(card).toHaveTextContent('需要确认');
    expect(screen.queryByText(/unknownMessageType/)).toBeNull();

    fireEvent.change(screen.getByLabelText('messages.hostQuestionAnswer'), { target: { value: '/srv/work' } });
    fireEvent.click(screen.getByTestId('host-question-submit'));
    await screen.findByTestId('host-question-answered');
    await waitFor(() =>
      expect(request).toHaveBeenCalledWith('/api/conversations/conv-probe-1/interactions/req-1/review', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ answer: '/srv/work', epoch: 'epoch-1' }),
      })
    );
  });

  it('needs the schema to offer an answer at all, and says so when the host could not read it', () => {
    list.messages = [questionRow({ requested_schema: null })];
    renderList();

    expect(screen.getByTestId('host-question-unsupported')).toBeInTheDocument();
    expect(screen.queryByTestId('host-question-submit')).toBeNull();
    expect(request).not.toHaveBeenCalled();
  });
});
