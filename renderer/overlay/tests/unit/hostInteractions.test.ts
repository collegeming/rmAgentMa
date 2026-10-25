/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { isBackendHttpError } from '@/common/adapter/httpBridge';
import {
  answerConnectionChallenge,
  readConnectionChallenge,
  submitQuestionAnswer,
} from '@/renderer/host/hostInteractions';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));

vi.mock('@/common/host/hostTransport', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/common/host/hostTransport')>()),
  hostFetch: request,
}));

const answer = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const secret = 'correct horse battery staple';

beforeEach(() => {
  request.mockReset();
});

describe('the host routes behind the question card and the credential prompt', () => {
  it('answers a question through the interaction route, with the epoch it was published with', async () => {
    request.mockResolvedValueOnce(answer(200, { success: true, data: { state: 'answered' } }));
    await expect(submitQuestionAnswer('conv-probe-1', 'req-1', '{"name":"a"}', 'epoch-1')).resolves.toEqual({
      state: 'answered',
    });
    expect(request).toHaveBeenCalledWith('/api/conversations/conv-probe-1/interactions/req-1/review', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ answer: '{"name":"a"}', epoch: 'epoch-1' }),
    });
  });

  it('reports the code of a question the host refuses, never a quiet failure', async () => {
    request.mockResolvedValueOnce(
      answer(409, { success: false, code: 'NOT_PENDING', error: '该提问不再是当前挂起项。' })
    );
    const failure = await submitQuestionAnswer('conv-probe-1', 'req-old', 'yes').catch(
      (error: unknown) => error as { code?: string; status?: number; backendMessage?: string }
    );
    expect(failure).toMatchObject({ code: 'NOT_PENDING', status: 409, backendMessage: '该提问不再是当前挂起项。' });
    expect(isBackendHttpError(failure)).toBe(true);
  });

  it('reads the pending challenge and answers it in the request body, never in a URL or a log', async () => {
    const logs = [vi.spyOn(console, 'debug'), vi.spyOn(console, 'error'), vi.spyOn(console, 'warn')].map((spy) =>
      spy.mockImplementation(() => {})
    );
    request.mockResolvedValueOnce(
      answer(200, {
        success: true,
        data: {
          challenge: {
            id: 'challenge-1',
            host_id: 'host-3',
            host_name: 'edge',
            kind: 'password',
            message: '输入登录密码',
            secret: true,
          },
        },
      })
    );
    await expect(readConnectionChallenge()).resolves.toEqual({
      id: 'challenge-1',
      hostId: 'host-3',
      hostName: 'edge',
      kind: 'password',
      message: '输入登录密码',
      secret: true,
    });

    request.mockResolvedValueOnce(answer(200, { success: true, data: { answered: true } }));
    await answerConnectionChallenge('challenge-1', secret);
    const [path, init] = request.mock.calls.at(-1) as [string, { method: string; body: string }];
    expect(init.method).toBe('POST');
    expect(init.body).toBe(JSON.stringify({ answer: secret }));
    // The credential is nowhere near a URL, and nothing logged the request that carried it.
    expect(path).toBe('/api/challenges/challenge-1/answer');
    expect(path).not.toContain(secret);
    for (const spy of logs) {
      for (const call of spy.mock.calls) expect(JSON.stringify(call)).not.toContain(secret);
      spy.mockRestore();
    }
  });

  it('cancels a challenge by sending no answer, and reports the code of one that is no longer pending', async () => {
    request.mockResolvedValueOnce(answer(200, { success: true, data: { answered: true } }));
    await answerConnectionChallenge('challenge-1', null);
    expect(request.mock.calls.at(-1)?.[1]).toMatchObject({ body: JSON.stringify({ answer: null }) });

    request.mockResolvedValueOnce(
      answer(409, { success: false, code: 'CHALLENGE_NOT_PENDING', error: '该凭据请求已不再挂起。' })
    );
    const failure = await answerConnectionChallenge('challenge-1', secret).catch((error: unknown) => error);
    expect(isBackendHttpError(failure) && failure.code).toBe('CHALLENGE_NOT_PENDING');
  });

  it('reads no challenge as no challenge, and an unreadable payload as a failure', async () => {
    request.mockResolvedValueOnce(answer(200, { success: true, data: { challenge: null } }));
    await expect(readConnectionChallenge()).resolves.toBeNull();

    request.mockResolvedValueOnce(answer(200, { success: true, data: { challenge: { id: 'challenge-1' } } }));
    await expect(readConnectionChallenge()).rejects.toThrow('HOST_CHALLENGE_INVALID');

    request.mockResolvedValueOnce(answer(500, { success: false, code: 'CHALLENGE_UNAVAILABLE', error: '无法读取' }));
    const failure = await readConnectionChallenge().catch((error: unknown) => error);
    expect(isBackendHttpError(failure) && failure.code).toBe('CHALLENGE_UNAVAILABLE');
  });
});
