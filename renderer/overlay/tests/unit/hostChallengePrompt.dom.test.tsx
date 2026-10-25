/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from '@arco-design/web-react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { read, answer, t } = vi.hoisted(() => ({
  read: vi.fn(),
  answer: vi.fn(),
  t: (key: string, options?: Record<string, unknown>) =>
    options ? `${key}(${Object.values(options).join('|')})` : key,
}));

vi.mock('@renderer/host/hostInteractions', () => ({
  readConnectionChallenge: read,
  answerConnectionChallenge: answer,
}));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t, i18n: { language: 'zh-CN' } }) }));

import HostChallengePrompt from '@renderer/host/HostChallengePrompt';

const challenge = (overrides: Record<string, unknown> = {}) => ({
  id: 'challenge-1',
  hostId: 'host-3',
  hostName: 'edge',
  kind: 'password',
  message: '输入 edge 的登录密码',
  secret: true,
  ...overrides,
});

const password = 'correct horse battery staple';

const renderPrompt = () =>
  render(
    <ConfigProvider>
      <HostChallengePrompt />
    </ConfigProvider>
  );

beforeEach(() => {
  read.mockReset();
  answer.mockReset();
  read.mockResolvedValue(null);
});

afterEach(() => {
  cleanup();
});

describe('the prompt behind a connection that waits for the user', () => {
  it('takes a credential in a secret field, sends it once, and leaves nothing behind', async () => {
    const logs = [vi.spyOn(console, 'debug'), vi.spyOn(console, 'error'), vi.spyOn(console, 'warn')].map((spy) =>
      spy.mockImplementation(() => {})
    );
    read.mockResolvedValueOnce(challenge()).mockResolvedValue(null);
    answer.mockResolvedValueOnce({ answered: true });
    renderPrompt();

    const field = await screen.findByLabelText('conversation.hostTools.challengeSecret');
    expect(field).toHaveAttribute('type', 'password');
    expect(screen.getByText('edge')).toBeInTheDocument();
    expect(screen.getByText('输入 edge 的登录密码')).toBeInTheDocument();

    const confirm = screen.getByTestId('host-challenge-confirm');
    expect(confirm).toBeDisabled();
    fireEvent.change(field, { target: { value: password } });
    await waitFor(() => expect(confirm).toBeEnabled());
    fireEvent.click(confirm);

    await waitFor(() => expect(answer).toHaveBeenCalledWith('challenge-1', password));
    // The host cleared it, so the next read decides: nothing here keeps the credential alive.
    await waitFor(() => expect(screen.queryByTestId('host-challenge-prompt')).toBeNull());
    for (const spy of logs) {
      for (const call of spy.mock.calls) expect(JSON.stringify(call)).not.toContain(password);
      spy.mockRestore();
    }
  });

  it('answers a host key confirmation with accept, and refuses it with no answer at all', async () => {
    read.mockResolvedValue(challenge({ kind: 'host_key', secret: false, message: 'SHA256:abcd' }));
    answer.mockResolvedValue({ answered: true });
    renderPrompt();

    fireEvent.click(await screen.findByTestId('host-challenge-confirm'));
    await waitFor(() => expect(answer).toHaveBeenLastCalledWith('challenge-1', 'accept'));
    expect(screen.queryByLabelText('conversation.hostTools.challengeSecret')).toBeNull();

    fireEvent.click(screen.getByTestId('host-challenge-cancel'));
    await waitFor(() => expect(answer).toHaveBeenLastCalledWith('challenge-1', null));
  });

  it('states a credential request this build cannot answer and cancels it, instead of answering blind', async () => {
    read.mockResolvedValue(challenge({ kind: 'biometric', secret: true }));
    answer.mockResolvedValue({ answered: true });
    renderPrompt();

    expect(await screen.findByText('conversation.hostTools.challengeUnknown(biometric)')).toBeInTheDocument();
    expect(screen.queryByTestId('host-challenge-confirm')).toBeNull();
    expect(screen.queryByLabelText('conversation.hostTools.challengeSecret')).toBeNull();
    fireEvent.click(screen.getByTestId('host-challenge-cancel'));
    await waitFor(() => expect(answer).toHaveBeenCalledWith('challenge-1', null));
  });

  it('reports the code of a refused answer and lets the user try again', async () => {
    read.mockResolvedValue(challenge());
    answer.mockRejectedValueOnce(
      Object.assign(new Error('HOST_ACTION_FAILED'), {
        name: 'BackendHttpError',
        status: 409,
        code: 'CHALLENGE_NOT_PENDING',
        backendMessage: '该凭据请求已不再挂起。',
      })
    );
    answer.mockResolvedValueOnce({ answered: true });
    renderPrompt();

    const field = await screen.findByLabelText('conversation.hostTools.challengeSecret');
    fireEvent.change(field, { target: { value: password } });
    fireEvent.click(screen.getByTestId('host-challenge-confirm'));

    expect(await screen.findByTestId('host-challenge-failure')).toHaveTextContent(
      'settings.hosts.requestFailed(CHALLENGE_NOT_PENDING|该凭据请求已不再挂起。)'
    );
    const confirm = screen.getByTestId('host-challenge-confirm');
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);
    await waitFor(() => expect(answer).toHaveBeenLastCalledWith('challenge-1', password));
  });

  it('states a credential request it could not read, with the retry behind it', async () => {
    read.mockRejectedValueOnce(
      Object.assign(new Error('HOST_ACTION_FAILED'), {
        name: 'BackendHttpError',
        status: 501,
        code: 'CHALLENGE_UNSUPPORTED',
        backendMessage: '本机不支持读取凭据请求。',
      })
    );
    renderPrompt();

    expect(await screen.findByTestId('host-challenge-unavailable')).toHaveTextContent(
      'conversation.hostTools.challengeUnavailable(settings.hosts.requestFailed(CHALLENGE_UNSUPPORTED|本机不支持读取凭据请求。))'
    );
    read.mockResolvedValue(challenge());
    fireEvent.click(screen.getByTestId('host-challenge-retry'));
    expect(await screen.findByTestId('host-challenge-prompt')).toBeInTheDocument();
    expect(screen.queryByTestId('host-challenge-unavailable')).toBeNull();
  });
});
