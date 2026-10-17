// SPDX-License-Identifier: Apache-2.0
import { afterAll, beforeAll, describe, expect, test } from 'bun:test';
import { mkdtempSync, readFileSync, existsSync, appendFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startBackend } from '../backend.mjs';

const C = '/api/conversations/conv-probe-1';
let backend;
let runDir;
const runnerSocket = (url) => new WebSocket(url, { headers: backend.probeHeaders });
async function api(path, method = 'GET', body, headers = {}) {
  const response = await fetch(backend.url + path, { method, headers: { ...backend.probeHeaders, 'Content-Type': 'application/json', ...headers }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  return { status: response.status, body: await response.json() };
}
async function waitFor(check, timeout = 15000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    const value = await check();
    if (value) return value;
    await Bun.sleep(30);
  }
  throw new Error(`Condition did not become true in ${timeout}ms; runtime=${JSON.stringify(backend.state.runtime())}`);
}
async function pending() { return (await api(C + '/confirmations')).body.data[0]; }
async function idle() { await waitFor(() => backend.state.status === 'idle'); }
async function send(content, key = crypto.randomUUID()) {
  const accepted = await api(C + '/messages', 'POST', { content }, { 'Idempotency-Key': key });
  expect(accepted.status).toBe(200);
  return accepted.body.data;
}
async function effect(turnId) { return (await api('/api/probe/status')).body.data.side_effects.find((item) => item.turn_id === turnId); }

beforeAll(async () => {
  runDir = mkdtempSync(join(tmpdir(), 'rmagent-g1-test-'));
  backend = await startBackend({ runDir, dshBin: '/usr/sbin/dsh', port: 0 });
});
afterAll(async () => { await backend?.close(); });

describe('real dsh with isolated fake local models', () => {
  test('unknown API, foreign origin and malformed JSON fail closed', async () => {
    expect((await api('/api/not-real')).status).toBe(404);
    expect((await api('/api/auth/user', 'GET', undefined, { Origin: 'https://evil.example' })).status).toBe(403);
    const response = await fetch(backend.url + C + '/messages', { method: 'POST', headers: { ...backend.probeHeaders, 'Content-Type': 'application/json' }, body: '{' });
    expect(response.status).toBe(400);
    expect((await api(C + '/messages/latest?type=plan')).body.data).toBeNull();
  });

  test('ensure and model setters use real RPC; nonlocal and unknown choices rejected', async () => {
    const ensured = await api(C + '/runtime/ensure', 'POST', {});
    expect(ensured.status).toBe(200);
    expect(ensured.body.data.runtime.supports_midturn_delivery).toBe(false);
    const options = ensured.body.data.config_options.find((option) => option.id === 'model');
    expect(options.options.some((option) => option.value === '["fake","fake-2"]')).toBe(true);
    expect((await api(C + '/config-options/model', 'PUT', { value: '["fake","fake-2"]' })).body.data.confirmation).toBe('observed');
    expect((await api(C + '/config-options/model', 'PUT', { value: '["deepseek-official","deepseek-flash"]' })).status).toBe(403);
    expect((await api(C + '/config-options/model', 'PUT', { value: 'unknown' })).status).toBe(422);
    expect((await api(C + '/config-options/unknown', 'PUT', { value: 'unknown' })).status).toBe(422);
    expect((await api(C + '/config-options')).body.data.config_options.find((option) => option.id === 'model').current_value).toBe('["fake","fake-2"]');
    const environment = readFileSync(join(runDir, 'acp-frames.jsonl'), 'utf8');
    expect(environment).toContain('session/set_config_option');
    expect(existsSync(join(runDir, 'dsh'))).toBe(true);
    const isolation = (await api('/api/probe/status')).body.data.isolation;
    expect(isolation.home).toBe(join(runDir, 'home'));
    expect(isolation.environment_names.sort()).toEqual(['PATH', 'HOME', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME', 'XDG_CACHE_HOME', 'XDG_STATE_HOME', 'TMPDIR', 'DSH_HOME', 'LANG', 'LC_ALL', 'NO_COLOR', 'FAKE_KEY'].sort());
  }, 30000);

  test('Chinese text reaches real dsh; send returns before final and is idempotent', async () => {
    const key = crypto.randomUUID();
    const turn = await send('你好，离线文本测试', key);
    const duplicate = await api(C + '/messages', 'POST', { content: '你好，离线文本测试' }, { 'Idempotency-Key': key });
    expect(duplicate.body.data.turn_id).toBe(turn.turn_id);
    expect((await api(C + '/messages', 'POST', { content: 'different' }, { 'Idempotency-Key': key })).status).toBe(409);
    await idle();
    const messages = (await api(C + '/messages')).body.data.items;
    expect(messages.some((message) => message.content?.content?.includes('text 完成'))).toBe(true);
    const status = (await api('/api/probe/status')).body.data;
    expect(status.last_turn.status).toBe('completed');
    expect(status.fake_requests.every((request) => request.model === 'fake-2')).toBe(true);
  }, 30000);

  test('live allow preserves raw options, real sandbox denial and approved side effect', async () => {
    const turn = await send('allow 允许写入');
    const approval = await waitFor(pending);
    expect(approval.options.map((item) => item.option_id)).toEqual(['allow-once', 'reject-once']);
    expect(approval.tool_call.raw_input.sandbox_permissions).toBe('danger-full-access');
    expect((await effect(turn.turn_id)).exists).toBe(false);
    expect((await api(C + '/messages', 'POST', { content: 'busy' })).status).toBe(409);
    expect((await api(C + '/config-options/model', 'PUT', { value: '["fake","fake-1"]' })).status).toBe(409);
    const path = C + '/confirmations/' + approval.request_id + '/confirm';
    backend.setAllowedOrigins(['http://127.0.0.1:54321']);
    expect((await api(path, 'POST', { data: 'allow-once', epoch: approval.epoch }, { Origin: 'http://127.0.0.1:54322', 'Content-Type': 'text/plain', 'Sec-Fetch-Site': 'same-site' })).status).toBe(403);
    expect((await api(path, 'POST', { data: 'allow-once', epoch: approval.epoch }, { Origin: 'http://127.0.0.1:54321', 'Content-Type': 'text/plain' })).status).toBe(415);
    expect((await pending()).request_id).toBe(approval.request_id);
    expect((await effect(turn.turn_id)).exists).toBe(false);
    expect((await api(path, 'POST', { data: 'allow_once', epoch: approval.epoch })).status).toBe(422);
    expect((await api(path, 'POST', { data: 'allow-once', epoch: 'old' })).status).toBe(409);
    expect((await api(C + '/confirmations/' + approval.tool_call_id + '/confirm', 'POST', { data: 'allow-once', epoch: approval.epoch })).status).toBe(404);
    const answer = { data: 'allow-once', epoch: approval.epoch, msg_id: approval.msg_id };
    expect((await api(path, 'POST', answer, { Origin: 'http://127.0.0.1:54321' })).status).toBe(200);
    expect((await api(path, 'POST', answer)).status).toBe(200);
    expect((await api(path, 'POST', { ...answer, data: 'reject-once' })).status).toBe(409);
    await idle();
    const result = await effect(turn.turn_id);
    expect(result.exists).toBe(true);
    expect(result.content).toBe(`effect:${turn.turn_id}\n`);
    const messages = (await api(C + '/messages')).body.data.items;
    expect(messages.filter((message) => message.type === 'acp_permission').every((message) => message.content.readonly)).toBe(true);
    const tools = messages.filter((message) => message.type === 'acp_tool_call' && message.turn_id === turn.turn_id);
    expect(tools.some((message) => JSON.stringify(message.content).includes('exit code: 1'))).toBe(true);
    expect(tools.every((message) => message.content.update.kind === 'other')).toBe(true);
  }, 30000);

  test('live reject has no side effect and original failed output remains', async () => {
    const turn = await send('reject 拒绝写入');
    const approval = await waitFor(pending);
    expect((await api(C + '/confirmations/' + approval.request_id + '/confirm', 'POST', { data: 'reject-once', epoch: approval.epoch })).status).toBe(200);
    await idle();
    expect((await effect(turn.turn_id)).exists).toBe(false);
    expect(backend.state.messages.some((message) => message.turn_id === turn.turn_id && message.content?.update?.status === 'failed')).toBe(true);
  }, 30000);

  test('pending cancellation expires approval without allowing it; stale cancel cannot stop next turn', async () => {
    const turn = await send('allow 待审批取消');
    const approval = await waitFor(pending);
    const cancelled = await api(C + '/cancel', 'POST', { turn_id: turn.turn_id });
    expect(cancelled.status).toBe(200);
    expect(cancelled.body.data.runtime.state).toBe('cancelling');
    await idle();
    expect((await effect(turn.turn_id)).exists).toBe(false);
    expect((await api(C + '/confirmations/' + approval.request_id + '/confirm', 'POST', { data: 'allow-once', epoch: approval.epoch })).status).toBe(410);
    const next = await send('slow 慢速流式');
    await waitFor(() => backend.state.events.some((event) => event.name === 'message.stream' && event.data.type === 'text' && event.data.turn_id === next.turn_id));
    expect((await api(C + '/cancel', 'POST', { turn_id: turn.turn_id })).status).toBe(409);
    expect(backend.state.turn.id).toBe(next.turn_id);
    expect((await api(C + '/cancel', 'POST', { turn_id: next.turn_id })).status).toBe(200);
    await idle();
  }, 30000);

  test('cancellation during model SSE is confirmed by the real prompt result', async () => {
    const turn = await send('cancel stream');
    await waitFor(async () => (await api('/api/probe/status')).body.data.fake_requests.some((request) => request.turn_id === turn.turn_id));
    expect((await api(C + '/cancel', 'POST', { turn_id: turn.turn_id })).status).toBe(200);
    await idle();
    expect(backend.state.lastTurn).toMatchObject({ turn_id: turn.turn_id, status: 'cancelled', stop_reason: 'cancelled' });
  }, 30000);

  test('WebSocket snapshots, directed subscriptions, cursors and disconnect injection', async () => {
    const frames = [];
    const socket = runnerSocket(backend.url.replace('http:', 'ws:') + '/ws?conversation_id=conv-probe-1&documentId=test-doc');
    socket.onmessage = (event) => frames.push(JSON.parse(event.data));
    await waitFor(() => frames.some((event) => event.name === 'runtime.snapshot'));
    const snapshot = frames.find((event) => event.name === 'runtime.snapshot');
    expect(snapshot.documentId).toBe('test-doc');
    const cursor = snapshot.seq;
    const mutedFrames = [];
    const muted = runnerSocket(backend.url.replace('http:', 'ws:') + '/ws?documentId=muted');
    muted.onmessage = (event) => mutedFrames.push(JSON.parse(event.data));
    await waitFor(() => mutedFrames.some((event) => event.name === 'runtime.snapshot'));
    muted.send(JSON.stringify({ name: 'events.unsubscribe' }));
    await Bun.sleep(30);
    const turn = await send('你好 ws');
    await idle();
    await waitFor(() => frames.some((event) => event.name === 'turn.completed' && event.data.turn_id === turn.turn_id));
    const events = frames.filter((event) => event.name !== 'runtime.snapshot');
    expect(events.every((event) => event.seq > cursor && event.epoch === snapshot.epoch)).toBe(true);
    expect(new Set(events.map((event) => event.seq)).size).toBe(events.length);
    expect(mutedFrames.filter((event) => event.name !== 'runtime.snapshot')).toEqual([]);
    muted.close();
    const closed = new Promise((resolve) => { socket.onclose = resolve; });
    expect((await api('/api/probe/disconnect-stream', 'POST', {})).status).toBe(200);
    await closed;
    const catchup = (await api('/api/probe/events?since=' + cursor)).body.data;
    expect(catchup.some((event) => event.name === 'turn.completed')).toBe(true);
    const recovered = [];
    const reconnect = runnerSocket(backend.url.replace('http:', 'ws:') + `/ws?since=${cursor}&epoch=${snapshot.epoch}`);
    reconnect.onmessage = (event) => recovered.push(JSON.parse(event.data));
    await waitFor(() => recovered.some((event) => event.name === 'turn.completed'));
    reconnect.close();
  }, 30000);

  test('restart restores history, changes epoch and never revives live approvals', async () => {
    await backend.close();
    const previousEpoch = backend.state.epoch;
    const count = backend.state.messages.length;
    backend = await startBackend({ runDir, dshBin: '/usr/sbin/dsh' });
    expect(backend.state.epoch).not.toBe(previousEpoch);
    expect(backend.state.messages.length).toBe(count);
    expect(backend.state.pending()).toEqual([]);
    const restored = await api(C + '/runtime/ensure', 'POST', {});
    expect(restored.status).toBe(200);
    expect(restored.body.data.recovered).toBe(true);
    expect((await api(C + '/confirmations/history-read-only/confirm', 'POST', { data: 'allow-once', epoch: previousEpoch })).status).toBe(404);
    expect((await api('/api/probe/status')).body.data.side_effects.some((item) => item.exists)).toBe(true);
  }, 30000);

  test('restart while approval is live invalidates it, blocks new sends and never replays prompt', async () => {
    const turn = await send('allow restart live approval');
    const approval = await waitFor(pending);
    const requestsBefore = (await api('/api/probe/status')).body.data.fake_requests.length;
    await backend.close();
    backend = await startBackend({ runDir, dshBin: '/usr/sbin/dsh' });
    expect(backend.state.status).toBe('interrupted');
    expect(backend.state.pending()).toEqual([]);
    expect((await api(C + '/confirmations/' + approval.request_id + '/confirm', 'POST', { epoch: approval.epoch, data: 'allow-once' })).status).toBe(409);
    const restored = await api(C + '/runtime/ensure', 'POST', {});
    expect(restored.status).toBe(200);
    expect(restored.body.data.runtime.can_send_message).toBe(false);
    expect((await api(C + '/messages', 'POST', { content: 'do not silently replay' })).status).toBe(409);
    expect((await effect(turn.turn_id)).exists).toBe(false);
    expect((await api('/api/probe/status')).body.data.fake_requests.length).toBe(requestsBefore);
  }, 30000);

  test('real dsh history and side effects remain readable after truncated event and fake request tails', async () => {
    await backend.close();
    const snapshot = readFileSync(join(runDir, 'snapshot.json'));
    const messageCount = backend.state.messages.length;
    for (const file of ['events.jsonl', 'fake-requests.jsonl']) appendFileSync(join(runDir, file), '{"partial":');
    backend = await startBackend({ runDir, dshBin: '/usr/sbin/dsh' });
    const status = (await api('/api/probe/status')).body.data;
    expect(status.read_only).toBe(true);
    expect(status.recovery_issues.filter((issue) => issue.reason === 'incomplete_tail')).toHaveLength(2);
    expect(status.messages).toHaveLength(messageCount);
    expect(status.side_effects.some((effect) => effect.exists && effect.content.startsWith('effect:'))).toBe(true);
    expect((await api(C + '/messages')).body.data.items.length).toBeGreaterThan(0);
    expect((await api(C + '/runtime/ensure', 'POST', {})).status).toBe(409);
    expect((await api(C + '/messages', 'POST', { content: 'must not replay' })).status).toBe(409);
    await backend.close();
    expect(readFileSync(join(runDir, 'snapshot.json'))).toEqual(snapshot);
  }, 30000);
});
