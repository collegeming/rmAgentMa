// SPDX-License-Identifier: Apache-2.0
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { ATTACHMENT_REFERENCE_PATTERN, AttachmentStore, CHUNK_BYTES, UPLOAD_WINDOW_MS } from '../harness/attachment-store.mjs';
import { MessageQueue, SessionIndex, fixtureHosts } from '../harness/host-fixture.mjs';
import { NativeProbeDTO } from '../harness/native-fixture.mjs';

// The offline harness fixtures this suite covers: the upload store every attachment check runs against, the
// cross-session state (queue, index cache, saved hosts) and the native bridge fixture the packaged-asset probe
// drives. Each answer has to carry the contract's own field names, so the checks can assert them.

const C = 'conv-probe-1';
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const DOCUMENT = 'document-probe';
const attachment = (overrides = {}) => ({ name: '契约.txt', mime: 'text/plain', size: 4, sha256: sha(Buffer.from('字节')), ...overrides });

async function refusal(run) {
  try {
    await run();
  } catch (error) {
    return error;
  }
  throw new Error('Expected this call to be refused');
}

describe('offline attachment store', () => {
  it('answers the upload window, echoes every offset and stores the verified reference', () => {
    const store = new AttachmentStore();
    const bytes = Buffer.from('受控附件字节\n');
    const began = store.begin(DOCUMENT, attachment({ size: bytes.length, sha256: sha(bytes) }));
    expect(began.chunkBytes).toBe(CHUNK_BYTES);
    expect(began.chunkBytes).toBeLessThanOrEqual(12288);
    expect(began.expiresInMs).toBe(UPLOAD_WINDOW_MS);
    expect(typeof began.uploadId).toBe('string');
    expect(store.chunk(DOCUMENT, { uploadId: began.uploadId, offset: 0, data: bytes.toString('base64') })).toEqual({ offset: bytes.length });
    const finished = store.finish(DOCUMENT, { uploadId: began.uploadId });
    expect(finished.attachmentRef).toMatch(ATTACHMENT_REFERENCE_PATTERN);
    expect(finished).toMatchObject({ path: finished.attachmentRef, name: '契约.txt', mime: 'text/plain', size: bytes.length, sha256: sha(bytes), source: 'mobile-upload' });
    expect(store.pending.size).toBe(0);
    expect(() => store.validate([{ kind: 'upload', path: finished.attachmentRef }])).not.toThrow();
  });

  it('refuses an oversized, mistyped or malformed declaration and respects its upload window', async () => {
    const store = new AttachmentStore();
    expect(store.begin(DOCUMENT, attachment({ size: 4 }))).toMatchObject({ chunkBytes: CHUNK_BYTES });
    expect((await refusal(() => store.begin(DOCUMENT, attachment({ size: 8 * 1024 * 1024 + 1 })))).code).toBe('UPLOAD_TOO_LARGE');
    expect((await refusal(() => store.begin(DOCUMENT, attachment({ mime: 'application/zip' })))).code).toBe('UNSUPPORTED_MIME');
    expect((await refusal(() => store.begin(DOCUMENT, attachment({ sha256: 'not-a-digest' })))).code).toBe('INVALID_REQUEST');
    expect((await refusal(() => store.begin(DOCUMENT, attachment({ name: '../escape.txt' })))).code).toBe('INVALID_REQUEST');
    for (let index = 0; index < 3; index++) store.begin(DOCUMENT, attachment());
    expect((await refusal(() => store.begin(DOCUMENT, attachment()))).code).toBe('UPLOAD_LIMIT');
  });

  it('refuses a conflicting offset, a chunk past the declared size and a wrong digest', async () => {
    const store = new AttachmentStore();
    const bytes = Buffer.from('偏移与摘要受控字节\n');
    const began = store.begin(DOCUMENT, attachment({ size: bytes.length, sha256: sha(Buffer.from('另一份字节')) }));
    expect((await refusal(() => store.chunk(DOCUMENT, { uploadId: began.uploadId, offset: 1, data: bytes.toString('base64') }))).code).toBe('UPLOAD_OFFSET_CONFLICT');
    expect((await refusal(() => store.chunk(DOCUMENT, { uploadId: began.uploadId, offset: 0, data: Buffer.alloc(CHUNK_BYTES + 1).toString('base64') }))).code).toBe('CHUNK_TOO_LARGE');
    expect((await refusal(() => store.chunk(DOCUMENT, { uploadId: began.uploadId, offset: 0, data: 'not base64!' }))).code).toBe('INVALID_CHUNK');
    store.chunk(DOCUMENT, { uploadId: began.uploadId, offset: 0, data: bytes.toString('base64') });
    expect((await refusal(() => store.finish(DOCUMENT, { uploadId: began.uploadId }))).code).toBe('UPLOAD_HASH_MISMATCH');
    expect(store.pending.size).toBe(0);
    expect((await refusal(() => store.finish(DOCUMENT, { uploadId: began.uploadId }))).code).toBe('UPLOAD_EXPIRED');
  });

  it('keeps a short upload incomplete, drops an aborted one and forgets an expired window', async () => {
    let now = 1000;
    const store = new AttachmentStore({ now: () => now });
    const bytes = Buffer.from('短上传\n');
    const short = store.begin(DOCUMENT, attachment({ size: bytes.length + 1, sha256: sha(bytes) }));
    store.chunk(DOCUMENT, { uploadId: short.uploadId, offset: 0, data: bytes.toString('base64') });
    expect((await refusal(() => store.finish(DOCUMENT, { uploadId: short.uploadId }))).code).toBe('UPLOAD_INCOMPLETE');
    expect(store.abort(DOCUMENT, { uploadId: short.uploadId })).toEqual({ aborted: true });
    expect((await refusal(() => store.finish(DOCUMENT, { uploadId: short.uploadId }))).code).toBe('UPLOAD_EXPIRED');
    const expiring = store.begin(DOCUMENT, attachment({ size: bytes.length, sha256: sha(bytes) }));
    now += UPLOAD_WINDOW_MS + 1;
    expect((await refusal(() => store.chunk(DOCUMENT, { uploadId: expiring.uploadId, offset: 0, data: bytes.toString('base64') }))).code).toBe('UPLOAD_EXPIRED');
  });

  it('keeps one document out of another document\'s upload and refuses references it never issued', async () => {
    const store = new AttachmentStore();
    const bytes = Buffer.from('文档隔离字节\n');
    const began = store.begin(DOCUMENT, attachment({ size: bytes.length, sha256: sha(bytes) }));
    expect((await refusal(() => store.chunk('another-document', { uploadId: began.uploadId, offset: 0, data: bytes.toString('base64') }))).code).toBe('STALE_UPLOAD');
    expect((await refusal(() => store.validate([{ kind: 'upload', path: 'probe-upload:00000000-0000-0000-0000-000000000000' }]))).code).toBe('ATTACHMENT_NOT_FOUND');
    expect((await refusal(() => store.validate([{ kind: 'path', path: 'probe-upload:00000000-0000-0000-0000-000000000000' }]))).code).toBe('ATTACHMENT_SOURCE_NOT_ALLOWED');
    expect((await refusal(() => store.validate([{ kind: 'upload', path: '/etc/passwd' }]))).code).toBe('INVALID_ATTACHMENT');
    expect((await refusal(() => store.validate(Array.from({ length: 5 }, () => ({ kind: 'upload', path: 'probe-upload:00000000-0000-0000-0000-000000000000' }))))).code).toBe('UPLOAD_LIMIT');
    expect(store.pending.size).toBe(1);
    store.invalidate(DOCUMENT);
    expect(store.pending.size).toBe(0);
  });
});

describe('offline queue, index cache and saved hosts', () => {
  it('keeps a queued message pending, then records its withdrawal as withdrawn', async () => {
    const queue = new MessageQueue({ now: () => 7 });
    const queued = queue.enqueue({ conversationId: C, hostId: 'host-1', agent: 'dsh', sessionId: 'session-1', kind: 'notify', text: '中文' });
    expect(queued).toMatchObject({ queue_id: 'queue-probe-1', conversation_id: C, kind: 'notify', state: 'pending', queued: true, delivered: false, created_at: 7 });
    expect(queue.pending().map((row) => row.queue_id)).toEqual(['queue-probe-1']);
    expect(queue.pending('another-session')).toEqual([]);
    expect(queue.stats()).toEqual({ stored: 1, pending: 1, delivered: 0, dropped: 0 });
    expect(queue.cancel(queued.queue_id)).toEqual({ cancelled: true, queue_id: 'queue-probe-1', delivered: false });
    expect(queue.pending()).toEqual([]);
    expect(queue.all()[0]).toMatchObject({ queue_id: 'queue-probe-1', state: 'withdrawn', pending: false });
    expect(queue.stats()).toEqual({ stored: 1, pending: 0, delivered: 0, dropped: 0 });
    expect((await refusal(async () => queue.cancel(queued.queue_id))).code).toBe('QUEUE_NOT_FOUND');
    expect((await refusal(async () => queue.cancel('x'.repeat(129)))).code).toBe('INVALID_QUEUE_ID');
    expect((await refusal(async () => queue.enqueue({ kind: 'later', text: '中文' }))).code).toBe('INVALID_KIND');
    expect((await refusal(async () => queue.enqueue({ kind: 'request', text: '  ' }))).code).toBe('INVALID_CONTENT');
  });

  it('refuses a message its conversation already took instead of reporting a second withdrawal', async () => {
    const queue = new MessageQueue();
    const queued = queue.enqueue({ conversationId: C, kind: 'request', text: '中文' });
    queue.deliver(queued.queue_id);
    expect((await refusal(async () => queue.cancel(queued.queue_id))).code).toBe('QUEUE_NOT_PENDING');
  });

  it('counts the index it holds and leaves, and serves one Windows target under the literal windows', () => {
    const index = new SessionIndex([{ session_id: C }]);
    expect(index.clear()).toEqual({ cleared: true, sessions_before: 1, sessions: 0 });
    expect(index.clear()).toEqual({ cleared: true, sessions_before: 0, sessions: 0 });
    const hosts = fixtureHosts();
    for (const row of hosts)
      for (const field of ['id', 'nickname', 'hostname', 'port', 'username', 'protocol', 'os_type', 'agent_target', 'target', 'target_supported', 'session_count', 'detection'])
        expect(Object.hasOwn(row, field)).toBe(true);
    expect(hosts.find((row) => row.agent_target === 'windows')).toMatchObject({ os_type: 'windows', target: 'windows-native', target_supported: true });
    expect(hosts.find((row) => row.agent_target.startsWith('wsl:'))).toMatchObject({ target: 'wsl:Ubuntu' });
    expect(hosts.find((row) => row.os_type === 'linux')).toMatchObject({ agent_target: 'native', target: 'posix' });
    expect(fixtureHosts()).not.toBe(hosts);
  });
});

describe('native bridge fixture', () => {
  const payload = (body, params = {}) => ({ params, query: {}, body });
  const call = (fixture, action, body, params = {}) => fixture.handle(action, payload(body, params), DOCUMENT);

  it('answers the four upload actions with the contract fields and refuses them without an open conversation', async () => {
    const fixture = new NativeProbeDTO();
    const bytes = Buffer.from('原生附件契约字节\n');
    const declared = { name: '原生.txt', mime: 'text/plain', size: bytes.length, sha256: sha(bytes) };
    await expect(call(fixture, 'attachment.begin', { ...declared, size: 8 * 1024 * 1024 + 1 })).rejects.toMatchObject({ status: 413, code: 'UPLOAD_TOO_LARGE' });
    const began = await call(fixture, 'attachment.begin', declared);
    expect(began.status).toBe(201);
    expect(began.body.chunkBytes).toBe(CHUNK_BYTES);
    const chunk = await call(fixture, 'attachment.chunk', { uploadId: began.body.uploadId, offset: 0, data: bytes.toString('base64') });
    expect(chunk).toEqual({ status: 200, body: { offset: bytes.length } });
    const finished = await call(fixture, 'attachment.finish', { uploadId: began.body.uploadId });
    expect(finished.status).toBe(201);
    expect(finished.body.attachmentRef).toMatch(ATTACHMENT_REFERENCE_PATTERN);
    expect(fixture.attachments.has(finished.body.attachmentRef)).toBe(true);
    expect(() => fixture.validateFiles([{ kind: 'upload', path: finished.body.attachmentRef }])).not.toThrow();
    fixture.openConversation = null;
    await expect(call(fixture, 'attachment.begin', declared)).rejects.toMatchObject({ status: 501, code: 'ATTACHMENT_UNSUPPORTED' });
    fixture.openConversation = C;
    const dropped = await call(fixture, 'attachment.begin', declared);
    expect((await call(fixture, 'attachment.abort', { uploadId: dropped.body.uploadId })).body).toEqual({ aborted: true });
  });

  it('queues, lists and withdraws one message, and serves the index and host actions', async () => {
    const fixture = new NativeProbeDTO();
    const sent = (await call(fixture, 'queue.send', { conversation_id: C, kind: 'request', text: '中文' })).body;
    expect(sent.success).toBe(true);
    expect(sent.data).toMatchObject({ queue_id: 'queue-probe-1', conversation_id: C, kind: 'request', state: 'pending', queued: true, delivered: false });
    expect((await call(fixture, 'queue.list', null, {})).body.data.items.map((row) => row.queue_id)).toEqual(['queue-probe-1']);
    expect((await call(fixture, 'queue.cancel', null, { queueId: 'queue-probe-1' })).body.data).toEqual({ cancelled: true, queue_id: 'queue-probe-1', delivered: false });
    expect(fixture.queue.all()[0].state).toBe('withdrawn');
    expect((await call(fixture, 'index.clear', null)).body.data).toEqual({ cleared: true, sessions_before: 1, sessions: 0 });
    const hosts = (await call(fixture, 'hosts.list', null)).body.data;
    expect(hosts.items.find((row) => row.agent_target === 'windows')).toMatchObject({ target: 'windows-native', target_supported: true });
    await expect(call(fixture, 'queue.send', { conversation_id: 'conv-other', kind: 'notify', text: '中文' })).rejects.toMatchObject({ status: 404, code: 'CONVERSATION_NOT_FOUND' });
    await expect(call(fixture, 'not-real', null)).rejects.toMatchObject({ status: 404, code: 'UNKNOWN_ACTION' });
  });
});

// The two probe scripts only report ok while every required check ran exactly once, so a list that drifted from
// the checks themselves would make a run permanently red without naming anything.
describe('probe check lists', () => {
  const scripts = [
    ['browser-check.mjs', /const requiredChecks = \[([^\]]+)\]/s],
    ['native-browser-check.mjs', /const required = \[([^\]]+)\]/s],
  ];
  for (const [name, listPattern] of scripts) {
    it(`${name} runs exactly the checks it requires`, async () => {
      const source = await readFile(new URL(`../harness/${name}`, import.meta.url), 'utf8');
      const declared = listPattern.exec(source);
      expect(declared, `${name} must declare its required check list`).not.toBeNull();
      const required = declared[1].match(/'([^']+)'/g).map((entry) => entry.slice(1, -1));
      const run = [...source.matchAll(/await check\('([^']+)'/g)].map((match) => match[1]);
      expect(required.length).toBeGreaterThan(0);
      expect([...run].sort()).toEqual([...required].sort());
    });
  }
});
