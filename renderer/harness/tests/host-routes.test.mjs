// SPDX-License-Identifier: Apache-2.0
import { afterAll, beforeAll, describe, expect, test } from 'bun:test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startBackend } from '../backend.mjs';

const C = 'conv-probe-1';
let backend;
const api = async (path, method = 'GET', body) => {
  const response = await fetch(backend.url + path, { method, headers: { ...backend.probeHeaders, 'Content-Type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  return { status: response.status, body: await response.json() };
};
const unwrap = (body) => body?.data ?? body;
const queuePath = (queueId) => `/api/queue/${encodeURIComponent(queueId)}`;

beforeAll(async () => { backend = await startBackend({ runDir: mkdtempSync(join(tmpdir(), 'rmagent-host-routes-')) }); });
afterAll(async () => { await backend?.close(); });

describe('cross-session routes of the offline backend, without an agent', () => {
  test('queue.send only queues, queue.list counts what waits, and a withdrawal is recorded as withdrawn', async () => {
    const sent = await api('/api/queue', 'POST', { conversation_id: C, kind: 'notify', text: '来自离线探针的队列通知。' });
    expect(sent.status).toBe(200);
    expect(unwrap(sent.body)).toMatchObject({ queue_id: 'queue-probe-1', conversation_id: C, kind: 'notify', state: 'pending', queued: true, delivered: false });
    const listed = unwrap((await api('/api/queue')).body);
    expect(listed.items.map((row) => row.queue_id)).toEqual(['queue-probe-1']);
    expect(listed.items[0]).toMatchObject({ state: 'pending', pending: true });
    expect(listed.stats).toEqual({ stored: 1, pending: 1, delivered: 0, dropped: 0 });
    const cancelled = await api(queuePath('queue-probe-1'), 'DELETE', {});
    expect(cancelled.status).toBe(200);
    expect(unwrap(cancelled.body)).toEqual({ cancelled: true, queue_id: 'queue-probe-1', delivered: false });
    expect(unwrap((await api('/api/queue')).body).items).toEqual([]);
    const status = unwrap((await api('/api/probe/status')).body);
    expect(status.queue.rows[0]).toMatchObject({ queue_id: 'queue-probe-1', state: 'withdrawn' });
    expect(status.queue.stats).toEqual({ stored: 1, pending: 0, delivered: 0, dropped: 0 });
    const refused = [
      await api(queuePath('queue-probe-1'), 'DELETE', {}),
      await api(queuePath('x'.repeat(129)), 'DELETE', {}),
      await api('/api/queue', 'POST', { conversation_id: C, kind: 'later', text: '中文' }),
      await api('/api/queue', 'POST', { conversation_id: C, kind: 'request', text: '   ' }),
      await api('/api/queue', 'POST', { conversation_id: 'conv-other', kind: 'notify', text: '中文' }),
    ];
    expect(refused.map((result) => [result.status, result.body.code])).toEqual([
      [404, 'QUEUE_NOT_FOUND'],
      [422, 'INVALID_QUEUE_ID'],
      [422, 'INVALID_KIND'],
      [422, 'INVALID_CONTENT'],
      [404, 'CONVERSATION_NOT_FOUND'],
    ]);
    expect(unwrap((await api('/api/queue')).body).stats).toEqual({ stored: 1, pending: 0, delivered: 0, dropped: 0 });
  }, 60000);

  test('index.clear reports both counts and hosts.list carries the windows agent target', async () => {
    const first = await api('/api/index/clear', 'POST', {});
    expect(first.status).toBe(200);
    expect(unwrap(first.body)).toEqual({ cleared: true, sessions_before: 1, sessions: 0 });
    expect(unwrap((await api('/api/index/clear', 'POST', {})).body)).toEqual({ cleared: true, sessions_before: 0, sessions: 0 });
    const hosts = unwrap((await api('/api/hosts')).body);
    expect(hosts.items.length).toBe(3);
    for (const row of hosts.items)
      for (const field of ['id', 'nickname', 'hostname', 'port', 'username', 'protocol', 'os_type', 'agent_target', 'target', 'target_supported', 'session_count', 'detection'])
        expect(Object.hasOwn(row, field)).toBe(true);
    expect(hosts.items.find((row) => row.agent_target === 'windows')).toMatchObject({ os_type: 'windows', target: 'windows-native', target_supported: true });
    expect(hosts.items.find((row) => row.agent_target === 'wsl:Ubuntu')).toMatchObject({ target: 'wsl:Ubuntu', target_supported: true });
    expect(hosts.items.find((row) => row.os_type === 'linux')).toMatchObject({ agent_target: 'native', target: 'posix' });
  }, 60000);
});
