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
const required = ['native-handshake', 'real-sendbox-loading-id', 'native-draft-refresh', 'native-send-failure-preserves-draft', 'native-attachment-send', 'native-attachment-draft-refresh', 'native-attachment-contract', 'native-queue-withdrawal', 'native-host-targets-and-index', 'native-document-refresh', 'native-no-network'];

// The offline host the bridge probe drives: one reusable fixture, shared with the unit tests under
// renderer/tests/ so the contract it answers is asserted without a browser.
import { NativeProbeDTO } from './native-fixture.mjs';

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
