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
const P = 'project-probe-1';
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const envelope = (data, status = 200) => ({ status, body: { success: true, data } });
const error = (status, code) => Object.assign(new Error(code), { status, code });
const required = ['native-handshake', 'real-sendbox-loading-id', 'native-draft-refresh', 'native-send-failure-preserves-draft', 'native-attachment-send', 'native-attachment-draft-refresh', 'native-document-refresh', 'native-no-network'];

export class NativeProbeDTO {
  constructor() {
    this.epoch = randomUUID();
    this.seq = 0;
    this.phase = 'idle';
    this.turn = '';
    this.draft = '';
    this.draftFiles = [];
    this.model = 'probe-model-a';
    this.messages = [];
    this.ledger = new Map();
    this.uploads = new Map();
    this.attachments = new Map();
    this.failNextSend = false;
  }
  runtime() {
    return { state: this.phase, can_send_message: this.phase === 'idle', has_task: this.phase === 'running', is_processing: this.phase === 'running', pending_confirmations: 0, turn_id: this.turn, epoch: this.epoch, seq: this.seq, supports_midturn_delivery: false };
  }
  config() { return [{ id: 'model', name: '探针模型', category: 'model', type: 'select', current_value: this.model, options: [{ value: 'probe-model-a', name: '本地合成模型甲' }, { value: 'probe-model-b', name: '本地合成模型乙' }] }]; }
  conversation() {
    return { id: C, name: '中文宿主探针', type: 'acp', project_id: P, created_at: 1, modified_at: Date.now(), model: { provider_id: 'fake', use_model: this.model, model: this.model }, status: this.phase === 'running' ? 'running' : 'finished', fork_capability: { at_turn: false }, prompt_capability: { image: false, audio: false }, extra: { workspace: '/probe', backend: 'dsh', agent_name: 'dsh', custom_workspace: true, cached_config_options: this.config() }, runtime: this.runtime() };
  }
  snapshot() { return { conversation_id: C, epoch: this.epoch, seq: this.seq, runtime: this.runtime(), messages: this.messages, pending: [], draft: { text: this.draft, files: this.draftFiles }, config_options: this.config(), backgroundVerified: false }; }
  publish(name, data) { this.seq++; this.deliver?.({ type: 'event', name, data, epoch: this.epoch, seq: this.seq }); }
  validateFiles(files) {
    assert.ok(Array.isArray(files) && files.length <= 4, 'INVALID_ATTACHMENT');
    for (const file of files) {
      if (file?.kind !== 'upload' || !this.attachments.has(file.path)) throw error(422, 'ATTACHMENT_SOURCE_NOT_ALLOWED');
    }
  }
  async handle(action, payload, documentId) {
    const { params, query, body: rawBody } = payload;
    const body = rawBody ?? {};
    if (params.conversationId !== undefined && params.conversationId !== C) throw error(404, 'CONVERSATION_NOT_FOUND');
    if (action === 'auth.read') return { status: 200, body: { success: true, user: { id: 'probe-user-1', username: 'offline-probe' } } };
    if (action === 'settings.client.read' || action === 'settings.read') return envelope({ language: 'zh-CN', theme: 'light', colorScheme: 'default', notificationEnabled: false, cronNotificationEnabled: false, keepAwake: false, saveUploadToWorkspace: false });
    if (action === 'settings.client.write') return envelope(body.settings ?? body);
    if (action === 'conversations.list') return envelope({ items: [this.conversation()], total: 1, has_more: false });
    if (action === 'conversation.read') return envelope(this.conversation());
    if (action === 'projects.list') return envelope([{ project_id: P, name: '隔离探针项目' }]);
    if (action === 'project.read') return envelope({ project_id: P, name: '隔离探针项目', explorer: { workspace_pe_id: null, entries: [] } });
    if (action === 'messages.list') return envelope({ items: structuredClone(this.messages), oldest_cursor: this.messages[0]?.id ?? null, newest_cursor: this.messages.at(-1)?.id ?? null, has_more_before: false, has_more_after: false });
    if (action === 'messages.latest') return envelope([...this.messages].reverse().find((message) => message.type === query.type) ?? null);
    if (action === 'runtime.ensure') return envelope({ recovered: false, config_options: this.config(), runtime: this.runtime() });
    if (action === 'config.read') return envelope({ config_options: this.config() });
    if (action === 'config.set') {
      if (!['probe-model-a', 'probe-model-b'].includes(body.value)) throw error(422, 'MODEL_NOT_ALLOWED');
      this.model = body.value;
      return envelope({ confirmation: 'observed', config_options: this.config() });
    }
    if (action === 'events.subscribe' || action === 'runtime.snapshot') {
      queueMicrotask(() => this.publish('runtime.snapshot', this.snapshot()));
      return { status: 200, body: this.snapshot() };
    }
    if (action === 'events.unsubscribe') return { status: 200, body: { runtime: this.runtime() } };
    if (action === 'runtime.visible') return envelope({ runtime: this.runtime(), expires_at: Date.now() + 60000 });
    if (action === 'draft.load') return { status: 200, body: { text: this.draft, files: this.draftFiles, conversation_id: C } };
    if (action === 'draft.save') {
      if (typeof body.text !== 'string' || body.text.length > 16384) throw error(413, 'DRAFT_TOO_LARGE');
      this.validateFiles(body.files ?? this.draftFiles);
      this.draft = body.text;
      this.draftFiles = structuredClone(body.files ?? this.draftFiles);
      return { status: 200, body: { text: this.draft, files: this.draftFiles, saved: true } };
    }
    if (action.startsWith('attachment.')) return this.attachment(action, body, documentId);
    if (action === 'messages.send') {
      if (typeof body.loading_id !== 'string' || !body.loading_id || body.loading_id.length > 128) throw error(400, 'INVALID_loading_id');
      if (typeof body.content !== 'string' || !body.content.trim() || body.content.length > 8192) throw error(422, 'INVALID_CONTENT');
      if (this.failNextSend) { this.failNextSend = false; throw error(503, 'TEST_SEND_FAILURE'); }
      this.validateFiles(body.files ?? []);
      if (body.sessions?.length || body.inject_skills?.length) throw error(422, 'UNSUPPORTED_REFERENCE');
      const fingerprint = JSON.stringify({ content: body.content, files: body.files ?? [], sessions: body.sessions ?? [] });
      const previous = this.ledger.get(body.loading_id);
      if (previous) {
        if (previous.fingerprint !== fingerprint) throw error(409, 'SEND_ID_CONFLICT');
        return envelope(previous.result);
      }
      if (this.phase !== 'idle') throw error(409, 'TURN_BUSY');
      this.turn = randomUUID();
      this.phase = 'running';
      const turn = this.turn;
      const msgId = randomUUID();
      this.messages.push({ id: msgId, msg_id: msgId, conversation_id: C, turn_id: turn, type: 'text', position: 'right', status: 'finish', created_at: Date.now(), content: { content: body.content, files: body.files ?? [] } });
      const result = { msg_id: msgId, turn_id: turn, runtime: this.runtime() };
      this.ledger.set(body.loading_id, { fingerprint, result });
      queueMicrotask(() => {
        this.publish('message.userCreated', { conversation_id: C, msg_id: msgId, client_msg_id: body.loading_id, turn_id: turn, content: body.content, position: 'right', status: 'finish', hidden: false, created_at: Date.now() });
        this.publish('message.stream', { type: 'start', data: {}, msg_id: `${turn}-start`, conversation_id: C, turn_id: turn });
        const answerId = `answer-${turn}`;
        this.messages.push({ id: answerId, msg_id: answerId, conversation_id: C, turn_id: turn, type: 'text', position: 'left', status: 'finish', created_at: Date.now(), content: { content: '中文分块：宿主已收到合成任务。' } });
        for (const content of ['中文分块：', '宿主已收到合成任务。']) this.publish('message.stream', { type: 'text', data: { content }, msg_id: answerId, conversation_id: C, turn_id: turn });
        this.phase = 'idle';
        this.publish('message.stream', { type: 'finish', data: { stop_reason: 'end_turn' }, msg_id: `${turn}-finish`, conversation_id: C, turn_id: turn });
        this.publish('turn.completed', { conversation_id: C, session_id: C, turn_id: turn, status: 'finished', outcome: 'completed', state: 'ai_waiting_input', runtime: this.runtime() });
      });
      return envelope(result, 202);
    }
    if (action === 'approvals.list') return envelope([]);
    if (action === 'usage.read') return envelope(null);
    if (['commands.list', 'artifacts.list', 'assistants.list', 'agents.list', 'providers.list', 'mcp.list', 'skills.list', 'cron.list', 'teams.list', 'adapters.list'].includes(action)) return envelope([]);
    throw error(404, 'UNKNOWN_ACTION');
  }
  attachment(action, body, documentId) {
    if (action === 'attachment.begin') {
      if (!['text/plain', 'image/png', 'image/jpeg', 'image/webp', 'application/pdf'].includes(body.mime) || !Number.isSafeInteger(body.size) || body.size > 8 * 1024 * 1024 || !/^[a-f0-9]{64}$/.test(body.sha256)) throw error(422, 'INVALID_UPLOAD');
      const uploadId = randomUUID();
      this.uploads.set(uploadId, { documentId, ...body, chunks: [], length: 0 });
      return { status: 201, body: { uploadId, chunkBytes: 12288, expiresInMs: 300000 } };
    }
    const upload = this.uploads.get(body.uploadId);
    if (!upload || upload.documentId !== documentId) throw error(410, 'UPLOAD_EXPIRED');
    if (action === 'attachment.chunk') {
      const bytes = Buffer.from(body.data, 'base64');
      if (body.offset !== upload.length || bytes.length > 12288 || !bytes.length || upload.length + bytes.length > upload.size) throw error(409, 'UPLOAD_OFFSET_CONFLICT');
      upload.chunks.push(bytes);
      upload.length += bytes.length;
      return { status: 200, body: { offset: upload.length } };
    }
    if (action === 'attachment.abort') { this.uploads.delete(body.uploadId); return { status: 200, body: { aborted: true } }; }
    const bytes = Buffer.concat(upload.chunks);
    if (bytes.length !== upload.size || sha(bytes) !== upload.sha256) throw error(422, 'UPLOAD_HASH_MISMATCH');
    const attachmentRef = `probe-upload:${body.uploadId}`;
    this.attachments.set(attachmentRef, { ...upload, bytes });
    this.uploads.delete(body.uploadId);
    return { status: 201, body: { attachmentRef, path: attachmentRef, name: upload.name, mime: upload.mime, size: upload.size, sha256: upload.sha256, source: 'mobile-upload' } };
  }
}

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
      const persisted = fixture.draftFiles.some((item) => item.kind === 'upload' && item.path === reference);
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('sendbox-input')).toHaveValue(text);
      assert.ok(persisted, 'Native draft.save did not preserve the actual uploaded ChatFileRef');
      await expect(page.getByText(reference, { exact: false }).filter({ visible: true }).last()).toBeVisible();
      const sends = calls('messages.send').length;
      await page.getByTestId('sendbox-send-btn').click();
      await poll(() => calls('messages.send').length > sends && Boolean(calls('messages.send').at(-1).response), 'Restored attachment must actually be submitted by SendBox');
      const submitted = JSON.parse(calls('messages.send').at(-1).raw).payload.body;
      assert.equal(submitted.content, text);
      assert.ok(submitted.files.some((item) => item.kind === 'upload' && item.path === reference));
      assert.equal(calls('messages.send').at(-1).response.status, 202);
      await shot('native-attachment-draft-restored');
      return { reference, submitted };
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
    report.fixture = { ledgerEntries: fixture.ledger.size, draft: fixture.draft, draftFiles: fixture.draftFiles, attachments: [...fixture.attachments.keys()] };
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
