// SPDX-License-Identifier: Apache-2.0
import { expect, test } from 'bun:test';
import { mkdtempSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { startBackend } from '../backend.mjs';

async function socketAttempt(url, headers) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url, { headers });
    const timer = setTimeout(() => { socket.close(); reject(new Error('WebSocket attempt timed out')); }, 3000);
    socket.onopen = () => { clearTimeout(timer); socket.close(); resolve(true); };
    socket.onerror = () => { clearTimeout(timer); resolve(false); };
  });
}

test('HTTP/WS bind to exact registered origins; originless callers require private runner headers', async () => {
  const backend = await startBackend({ runDir: mkdtempSync(join(tmpdir(), 'rmagent-origin-unit-')) });
  const origin = 'http://127.0.0.1:54321';
  const attacker = 'http://127.0.0.1:54322';
  const path = '/api/conversations/conv-probe-1/messages';
  try {
    const request = (route, headers, method = 'GET', body) => fetch(backend.url + route, { method, headers, ...(body === undefined ? {} : { body }) });
    expect((await request('/api/probe/status', {})).status).toBe(403);
    expect((await request('/api/probe/status', { ...backend.probeHeaders, Origin: origin })).status).toBe(403);
    backend.setAllowedOrigins([origin]);
    expect((await request('/api/probe/status', { Origin: origin })).status).toBe(200);
    expect((await request('/api/probe/status', backend.probeHeaders)).status).toBe(200);
    expect((await request('/api/probe/status', { 'X-RmAgent-Probe-Token': 'wrong' })).status).toBe(403);
    for (const candidate of [attacker, 'http://localhost:54321', 'http://[::1]:54321', origin + '/', 'null']) {
      expect((await request('/api/probe/status', { ...backend.probeHeaders, Origin: candidate })).status).toBe(403);
      expect((await request(path, { Origin: candidate, 'Sec-Fetch-Site': 'same-site', 'Content-Type': 'text/plain' }, 'POST', '{"content":"allow attacker"}')).status).toBe(403);
    }
    for (const headers of [{ Origin: origin }, backend.probeHeaders]) {
      expect((await request(path, { ...headers, 'Content-Type': 'text/plain' }, 'POST', '{"content":"allow attacker"}')).status).toBe(415);
      expect((await request(path, headers, 'POST', '{"content":"allow attacker"}')).status).toBe(415);
    }
    expect((await request(path, { 'Content-Type': 'application/json', 'Sec-Fetch-Site': 'same-origin' }, 'POST', '{"content":"allow attacker"}')).status).toBe(403);
    expect(backend.state.nativeSessionId).toBeNull();
    expect(backend.state.receipts.size).toBe(0);
    const ws = backend.url.replace('http:', 'ws:') + '/ws';
    expect(await socketAttempt(ws, { Origin: attacker })).toBe(false);
    expect(await socketAttempt(ws, {})).toBe(false);
    expect(await socketAttempt(ws, { ...backend.probeHeaders, Origin: attacker })).toBe(false);
    expect(await socketAttempt(ws, { Origin: origin })).toBe(true);
    expect(await socketAttempt(ws, backend.probeHeaders)).toBe(true);
    for (const invalid of ['http://127.0.0.1:*', origin + '/', 'https://127.0.0.1:54321', 'http://user@127.0.0.1:54321']) {
      expect(() => backend.setAllowedOrigins([invalid])).toThrow();
    }
    const socket = new WebSocket(ws, { headers: { Origin: origin } });
    await new Promise((resolve) => { socket.onopen = resolve; });
    const closed = new Promise((resolve) => { socket.onclose = resolve; });
    backend.setAllowedOrigins([]);
    await closed;
    expect((await request('/api/probe/status', { Origin: origin })).status).toBe(403);
    expect((await (await request('/api/probe/status', backend.probeHeaders)).text())).not.toContain(backend.probeHeaders['X-RmAgent-Probe-Token']);
  } finally { await backend.close(); }
}, 15000);
