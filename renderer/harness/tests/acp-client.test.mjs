// SPDX-License-Identifier: Apache-2.0
import { expect, test } from 'bun:test';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { mkdtempSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { AcpClient } from '../acp-client.mjs';
import { SessionState } from '../session-state.mjs';

function fakeChild() {
  const child = new EventEmitter();
  Object.assign(child, { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), pid: null });
  return child;
}

test('ACP UTF-8 survives every byte boundary in notifications, persisted projections and frames', async () => {
  const frame = { jsonrpc: '2.0', method: 'session/update', params: { update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: '你好，世界🧪' } } } };
  const bytes = Buffer.from(JSON.stringify(frame) + '\n');
  const childProcess = await import('node:child_process');
  const { spyOn } = await import('bun:test');
  const child = fakeChild();
  const spy = spyOn(childProcess, 'spawn').mockReturnValue(child);
  try {
    const dir = mkdtempSync(join(tmpdir(), 'rmagent-utf8-unit-'));
    const state = new SessionState(dir);
    const client = new AcpClient({ dshBin: '/unused', cwd: dir, env: {}, patch: '/unused', framesPath: join(dir, 'frames.jsonl') });
    const received = [];
    client.on('notification', (message) => {
      received.push(message);
      state.addMessage('text', { content: message.params.update.content.text });
      state.emit('message.stream', message.params.update);
    });
    for (let boundary = 1; boundary < bytes.length; boundary++) {
      child.stdout.write(bytes.subarray(0, boundary));
      child.stdout.write(bytes.subarray(boundary));
    }
    for (const byte of bytes) child.stdout.write(Buffer.from([byte]));
    child.stdout.end();
    expect(received).toHaveLength(bytes.length);
    expect(received.every((message) => message.params.update.content.text === '你好，世界🧪')).toBe(true);
    expect(state.messages.filter((message) => message.type === 'text').every((message) => message.content.content === '你好，世界🧪')).toBe(true);
    const frames = readFileSync(join(dir, 'frames.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
    expect(frames.every((entry) => entry.params.update.content.text === '你好，世界🧪')).toBe(true);
    expect(readFileSync(join(dir, 'snapshot.json'), 'utf8')).not.toContain('\uFFFD');
    expect(readFileSync(join(dir, 'events.jsonl'), 'utf8')).not.toContain('\uFFFD');
    expect(client.closed).toBe(false);
  } finally { spy.mockRestore(); }
});
