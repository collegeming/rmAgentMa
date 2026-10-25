/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from '@arco-design/web-react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { TMessage } from '@/common/chat/chatLib';

const { request, t } = vi.hoisted(() => ({
  request: vi.fn(),
  t: (key: string, options?: Record<string, unknown>) =>
    options ? `${key}(${Object.values(options).join('|')})` : key,
}));

vi.mock('@/common/host/hostTransport', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/common/host/hostTransport')>()),
  hostFetch: request,
}));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t, i18n: { language: 'zh-CN' } }) }));

import HostQuestionCard from '@renderer/host/HostQuestionCard';

const hostAnswer = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const question = (content: Record<string, unknown>): TMessage =>
  ({
    id: 'question-1',
    msg_id: 'question-1',
    type: 'acp_question',
    position: 'left',
    conversation_id: 'conv-probe-1',
    content: {
      session_id: 'conv-probe-1',
      conversation_id: 'conv-probe-1',
      question_id: 'req-1',
      request_id: 'req-1',
      epoch: 'epoch-1',
      title: '需要确认',
      message: '请选择工作目录',
      options: [],
      fields: [{ name: 'name', label: '名称', type: 'string', required: true, choices: [] }],
      single_string: false,
      requested_schema: { type: 'object', properties: { name: { type: 'string' } }, required: ['name'] },
      live: true,
      readonly: false,
      state: 'waiting',
      ...content,
    },
  }) as unknown as TMessage;

const renderCard = (message: TMessage) =>
  render(
    <ConfigProvider>
      <HostQuestionCard message={message} />
    </ConfigProvider>
  );

beforeEach(() => {
  request.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('the question card of the open conversation', () => {
  it('answers a multi-field question with one JSON object, for the pending reference and epoch', async () => {
    request.mockResolvedValueOnce(hostAnswer(200, { success: true, data: { state: 'answered' } }));
    renderCard(question({}));

    expect(screen.getByTestId('host-question-fields')).toHaveTextContent('名称');
    const submit = screen.getByTestId('host-question-submit');
    expect(submit).toBeDisabled();

    const box = screen.getByLabelText('messages.hostQuestionJson');
    fireEvent.change(box, { target: { value: '{"name":"work"}' } });
    await waitFor(() => expect(submit).toBeEnabled());
    fireEvent.click(submit);

    await screen.findByTestId('host-question-answered');
    expect(request).toHaveBeenCalledWith('/api/conversations/conv-probe-1/interactions/req-1/review', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ answer: '{"name":"work"}', epoch: 'epoch-1' }),
    });
    expect(screen.queryByTestId('host-question-card')).toBeNull();
  });

  it('answers a single-string question from a choice the schema accepts, and keeps the others out of reach', async () => {
    request.mockResolvedValueOnce(hostAnswer(200, { success: true, data: { state: 'answered' } }));
    renderCard(
      question({
        single_string: true,
        fields: [{ name: 'value', label: '选项', type: 'string', required: true, choices: ['alpha', 'beta'] }],
        options: [{ option_id: 'allow_once', name: '允许一次' }],
        requested_schema: { type: 'object', properties: { value: { type: 'string', enum: ['alpha', 'beta'] } } },
      })
    );

    const first = screen.getByTestId('host-question-choice-0');
    expect(first).toHaveTextContent('alpha');
    expect(first).toBeEnabled();
    fireEvent.click(first);

    await screen.findByTestId('host-question-answered');
    expect(request.mock.calls.at(-1)?.[1]).toMatchObject({
      body: JSON.stringify({ answer: 'alpha', epoch: 'epoch-1' }),
    });
  });

  it('states the code of a question the host refuses, and stops offering the answer it refused', async () => {
    request.mockResolvedValueOnce(
      hostAnswer(409, { success: false, code: 'NOT_PENDING', error: '该提问不再是当前挂起项。' })
    );
    renderCard(question({}));
    fireEvent.change(screen.getByLabelText('messages.hostQuestionJson'), { target: { value: '{"name":"work"}' } });
    fireEvent.click(screen.getByTestId('host-question-submit'));

    expect(await screen.findByTestId('host-question-failure')).toHaveTextContent(
      'settings.hosts.requestFailed(NOT_PENDING|该提问不再是当前挂起项。)'
    );
    expect(screen.getByTestId('host-question-submit')).toBeDisabled();
    expect(screen.getByLabelText('messages.hostQuestionJson')).toBeDisabled();
  });

  it('keeps a refused answer editable when its own shape was the problem', async () => {
    request.mockResolvedValueOnce(
      hostAnswer(422, { success: false, code: 'INVALID_ANSWER', error: '该回答不符合提问要求。' })
    );
    renderCard(question({}));
    const box = screen.getByLabelText('messages.hostQuestionJson');
    fireEvent.change(box, { target: { value: '{"name":"work"}' } });
    fireEvent.click(screen.getByTestId('host-question-submit'));

    expect(await screen.findByTestId('host-question-failure')).toHaveTextContent('INVALID_ANSWER');
    expect(screen.getByTestId('host-question-submit')).toBeEnabled();
    expect(box).toBeEnabled();
  });

  it('offers no answer for a question whose structure this host cannot read', async () => {
    renderCard(question({ requested_schema: null }));
    expect(screen.getByTestId('host-question-unsupported')).toHaveTextContent('messages.hostQuestionUnsupported');
    expect(screen.queryByTestId('host-question-submit')).toBeNull();
    expect(request).not.toHaveBeenCalled();
  });
});
