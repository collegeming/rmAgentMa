// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { claimOutput, verifyBuild } from './run.mjs';

const origin = 'https://appassets.androidplatform.net';
const entry = '/assets/renderer/';
const C = 'conv-probe-1';
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const error = (status, code) => Object.assign(new Error(code), { status, code });
const required = ['native-handshake', 'real-sendbox-loading-id', 'native-draft-refresh', 'native-send-failure-preserves-draft', 'native-attachment-send', 'native-attachment-draft-refresh', 'native-attachment-contract', 'native-queue-withdrawal', 'native-host-targets-and-index', 'native-history-pages-and-clear', 'native-host-auth-fields', 'native-cwd-missing-send-refused', 'native-conversation-recovery', 'native-document-refresh', 'native-no-network'];

// The offline host the bridge probe drives: one reusable fixture, shared with the unit tests under
// renderer/tests/ so the contract it answers is asserted without a browser.
import { NativeProbeDTO } from './native-fixture.mjs';
import { AUTH_METHODS, HISTORY_STATES, RECOVERY_STATES, SEEDED_HOST_PASSWORD } from './host-fixture.mjs';

export async function runNativeBrowserChecks(options) {
  await claimOutput(options.output);
  const report = { ok: false, diagnosticOnly: Boolean(options.diagnosticOldRelease), label: options.diagnosticOldRelease ?? 'current-release', androidDeviceVerified: false, transport: 'test-only WebMessageListener/replyProxy simulation; JS native DTO subset, not Kotlin execution', checks: [], calls: [], blocked: [], pageErrors: [], screenshots: [] };
  let browser;
  let page;
  const fixture = new NativeProbeDTO();
  try {
    const { verifyOutput } = await import('../scripts/build.mjs');
    const manifestPath = path.join(options.rendererDir, 'renderer-build.json');
    const metadata = options.diagnosticOldRelease ? await verifyOutput(options.rendererDir) : await verifyBuild({ rendererDir: options.rendererDir, buildManifest: manifestPath });
    report.build = { identity: metadata, manifestSha256: sha(await fs.readFile(manifestPath)) };
    const require = createRequire(path.join(options.aionuiRoot, 'package.json'));
    const { chromium, expect } = require('@playwright/test');
    const poll = (fn, message) => expect.poll(fn, { timeout: 15000, intervals: [25, 50, 100, 250], message }).toBe(true);
    browser = await chromium.launch({ executablePath: options.chromeBin, headless: true, chromiumSandbox: true, args: ['--disable-background-networking', '--disable-component-update', '--host-resolver-rules=MAP * ~NOTFOUND'] });
    const context = await browser.newContext({ serviceWorkers: 'block', locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
    const listed = JSON.parse(await fs.readFile(path.join(options.rendererDir, 'asset-manifest.json'), 'utf8')).files.map((file) => file.path);
    const assets = new Set([...listed, 'asset-manifest.json', 'renderer-build.json']);
    await context.route('**/*', async (route) => {
      const requested = new URL(route.request().url());
      const relative = requested.pathname.startsWith(entry) ? decodeURIComponent(requested.pathname.slice(entry.length)) : '';
      if (requested.origin !== origin || !assets.has(relative) || relative.includes('..') || route.request().method() !== 'GET') {
        report.blocked.push(route.request().url());
        return route.abort('blockedbyclient');
      }
      return route.fulfill({ path: path.join(options.rendererDir, relative) });
    });
    await context.routeWebSocket(/.*/, (socket) => { report.blocked.push(socket.url()); socket.close(); });
    let documentId = '';
    let activeFrame;
    const seen = new Map();
    await context.exposeBinding('__nativeProbePost', async ({ frame }, raw) => {
      const record = { raw, sourceOrigin: new URL(frame.url()).origin, mainFrame: frame === page?.mainFrame() };
      report.calls.push(record);
      const request = JSON.parse(raw);
      try {
        if (!record.mainFrame || record.sourceOrigin !== origin || frame !== activeFrame) throw error(403, 'BRIDGE_REJECTED');
        assert.deepEqual(Object.keys(request).sort(), ['action', 'documentId', 'payload', 'requestId']);
        assert.deepEqual(Object.keys(request.payload).sort(), ['body', 'params', 'query']);
        const reply = (result) => ({ requestId: request.requestId, documentId, ok: result.status >= 200 && result.status < 300, ...result });
        if (request.action === 'host.handshake') {
          assert.equal(request.documentId, '');
          // A new document replaces the old one, whose half-written uploads are dropped with it.
          if (documentId) fixture.uploads.invalidate(documentId);
          documentId = randomUUID();
          seen.clear();
          record.response = reply({ status: 200, body: { documentId, protocolVersion: 1 } });
        } else {
          if (!documentId || request.documentId !== documentId) throw error(409, 'STALE_DOCUMENT');
          const previous = seen.get(request.requestId);
          if (previous) {
            if (previous.raw !== raw) throw error(409, 'REQUEST_ID_CONFLICT');
            return previous.response;
          }
          const result = await fixture.handle(request.action, request.payload, documentId);
          record.response = reply(result);
          seen.set(request.requestId, record);
        }
      } catch (failure) {
        record.response = { requestId: request.requestId, documentId, ok: false, status: failure.status ?? 400, body: { success: false, code: failure.code ?? failure.message, error: failure.code ?? failure.message } };
      }
      return record.response;
    });
    await context.addInitScript(() => {
      if (location.origin !== 'https://appassets.androidplatform.net' || window !== window.top) return;
      window.RmAgentHost = { postMessage(raw) { void window.__nativeProbePost(raw).then((reply) => window.RmAgentHost.onmessage?.({ data: JSON.stringify(reply) })); } };
    });
    page = await context.newPage();
    activeFrame = page.mainFrame();
    page.on('request', (request) => { if (request.isNavigationRequest() && request.frame() === page.mainFrame()) documentId = ''; });
    page.on('framenavigated', (frame) => { if (frame === page.mainFrame()) activeFrame = frame; });
    page.setDefaultTimeout(10000);
    page.on('pageerror', (failure) => report.pageErrors.push(failure.message));
    fixture.deliver = (event) => {
      const target = documentId;
      if (!target || page.isClosed()) return;
      void page.evaluate((message) => window.RmAgentHost?.onmessage?.({ data: JSON.stringify(message) }), { ...event, documentId: target }).catch(() => {});
    };
    const calls = (action) => report.calls.filter((call) => JSON.parse(call.raw).action === action);
    const shot = async (name) => {
      const filename = `${name}.png`;
      await page.screenshot({ path: path.join(options.output, filename), fullPage: true });
      report.screenshots.push(filename);
      await fs.writeFile(path.join(options.output, `${name}-dom.txt`), await page.locator('body').innerText());
    };
    const check = async (id, fn) => {
      try { report.checks.push({ id, ok: true, details: await fn() }); }
      catch (failure) { report.checks.push({ id, ok: false, error: failure.message }); await shot(`failure-${id}`).catch(() => {}); }
    };
    await page.goto(`${origin}${entry}index.html#/conversation/${C}`, { waitUntil: 'domcontentloaded' });
    await check('native-handshake', async () => {
      await expect(page.getByTestId('sendbox-input')).toBeVisible();
      assert.ok(calls('host.handshake').length === 1 && documentId);
      await shot('native-baseline');
      return { documentId };
    });
    await check('real-sendbox-loading-id', async () => {
      const count = calls('messages.send').length;
      const text = '真实输入框中文 CR01：检查 loading_id。';
      await page.getByTestId('sendbox-input').fill(text);
      await page.getByTestId('sendbox-send-btn').click();
      await poll(() => calls('messages.send').length > count, 'Real SendBox must invoke native messages.send');
      const call = calls('messages.send').at(-1);
      const body = JSON.parse(call.raw).payload.body;
      assert.equal(body.content, text);
      assert.ok(typeof body.loading_id === 'string' && body.loading_id.length > 0, 'CR01: real SendBox did not send loading_id required by Kotlin ProbeStore');
      assert.equal(call.response?.status, 202);
      await poll(() => fixture.phase === 'idle', 'Synthetic native turn terminates');
      await expect(page.getByTestId('sendbox-input')).toHaveValue('');
      await shot('native-send-accepted');
      return body;
    });
    await check('native-draft-refresh', async () => {
      const draft = '中文原生草稿，刷新后仍在。';
      await page.getByTestId('sendbox-input').fill(draft);
      await poll(() => fixture.draft === draft, 'Real SendBox edits must persist draft.save');
      const oldDocument = documentId;
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('sendbox-input')).toHaveValue(draft);
      assert.notEqual(documentId, oldDocument);
      await shot('native-draft-restored');
      return { draft, oldDocument, documentId };
    });
    await check('native-send-failure-preserves-draft', async () => {
      fixture.failNextSend = true;
      const text = '原生发送失败必须保留中文草稿。';
      await page.getByTestId('sendbox-input').fill(text);
      const count = calls('messages.send').length;
      await page.getByTestId('sendbox-send-btn').click();
      await poll(() => calls('messages.send').length > count && Boolean(calls('messages.send').at(-1).response), 'Injected native send error returned');
      assert.equal(calls('messages.send').at(-1).response.status, 503);
      await expect(page.getByTestId('sendbox-input')).toHaveValue(text);
      await poll(() => fixture.draft === text, 'Rejected send must preserve authoritative draft');
      await shot('native-send-failed-draft');
      return JSON.parse(calls('messages.send').at(-1).raw).payload.body;
    });
    fixture.failNextSend = false;
    await check('native-attachment-send', async () => {
      const file = Buffer.from('中文附件\n受控字节\n');
      const input = page.locator('input[type="file"]').first();
      await input.setInputFiles({ name: '探针.txt', mimeType: 'text/plain', buffer: file });
      await poll(() => fixture.attachments.size === 1, 'Real file input must upload via attachment.begin/chunk/finish');
      const [reference, uploaded] = [...fixture.attachments][0];
      assert.equal(uploaded.sha256, sha(file));
      assert.deepEqual(uploaded.bytes, file);
      // The upload's own answer is checked field by field: this is what a message references later.
      const begin = calls('attachment.begin').at(-1)?.response;
      const chunks = calls('attachment.chunk');
      const finish = calls('attachment.finish').at(-1)?.response;
      assert.ok(begin, 'the real file input must call attachment.begin');
      assert.equal(begin.status, 201, 'attachment.begin must answer 201 with the upload window');
      assert.ok(Number.isSafeInteger(begin.body.chunkBytes) && begin.body.chunkBytes > 0 && begin.body.chunkBytes <= 12288, `attachment.begin field chunkBytes must be 1..12288, got ${begin.body.chunkBytes}`);
      assert.ok(chunks.length >= 1, 'attachment.chunk must be sent for every part of the file');
      assert.equal(chunks.at(-1).response.body.offset, file.length, 'attachment.chunk field offset must be the bytes received so far');
      assert.ok(finish, 'the real file input must call attachment.finish');
      assert.equal(finish.status, 201, 'attachment.finish must answer 201');
      assert.equal(finish.body.attachmentRef, reference, 'attachment.finish field attachmentRef must be the reference the message names');
      assert.equal(finish.body.path, reference, 'attachment.finish field path must be the same reference');
      assert.equal(finish.body.name, '探针.txt', 'attachment.finish field name must be the uploaded name');
      assert.equal(finish.body.mime, 'text/plain', 'attachment.finish field mime must be the declared type');
      assert.equal(finish.body.size, file.length, 'attachment.finish field size must be the bytes received');
      assert.equal(finish.body.sha256, sha(file), 'attachment.finish field sha256 must be the verified digest');
      assert.equal(typeof finish.body.attachmentRef, 'string', 'attachmentRef must be one string the caller can send back');
      const text = '带受控附件的中文消息。';
      await page.getByTestId('sendbox-input').fill(text);
      const count = calls('messages.send').length;
      await page.getByTestId('sendbox-send-btn').click();
      await poll(() => calls('messages.send').length > count && Boolean(calls('messages.send').at(-1).response), 'Real attachment send completed');
      const call = calls('messages.send').at(-1);
      const body = JSON.parse(call.raw).payload.body;
      assert.deepEqual(body.files, [{ kind: 'upload', path: reference }]);
      assert.ok(body.loading_id);
      assert.equal(call.response.status, 202);
      await shot('native-attachment-accepted');
      return { body, size: file.length, sha256: sha(file) };
    });
    fixture.failNextSend = false;
    await check('native-attachment-draft-refresh', async () => {
      const file = Buffer.from('刷新保留的原生附件。');
      await page.locator('input[type="file"]').first().setInputFiles({ name: '刷新附件.txt', mimeType: 'text/plain', buffer: file });
      await poll(() => [...fixture.attachments.values()].some((item) => item.name === '刷新附件.txt'), 'Attachment draft upload must finish');
      const text = '带附件的草稿刷新后不得丢失。';
      await page.getByTestId('sendbox-input').fill(text);
      await poll(() => fixture.draft === text, 'Draft text saved before refresh');
      const reference = [...fixture.attachments].find(([, item]) => item.name === '刷新附件.txt')[0];
      const saved = calls('draft.save').at(-1);
      assert.ok(saved, 'A draft save must have been attempted');
      assert.equal(
        JSON.parse(saved.raw).payload.body.files,
        undefined,
        'The draft is the text alone, so a save must not carry attachment references'
      );
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('sendbox-input')).toHaveValue(text);
      // The reference belongs to the message, not to the draft: after a reload the text is back and the upload is not.
      assert.equal(fixture.draftFiles.length, 0, 'A draft must not keep attachment references');
      const sends = calls('messages.send').length;
      await page.getByTestId('sendbox-send-btn').click();
      await poll(() => calls('messages.send').length > sends && Boolean(calls('messages.send').at(-1).response), 'A restored draft must still be sendable');
      const submitted = JSON.parse(calls('messages.send').at(-1).raw).payload.body;
      assert.equal(submitted.content, text);
      assert.deepEqual(submitted.files ?? [], []);
      assert.equal(calls('messages.send').at(-1).response.status, 202);
      await shot('native-draft-text-survives-refresh');
      return { reference, submitted };
    });
    // The four upload actions and their failure branches, answered by the offline host exactly as the contract
    // states them: every refusal names the action and the field it refused, so one run can say what broke.
    await check('native-attachment-contract', async () => {
      const bytes = Buffer.from('附件契约受控字节\n');
      const declared = (overrides = {}) => ({ name: '契约.txt', mime: 'text/plain', size: bytes.length, sha256: sha(bytes), ...overrides });
      const call = async (action, body) => {
        try { return await fixture.handle(action, { params: {}, query: {}, body }, documentId); }
        catch (failure) { return { status: failure.status, body: { success: false, code: failure.code } }; }
      };
      const refusals = [];
      const refused = async (action, body, status, code) => {
        const answer = await call(action, body);
        assert.equal(answer.status, status, `${action} must answer ${status} ${code}, got ${answer.status} ${answer.body.code}`);
        assert.equal(answer.body.code, code, `${action} must answer ${code}, got ${answer.body.code}`);
        refusals.push({ action, status, code });
      };
      await refused('attachment.begin', declared({ size: 8 * 1024 * 1024 + 1 }), 413, 'UPLOAD_TOO_LARGE');
      const began = await call('attachment.begin', declared());
      assert.equal(began.status, 201, 'attachment.begin must open one upload');
      assert.ok(typeof began.body.uploadId === 'string' && began.body.uploadId, 'attachment.begin field uploadId must identify the upload');
      assert.ok(Number.isSafeInteger(began.body.chunkBytes) && began.body.chunkBytes > 0 && began.body.chunkBytes <= 12288, `attachment.begin field chunkBytes must be 1..12288, got ${began.body.chunkBytes}`);
      await refused('attachment.chunk', { uploadId: began.body.uploadId, offset: 1, data: bytes.toString('base64') }, 409, 'UPLOAD_OFFSET_CONFLICT');
      const chunk = await call('attachment.chunk', { uploadId: began.body.uploadId, offset: 0, data: bytes.toString('base64') });
      assert.equal(chunk.status, 200, 'attachment.chunk must accept a chunk at its own offset');
      assert.equal(chunk.body.offset, bytes.length, `attachment.chunk field offset must echo ${bytes.length} received bytes, got ${chunk.body.offset}`);
      const finished = await call('attachment.finish', { uploadId: began.body.uploadId });
      assert.equal(finished.status, 201, 'attachment.finish must answer the stored attachment');
      assert.ok(typeof finished.body.attachmentRef === 'string' && finished.body.attachmentRef, `attachment.finish field attachmentRef must be one non-empty reference, got ${finished.body.attachmentRef}`);
      assert.equal(finished.body.path, finished.body.attachmentRef, 'attachment.finish fields path and attachmentRef must name the same upload');
      assert.equal(finished.body.name, '契约.txt', 'attachment.finish field name must be the uploaded name');
      assert.equal(finished.body.mime, 'text/plain', 'attachment.finish field mime must be the declared type');
      assert.equal(finished.body.size, bytes.length, 'attachment.finish field size must be the bytes received');
      assert.equal(finished.body.sha256, sha(bytes), 'attachment.finish field sha256 must be the verified digest');
      assert.equal(finished.body.source, 'mobile-upload', 'attachment.finish field source must name where the bytes came from');
      fixture.validateFiles([{ kind: 'upload', path: finished.body.attachmentRef }]);
      assert.throws(() => fixture.validateFiles([{ kind: 'upload', path: 'probe-upload:00000000-0000-0000-0000-000000000000' }]), /ATTACHMENT_NOT_FOUND/, 'a reference this host never issued must not be sendable');
      const mismatched = await call('attachment.begin', declared({ sha256: sha(Buffer.from('另一份字节')) }));
      await call('attachment.chunk', { uploadId: mismatched.body.uploadId, offset: 0, data: bytes.toString('base64') });
      await refused('attachment.finish', { uploadId: mismatched.body.uploadId }, 422, 'UPLOAD_HASH_MISMATCH');
      const incomplete = await call('attachment.begin', declared());
      await refused('attachment.finish', { uploadId: incomplete.body.uploadId }, 409, 'UPLOAD_INCOMPLETE');
      const aborted = await call('attachment.abort', { uploadId: incomplete.body.uploadId });
      assert.equal(aborted.status, 200, 'attachment.abort must answer a dropped upload');
      assert.equal(aborted.body.aborted, true, 'attachment.abort field aborted must be true');
      await refused('attachment.finish', { uploadId: incomplete.body.uploadId }, 410, 'UPLOAD_EXPIRED');
      // An upload belongs to the open conversation: with none open the host refuses it as unsupported.
      fixture.openConversation = null;
      try { await refused('attachment.begin', declared(), 501, 'ATTACHMENT_UNSUPPORTED'); } finally { fixture.openConversation = C; }
      return { refusals, attachmentRef: finished.body.attachmentRef };
    });
    // One message handed to another conversation, and its withdrawal: queued is all the host claims, and the
    // state it keeps is withdrawn rather than the mark a conversation's own delivery writes.
    await check('native-queue-withdrawal', async () => {
      const call = async (action, payload = {}) => {
        try { return (await fixture.handle(action, { params: payload.params ?? {}, query: payload.query ?? {}, body: payload.body ?? null }, documentId)).body; }
        catch (failure) { return { success: false, status: failure.status, code: failure.code }; }
      };
      const sent = await call('queue.send', { body: { conversation_id: C, kind: 'notify', text: '中文队列通知' } });
      assert.ok(typeof sent.data.queue_id === 'string' && sent.data.queue_id, 'queue.send field queue_id must identify the queued message');
      assert.equal(sent.data.conversation_id, C, 'queue.send field conversation_id must name the target conversation');
      assert.equal(sent.data.kind, 'notify', 'queue.send field kind must be the kind that was queued');
      assert.equal(sent.data.state, 'pending', `queue.send field state must be pending, got ${sent.data.state}`);
      assert.equal(sent.data.queued, true, 'queue.send field queued must claim the queue it wrote');
      assert.equal(sent.data.delivered, false, 'queue.send field delivered must stay false: nothing was delivered');
      const listed = await call('queue.list');
      assert.equal(listed.data.items.length, 1, 'queue.list must show the message waiting for its conversation');
      assert.equal(listed.data.items[0].state, 'pending', 'queue.list row field state must be pending');
      assert.equal(listed.data.items[0].pending, true, 'queue.list row field pending must be true while it waits');
      assert.deepEqual(listed.data.stats, { stored: 1, pending: 1, delivered: 0, dropped: 0 }, 'queue.list field stats must count what the queue holds');
      const cancelled = await call('queue.cancel', { params: { queueId: sent.data.queue_id } });
      assert.equal(cancelled.data.cancelled, true, 'queue.cancel field cancelled must be true');
      assert.equal(cancelled.data.queue_id, sent.data.queue_id, 'queue.cancel field queue_id must be the withdrawn message');
      assert.equal(cancelled.data.delivered, false, 'queue.cancel field delivered must never claim a delivery');
      assert.equal((await call('queue.list')).data.items.length, 0, 'a withdrawn message must never be listed as waiting');
      const storedRow = fixture.queue.all().find((row) => row.queue_id === sent.data.queue_id);
      assert.ok(storedRow, 'queue.cancel must keep the withdrawn row in the queue rather than drop it');
      assert.equal(storedRow.state, 'withdrawn', `queue.cancel must record the row as withdrawn, got ${storedRow.state}`);
      const refusals = [];
      const refused = async (action, payload, code) => {
        const answer = await call(action, payload);
        assert.equal(answer.code, code, `${action} must answer ${code}, got ${answer.code}`);
        refusals.push({ action, code });
      };
      await refused('queue.cancel', { params: { queueId: sent.data.queue_id } }, 'QUEUE_NOT_FOUND');
      await refused('queue.cancel', { params: { queueId: 'x'.repeat(129) } }, 'INVALID_QUEUE_ID');
      await refused('queue.send', { body: { conversation_id: C, kind: 'later', text: '中文' } }, 'INVALID_KIND');
      await refused('queue.send', { body: { conversation_id: C, kind: 'request', text: '   ' } }, 'INVALID_CONTENT');
      await refused('queue.send', { body: { conversation_id: 'conv-other', kind: 'notify', text: '中文' } }, 'CONVERSATION_NOT_FOUND');
      return { queueId: sent.data.queue_id, state: storedRow.state, refusals };
    });
    // The index cache and the saved-host rows: the Windows host holds its own Agent under the `windows` target.
    await check('native-host-targets-and-index', async () => {
      const call = async (action, payload = {}) => (await fixture.handle(action, { params: payload.params ?? {}, query: payload.query ?? {}, body: payload.body ?? null }, documentId)).body;
      const cleared = (await call('index.clear')).data;
      assert.deepEqual(cleared, { cleared: true, sessions_before: 1, sessions: 0 }, `index.clear must report the index it held and the one it leaves, got ${JSON.stringify(cleared)}`);
      const again = (await call('index.clear')).data;
      assert.deepEqual(again, { cleared: true, sessions_before: 0, sessions: 0 }, 'index.clear on an empty index must report zero, not invent sessions');
      const hosts = (await call('hosts.list')).data;
      assert.equal(hosts.items.length, 3, 'hosts.list must list every saved host');
      for (const row of hosts.items)
        for (const field of ['id', 'nickname', 'hostname', 'port', 'username', 'protocol', 'os_type', 'agent_target', 'target', 'target_supported', 'session_count', 'detection'])
          assert.ok(field in row, `hosts.list row ${row.id} must carry field ${field}`);
      const windows = hosts.items.find((row) => row.os_type === 'windows' && row.agent_target === 'windows');
      assert.ok(windows, "hosts.list must offer a Windows host's own Agent target under the literal windows");
      assert.equal(windows.target, 'windows-native', `hosts.list row ${windows.id} field target must be windows-native, got ${windows.target}`);
      assert.equal(windows.target_supported, true, `hosts.list row ${windows.id} field target_supported must be true for the windows target`);
      const wsl = hosts.items.find((row) => String(row.agent_target).startsWith('wsl:'));
      assert.ok(wsl, 'hosts.list must still offer a WSL distribution target');
      assert.equal(wsl.target, wsl.agent_target, 'a WSL row names its distribution as its shell context');
      const posix = hosts.items.find((row) => row.os_type === 'linux');
      assert.equal(posix.agent_target, 'native', 'a Linux host keeps the native agent target');
      assert.equal(posix.target, 'posix', 'a Linux host runs its Agent in its own POSIX shell');
      return { cleared, again, targets: hosts.items.map((row) => `${row.os_type}/${row.agent_target}/${row.target}/${row.target_supported}`) };
    });
    // The history store through the bridge: every page states how much of it the host could read, one older page
    // is served below the window, and its clear entry is its own — separate from the session index cache.
    await check('native-history-pages-and-clear', async () => {
      const call = async (action, body = null, payload = {}) => {
        try { return (await fixture.handle(action, { params: payload.params ?? {}, query: payload.query ?? {}, body }, documentId)).body; }
        catch (failure) { return { success: false, status: failure.status, code: failure.code }; }
      };
      const refusals = [];
      const refused = async (label, answer, status, code) => {
        assert.equal(answer.status, status, `${label} answered ${answer.status}, expected ${status} ${code} (${JSON.stringify(answer)})`);
        assert.equal(answer.code, code, `${label} must answer ${code}, got ${answer.code}`);
        refusals.push({ label, status, code });
      };
      const window = (await call('messages.list')).data;
      assert.ok(Array.isArray(window.items) && window.items.length > 0, 'messages.list must answer the loaded window');
      assert.ok(window.history && HISTORY_STATES.includes(window.history.state), `messages.list field history.state must be one of ${HISTORY_STATES.join('/')}, got ${JSON.stringify(window.history)}`);
      assert.equal(window.history.state, 'complete', `messages.list must report the history it read whole as complete, got ${window.history.state}`);
      assert.equal(window.history.note, null, 'messages.list field history.note must stay null while the history was read whole');
      assert.equal(window.has_more_before, true, 'the store holds one older row, so the window must not claim to be the start of the history');
      assert.ok(window.oldest_cursor, 'messages.list field oldest_cursor must name the cursor the paging protocol asks with');
      const older = await call('messages.before', null, { query: { before: window.oldest_cursor, limit: '50' } });
      assert.equal(older.success, true, `messages.before answered ${JSON.stringify(older)}`);
      assert.ok(older.data.items.length >= 1, 'messages.before must serve the older page the store holds');
      assert.equal(older.data.items.at(-1).conversation_id, C, 'messages.before row field conversation_id must name this conversation');
      assert.ok(older.data.history && HISTORY_STATES.includes(older.data.history.state), `messages.before page field history.state must be one of ${HISTORY_STATES.join('/')}, got ${JSON.stringify(older.data.history)}`);
      assert.equal(older.data.has_more_after, true, 'messages.before page field has_more_after must be true: the caller still holds the newer part');
      assert.equal(older.data.has_more_before, false, 'nothing is below the oldest row the store holds');
      await refused('messages.before with a cursor outside the window', await call('messages.before', null, { query: { before: 'never-loaded', limit: '50' } }), 422, 'INVALID_CURSOR');
      await refused('messages.before below the oldest row', await call('messages.before', null, { query: { before: older.data.oldest_cursor, limit: '50' } }), 409, 'HISTORY_EXHAUSTED');
      const restore = { state: fixture.history.state, note: fixture.history.note };
      try {
        fixture.history.state = 'unavailable';
        fixture.history.note = '历史库未能读取。';
        const unreadable = (await call('messages.list')).data;
        assert.equal(unreadable.history.state, 'unavailable', 'messages.list must report an unreadable history store as unavailable');
        assert.equal(unreadable.history.note, '历史库未能读取。', 'an unavailable history must carry the reason it knows');
        assert.ok(unreadable.items.length > 0, 'an unreadable history store must not empty the window the host already loaded');
        await refused('messages.before from an unreadable store', await call('messages.before', null, { query: { before: window.oldest_cursor, limit: '50' } }), 500, 'HISTORY_LOAD_FAILED');
        fixture.history.state = 'partial';
        fixture.history.note = '更早的历史只读到了一部分。';
        assert.equal((await call('messages.list')).data.history.state, 'partial', 'a history that was read in part must say so rather than read as complete');
      } finally {
        fixture.history.state = restore.state;
        fixture.history.note = restore.note;
      }
      const indexBefore = fixture.index.count();
      const cleared = (await call('history.clear')).data;
      assert.deepEqual(cleared, { cleared: true, conversations: 1 }, `history.clear must report the conversations it cleared, got ${JSON.stringify(cleared)}`);
      const again = (await call('history.clear')).data;
      assert.deepEqual(again, { cleared: true, conversations: 0 }, 'a history store that holds nothing must report zero conversations rather than invent one');
      assert.equal(fixture.index.count(), indexBefore, 'history.clear must leave the session index cache alone; index.clear is the entry that drops it');
      // A store that could not be cleared is answered as that failure, with the reason it reported.
      fixture.history.clearFailure = '历史存储被占用';
      try {
        await refused('history.clear from a store that cannot be cleared', await call('history.clear'), 500, 'HISTORY_CLEAR_FAILED');
      } finally {
        fixture.history.clearFailure = null;
      }
      return { cleared, again, refusals, olderRows: older.data.items.map((row) => row.id) };
    });
    // The saved hosts' credentials through the bridge: a row states how it authenticates, the key it uses and
    // whether it holds a password — never the password itself, and never for a host a request only updated.
    await check('native-host-auth-fields', async () => {
      const call = async (action, body = null, payload = {}) => {
        try { return (await fixture.handle(action, { params: payload.params ?? {}, query: payload.query ?? {}, body }, documentId)).body; }
        catch (failure) { return { success: false, status: failure.status, code: failure.code }; }
      };
      const refusals = [];
      const refused = async (label, answer, status, code) => {
        assert.equal(answer.status, status, `${label} answered ${answer.status}, expected ${status} ${code} (${JSON.stringify(answer)})`);
        assert.equal(answer.code, code, `${label} must answer ${code}, got ${answer.code}`);
        refusals.push({ label, status, code });
      };
      const rows = (await call('hosts.list')).data.items;
      for (const row of rows)
        for (const field of ['auth_method', 'key_id', 'key_name', 'has_password'])
          assert.ok(field in row, `hosts.list row ${row.id} must carry field ${field}`);
      for (const row of rows)
        assert.ok(AUTH_METHODS.includes(row.auth_method), `hosts.list row ${row.id} field auth_method must be one of ${AUTH_METHODS.join('/')}, got ${row.auth_method}`);
      const keyed = rows.find((row) => row.auth_method === 'key');
      assert.ok(keyed && Number.isInteger(keyed.key_id) && keyed.key_name, `a key-authenticated host must name the key it uses, got ${JSON.stringify(keyed)}`);
      assert.ok(!JSON.stringify(rows).includes(SEEDED_HOST_PASSWORD), 'hosts.list must never carry a stored password');
      await refused('hosts.create with an unknown auth method', await call('hosts.create', { nickname: '探针主机-未知方式', hostname: '10.0.0.9', auth_method: 'agent' }), 422, 'AUTH_METHOD_INVALID');
      await refused('hosts.create on password authentication without a password', await call('hosts.create', { nickname: '探针主机-无口令', hostname: '10.0.0.9', auth_method: 'password' }), 422, 'PASSWORD_REQUIRED');
      const created = (await call('hosts.create', { nickname: '探针主机-口令', hostname: '10.0.0.9', username: 'probe', auth_method: 'password', password: '离线探针新口令' })).data;
      assert.equal(created.auth_method, 'password', `hosts.create field auth_method must be the method it stored, got ${created.auth_method}`);
      assert.equal(created.key_id, null, 'hosts.create field key_id must stay null for a host that names no key');
      assert.equal(created.has_password, true, 'hosts.create must state that this host now holds a password');
      assert.ok(!JSON.stringify(created).includes('离线探针新口令'), 'hosts.create must never echo the password it stored');
      const afterCreate = (await call('hosts.list')).data.items;
      assert.equal(afterCreate.length, rows.length + 1, 'hosts.list must list the host that was created');
      assert.ok(!JSON.stringify(afterCreate).includes('离线探针新口令'), 'hosts.list must never carry a stored password');
      const updated = (await call('hosts.update', { nickname: '重命名的原生主机', password: '离线探针改过的口令' }, { params: { hostId: keyed.id } })).data;
      assert.equal(updated.id, keyed.id, 'hosts.update must answer the same host it wrote');
      assert.equal(updated.key_id, keyed.key_id, 'hosts.update field key_id must stay the association the request did not name');
      assert.equal(updated.auth_method, keyed.auth_method, 'hosts.update field auth_method must stay what the request did not name');
      assert.equal(updated.has_password, true, 'hosts.update must state that this host holds a password');
      assert.ok(!JSON.stringify(updated).includes('离线探针改过的口令'), 'hosts.update must never echo the password it stored');
      // A host switched to password authentication needs one: a stored credential counts, one it holds none of does not.
      await refused('hosts.update to password authentication without a password', await call('hosts.update', { auth_method: 'password' }, { params: { hostId: 'host-3' } }), 422, 'PASSWORD_REQUIRED');
      return { refusals, created: { id: created.id, auth_method: created.auth_method, has_password: created.has_password }, keyed: { id: keyed.id, key_name: keyed.key_name } };
    });
    // A session whose working directory is gone states it on its own row, and its send is refused as that fact
    // instead of starting a turn the Agent could not run in that directory.
    await check('native-cwd-missing-send-refused', async () => {
      const call = async (action, body = null, payload = {}) => {
        try { return (await fixture.handle(action, { params: payload.params ?? {}, query: payload.query ?? {}, body }, documentId)).body; }
        catch (failure) { return { success: false, status: failure.status, code: failure.code }; }
      };
      fixture.cwdMissing = true;
      fixture.cwdNote = '该会话的工作目录已被删除。';
      try {
        const read = (await call('conversation.read')).data;
        assert.equal(read.cwd_missing, true, 'conversation.read field cwd_missing must report a directory that is gone');
        assert.equal(read.cwd_note, '该会话的工作目录已被删除。', 'conversation.read field cwd_note must carry the reason the host knows');
        assert.equal(read.runtime.can_send_message, false, 'a conversation whose directory is unusable must state that no message can be sent there');
        const listed = (await call('conversations.list')).data.items.find((row) => row.id === C);
        assert.equal(listed.cwd_missing, true, 'the conversation list row must carry the same fact as conversation.read');
        const refused = await call('messages.send', { content: '工作目录不存在的会话不能发送。', loading_id: 'cwd-missing-native' });
        assert.equal(refused.status, 409, `messages.send for a session whose directory is gone answered ${refused.status} (${JSON.stringify(refused)})`);
        assert.equal(refused.code, 'CWD_MISSING', `messages.send must refuse this session with CWD_MISSING, got ${refused.code}`);
        assert.equal(fixture.turn, '', 'a send refused for a missing directory must not start a turn');
      } finally {
        fixture.cwdMissing = false;
        fixture.cwdNote = null;
      }
      const restored = (await call('conversation.read')).data;
      assert.equal(restored.cwd_missing, false, 'a directory that is there again must read as present');
      assert.equal(restored.cwd_note, null, 'a directory that is there carries no note');
      assert.equal(restored.runtime.can_send_message, true, 'a usable directory leaves the conversation able to take a message');
      return { status: 409, code: 'CWD_MISSING' };
    });
    // Whether the host still holds the session a runtime was attached to: every reader of that runtime states it,
    // and a session that is gone or unreadable is reported as itself rather than as a live conversation.
    await check('native-conversation-recovery', async () => {
      const call = async (action) => {
        try { return (await fixture.handle(action, { params: {}, query: {}, body: null }, documentId)).body; }
        catch (failure) { return { success: false, status: failure.status, code: failure.code }; }
      };
      const recoveryOf = async () => ({
        'conversation.read': (await call('conversation.read')).data.recovery,
        'runtime.snapshot': (await call('runtime.snapshot')).recovery,
      });
      const attached = await recoveryOf();
      for (const [reader, stated] of Object.entries(attached)) {
        assert.ok(stated && RECOVERY_STATES.includes(stated.state), `${reader} field recovery.state must be one of ${RECOVERY_STATES.join('/')}, got ${JSON.stringify(stated)}`);
        assert.equal(stated.state, 'attached', `${reader} must report the session it holds as attached`);
        assert.equal(stated.note, null, `${reader} must carry no note while the runtime is attached`);
      }
      const observed = [];
      try {
        for (const state of ['gone', 'unknown']) {
          fixture.recovery = { state, note: `恢复状态：${state}` };
          const reports = await recoveryOf();
          for (const [reader, stated] of Object.entries(reports)) {
            assert.equal(stated.state, state, `${reader} must report recovery ${state}, got ${stated.state}`);
            assert.equal(stated.note, `恢复状态：${state}`, `${reader} must carry the reason it knows with recovery ${state}`);
          }
          observed.push({ state, read: reports['conversation.read'], snapshot: reports['runtime.snapshot'] });
        }
      } finally {
        fixture.recovery = { state: 'attached', note: null };
      }
      assert.equal((await call('conversation.read')).data.recovery.state, 'attached', 'a runtime that is attached again must read as attached');
      return { attached, observed };
    });
    await check('native-document-refresh', async () => {
      const oldDocument = documentId;
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('sendbox-input')).toBeVisible();
      assert.ok(documentId && documentId !== oldDocument);
      assert.ok(calls('host.handshake').length >= 3);
      return { oldDocument, documentId };
    });
    await check('native-no-network', async () => {
      assert.deepEqual(report.blocked, [], 'Native renderer attempted HTTP/WS outside packaged assets');
      assert.deepEqual(report.pageErrors, []);
      return { calls: report.calls.length };
    });
    await context.close();
  } catch (failure) { report.error = failure.message; }
  finally {
    await browser?.close();
    for (const id of required) if (!report.checks.some((check) => check.id === id)) report.checks.push({ id, ok: false, notRun: true });
    report.ok = report.checks.length === required.length && report.checks.every((check) => check.ok);
    report.fixture = { ledgerEntries: fixture.ledger.size, draft: fixture.draft, draftFiles: fixture.draftFiles, attachments: [...fixture.attachments.keys()], queue: fixture.queue.all(), indexSessions: fixture.index.count() };
    await fs.writeFile(path.join(options.output, 'native-browser-report.json'), JSON.stringify(report, null, 2) + '\n');
  }
  console.log(JSON.stringify({ ok: report.ok, diagnosticOnly: report.diagnosticOnly, reportPath: path.join(options.output, 'native-browser-report.json'), failed: report.checks.filter((check) => !check.ok).map((check) => check.id) }));
  return report;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const options = {};
  try {
    const allowed = new Set(['aionui-root', 'renderer-dir', 'output', 'chrome-bin', 'diagnostic-old-release']);
    for (let index = 2; index < process.argv.length; index += 2) {
      const key = process.argv[index].replace(/^--/, '');
      if (!allowed.has(key) || !process.argv[index + 1]) throw new Error(`Invalid argument: ${process.argv[index]}`);
      options[key] = process.argv[index + 1];
    }
    for (const key of ['aionui-root', 'renderer-dir', 'output']) if (!options[key]) throw new Error(`Required --${key}`);
    const report = await runNativeBrowserChecks({ aionuiRoot: path.resolve(options['aionui-root']), rendererDir: path.resolve(options['renderer-dir']), output: path.resolve(options.output), chromeBin: options['chrome-bin'] ?? process.env.CHROME_BIN, diagnosticOldRelease: options['diagnostic-old-release'] });
    process.exitCode = report.ok ? 0 : 1;
  } catch (failure) { console.error(failure.message); process.exitCode = 1; }
}
