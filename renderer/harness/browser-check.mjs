// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { AUTH_METHODS, HISTORY_STATES, RECOVERY_STATES, SEEDED_HOST_PASSWORD } from './host-fixture.mjs';

const conversationId = 'conv-probe-1';
const apiBase = `/api/conversations/${conversationId}`;
const requiredChecks = [
  'fresh-renderer', 'model-catalog-and-ui-switch', 'unknown-model-rejected',
  'chinese-stream-and-terminal', 'ui-allow-with-effect', 'duplicate-approval-rejected',
  'history-readonly-after-refresh', 'ui-reject-without-effect', 'pending-refresh-and-ui-reject',
  'pending-ui-cancel-without-effect', 'stale-approval-rejected', 'stream-ui-cancel',
  'old-turn-cancel-cannot-stop-new-turn', 'queue-withdrawal', 'index-clear', 'host-targets',
  'history-pages-and-clear', 'host-auth-fields', 'cwd-missing-send-refused', 'conversation-recovery',
  'ws-reconnect-history-recovery', 'network-and-page-errors',
];

export function isAllowedURL(input, origin, websocket = false) {
  try {
    const url = new URL(input);
    const base = new URL(origin);
    return !url.username && !url.password && url.hostname === base.hostname && url.port === base.port &&
      url.protocol === (websocket ? 'ws:' : base.protocol) && (!websocket || url.pathname === '/ws');
  } catch { return false; }
}

export async function runBrowserChecks({ aionuiRoot, chromeBin, output, url, backend, build }) {
  const require = createRequire(path.join(aionuiRoot, 'package.json'));
  const { chromium, expect } = require('@playwright/test');
  const report = { ok: false, url, buildId: build.buildId, checks: [], screenshots: [], requests: [], frames: [], sideEffects: [], blocked: [], pageErrors: [], console: [] };
  let browser;
  let context;
  let page;
  const origin = new URL(url).origin;
  const unwrap = (body) => body?.data ?? body;
  const json = async (relative, init = {}) => {
    assert.ok(relative.startsWith('/api/'), 'Only explicit API diagnostics allowed');
    const response = await fetch(new URL(relative, backend.url), { ...init, signal: AbortSignal.timeout(10000), headers: { 'content-type': 'application/json', ...backend.probeHeaders, ...init.headers } });
    return { status: response.status, body: await response.json() };
  };
  const conversation = async () => {
    const result = await json(apiBase);
    assert.equal(result.status, 200);
    return unwrap(result.body);
  };
  const runtime = async () => (await conversation()).runtime;
  const probeStatus = async () => {
    const result = await json('/api/probe/status');
    assert.equal(result.status, 200);
    return unwrap(result.body);
  };
  const modelStream = async (turnId) => {
    const status = await probeStatus();
    const streams = status.fake_streams.filter((item) => item.turn_id === turnId);
    assert.ok(streams.length <= 1, 'Text-only probe must have exactly one model request');
    return { status, stream: streams[0] };
  };
  const pending = async () => {
    const result = await json(`${apiBase}/confirmations`);
    assert.equal(result.status, 200);
    const data = unwrap(result.body);
    assert.ok(Array.isArray(data), 'Confirmations must be an authoritative list');
    return data;
  };
  const poll = (fn, message, timeout = 30000) => expect.poll(fn, { message, timeout, intervals: [50, 100, 250, 500] }).toBe(true);
  const screenshot = async (name) => {
    const filename = `${String(report.screenshots.length + 1).padStart(2, '0')}-${name}.png`;
    await page.screenshot({ path: path.join(output, filename), fullPage: true });
    report.screenshots.push(filename);
    await fs.writeFile(path.join(output, `${name}-dom.json`), JSON.stringify({ body: await page.locator('body').innerText(), permissions: await permissionControls() }, null, 2));
  };
  const permissionControls = () => page.locator('button[data-testid^="message-acp-permission-option-"]').evaluateAll((elements) => elements.map((el) => ({
    id: el.getAttribute('data-testid'), rowId: el.closest('[data-message-type="acp_permission"]')?.id,
    disabled: el.matches(':disabled') || el.getAttribute('aria-disabled') === 'true',
    visible: el.checkVisibility() && el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0,
    command: el.closest('[data-testid="message-acp-permission-card"]')?.querySelector('code')?.textContent ?? null,
  })));
  const assertReadonly = async () => {
    await poll(async () => (await pending()).length === 0, 'No authoritative approvals remain');
    await poll(async () => (await permissionControls()).every((item) => !item.visible || item.disabled), 'Historical permission cards must have no enabled buttons');
  };
  const waitIdle = async () => {
    await poll(async () => { const value = await runtime(); return value.state === 'idle' && !value.turn_id && value.can_send_message === true; }, 'Authoritative terminal runtime must be idle');
    await expect(page.locator('.sendbox-stop-button')).toHaveCount(0);
  };
  const sendUI = async (text) => {
    const box = page.getByTestId('sendbox-input');
    await expect(box).toBeVisible();
    await box.fill(text);
    await expect(box).toHaveValue(text);
    await expect(page.getByTestId('sendbox-send-btn')).toBeEnabled();
    const [response] = await Promise.all([
      page.waitForResponse((response) => new URL(response.url()).pathname === `${apiBase}/messages` && response.request().method() === 'POST', { timeout: 15000 }),
      page.getByTestId('sendbox-send-btn').click(),
    ]);
    assert.equal(response.status(), 200, `SendBox submission failed: ${await response.text()}`);
    const result = unwrap(await response.json());
    assert.ok(result.turn_id && result.msg_id, 'UI send must return backend message and turn identity');
    const sent = response.request().postDataJSON();
    assert.equal(sent.content, text, 'Real SendBox must send exactly the filled Chinese text');
    return result;
  };
  const waitApproval = async () => {
    await poll(async () => (await pending()).length === 1, 'Real dsh must request one approval');
    const approval = (await pending())[0];
    assert.ok(approval.request_id && approval.epoch, 'Pending item missing authoritative request_id/epoch');
    assert.equal(approval.conversation_id, conversationId);
    return approval;
  };
  const answerUI = async (approval, option) => {
    const options = approval.options ?? approval.data?.options ?? [];
    const selected = options.find((item) => item.option_id === option || item.id === option);
    assert.ok(selected, `Original option ${option} must be preserved`);
    await poll(async () => (await permissionControls()).filter((item) => item.id === `message-acp-permission-option-${option}` && item.visible && !item.disabled).length === 1, 'Only the current live permission may be actionable');
    const current = (await permissionControls()).filter((item) => item.id === `message-acp-permission-option-${option}` && item.visible && !item.disabled);
    assert.equal(current.length, 1, 'Exactly one original option button is enabled');
    const control = current[0];
    assert.ok(control.rowId && !control.rowId.includes('history-permission'), 'Historical seed cannot be selected as live');
    assert.equal(control.command, approval.tool_call.raw_input.command, 'Actionable card must display this exact live request command, including its unique target');
    const row = page.locator(`[id=${JSON.stringify(control.rowId)}][data-message-type="acp_permission"]`);
    const button = row.locator(`button[data-testid=${JSON.stringify(`message-acp-permission-option-${option}`)}]`);
    await expect(button).toHaveCount(1);
    await expect(button).toBeEnabled();
    const [response] = await Promise.all([
      page.waitForResponse((response) => new URL(response.url()).pathname === `${apiBase}/confirmations/${encodeURIComponent(approval.request_id)}/confirm` && response.request().method() === 'POST'),
      button.click(),
    ]);
    assert.equal(response.status(), 200, `UI approval failed: ${await response.text()}`);
    const submitted = response.request().postDataJSON();
    assert.equal(submitted.epoch, approval.epoch);
    assert.equal(submitted.data, option);
    assert.equal(`message-${submitted.msg_id}`, control.rowId, 'The clicked row must be the submitted permission message');
    return submitted;
  };
  const effect = async (turnId) => {
    const status = await json('/api/probe/status');
    assert.equal(status.status, 200);
    const target = unwrap(status.body).side_effects.find((item) => item.turn_id === turnId);
    assert.ok(target, 'Probe status must identify the unique target for this turn');
    report.sideEffects.push({ observedAt: new Date().toISOString(), ...target });
    return target.exists ? target.content : null;
  };
  const eventsFor = (turnId, name) => {
    const unique = new Map();
    for (const frame of report.frames) {
      if (frame.name === name && (frame.data?.turn_id === turnId || frame.data?.backend_turn_id === turnId)) {
        assert.ok(frame.epoch && Number.isSafeInteger(frame.seq), 'Business event missing epoch/seq');
        unique.set(`${frame.epoch}:${frame.seq}`, frame);
      }
    }
    return [...unique.values()];
  };
  const check = async (id, fn) => {
    try {
      const details = await fn();
      report.checks.push({ id, ok: true, details });
      return details;
    } catch (error) {
      report.checks.push({ id, ok: false, error: error.message });
      await screenshot(`failure-${id}`).catch(() => {});
      if ((await runtime()).state !== 'idle') {
        try {
          await page.getByTestId('sendbox-input').fill('');
          await page.locator('.sendbox-stop-button').click();
          await waitIdle();
          await page.reload({ waitUntil: 'domcontentloaded' });
          await expect(page.getByTestId('sendbox-input')).toBeVisible();
        } catch (cleanup) { report.checks.at(-1).recoveryError = cleanup.message; }
      }
      return undefined;
    }
  };
  try {
    browser = await chromium.launch({ executablePath: chromeBin, headless: true, chromiumSandbox: true, args: [
      '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1', '--disable-background-networking', '--disable-component-update', '--no-first-run',
    ] });
    context = await browser.newContext({ serviceWorkers: 'block', locale: 'zh-CN', timezoneId: 'UTC', viewport: { width: 1440, height: 1000 } });
    await context.route('**/*', async (route) => {
      if (isAllowedURL(route.request().url(), origin)) return route.continue();
      report.blocked.push({ transport: 'http', url: route.request().url() });
      return route.abort('blockedbyclient');
    });
    assert.equal(typeof context.routeWebSocket, 'function', 'Playwright WebSocket routing is required for precise-origin enforcement');
    const sockets = new Set();
    await context.routeWebSocket(/.*/, (socket) => {
      if (!isAllowedURL(socket.url(), origin, true)) { report.blocked.push({ transport: 'ws', url: socket.url() }); socket.close(); return; }
      const server = socket.connectToServer();
      sockets.add(server);
      server.onClose((code, reason) => { sockets.delete(server); socket.close({ code, reason }); });
      server.onMessage((message) => {
        try {
          const frame = JSON.parse(String(message));
          if (frame.name) report.frames.push(frame);
        } catch {}
        socket.send(message);
      });
    });
    page = await context.newPage();
    page.setDefaultTimeout(15000);
    page.on('pageerror', (error) => report.pageErrors.push(error.message));
    page.on('console', (message) => report.console.push({ type: message.type(), text: message.text().slice(0, 2000) }));
    page.on('response', (response) => { if (new URL(response.url()).pathname.startsWith('/api/')) report.requests.push({ method: response.request().method(), url: response.url(), status: response.status() }); });
    await page.goto(`${origin}/#/conversation/${conversationId}`, { waitUntil: 'domcontentloaded' });
    await check('fresh-renderer', async () => {
      const result = await fetch(new URL('/renderer-build.json', origin));
      assert.equal(result.status, 200);
      const served = await result.json();
      const { buildId: _buildId, contractSha256: _contractSha256, ...identity } = build;
      assert.deepEqual(served, identity, 'WebHost did not serve this run\'s renderer');
      await expect(page.getByTestId('sendbox-input')).toBeVisible();
      const history = page.locator('[id="message-history-permission-1"][data-message-type="acp_permission"]');
      await expect(history).toBeVisible();
      await expect(history).toContainText('历史审批（只读）');
      await expect(history.getByTestId('message-acp-permission-option-allow-once')).toBeDisabled();
      const settings = unwrap((await json('/api/settings/client')).body);
      assert.equal(settings.language, 'zh-CN');
      await screenshot('baseline');
      return { buildId: build.buildId };
    });
    await check('model-catalog-and-ui-switch', async () => {
      await poll(async () => { const value = await runtime(); return value.can_send_message === true && value.state === 'idle'; }, 'Renderer automatic runtime.ensure must finish before inspecting model catalog');
      const config = await json(`${apiBase}/config-options`);
      assert.equal(config.status, 200);
      const data = unwrap(config.body);
      const models = (data.config_options ?? data.options ?? data).find((item) => item.id === 'model');
      assert.ok(models, 'Missing real dsh model option');
      const options = models.options.flatMap((item) => item.options ?? [item]);
      for (const id of ['fake-1', 'fake-2']) assert.ok(options.some((item) => item.value === JSON.stringify(['fake', id])), `Missing local model ${id}`);
      const selector = page.getByTestId('acp-model-selector').filter({ visible: true });
      await expect(selector).toHaveCount(1);
      await selector.click();
      if ((data.config_options ?? data.options ?? data).some((item) => item.category === 'thought_level')) {
        const submenu = page.locator('.arco-dropdown-menu-pop-header').filter({ hasText: /模型|Model/ }).filter({ visible: true });
        await expect(submenu).toHaveCount(1);
        await submenu.click();
      }
      const option = options.find((item) => item.value === JSON.stringify(['fake', 'fake-2']));
      const label = option.name ?? option.label ?? 'Fake Two';
      const item = page.locator('.dropdown-search-scroll .arco-menu-item, .dropdown-search-scroll .arco-dropdown-menu-item').filter({ has: page.getByText(label, { exact: true }) }).filter({ visible: true });
      await expect(item).toHaveCount(1);
      const responsePromise = page.waitForResponse((response) => new URL(response.url()).pathname === `${apiBase}/config-options/model` && response.request().method() === 'PUT');
      await item.click();
      const response = await responsePromise;
      assert.equal(response.status(), 200);
      assert.equal(response.request().postDataJSON().value, option.value);
      const confirmed = unwrap(await response.json());
      assert.equal(confirmed.confirmation, 'observed');
      assert.equal(confirmed.config_options.find((item) => item.id === 'model').current_value, option.value);
      await expect(selector).toContainText(label);
      await screenshot('model-switched');
      assert.ok(options.every((item) => ['fake-1', 'fake-2'].some((id) => item.value === JSON.stringify(['fake', id]))), 'Nonlocal model route exposed');
      return { options, selected: option.value };
    });
    await check('unknown-model-rejected', async () => {
      const response = await json(`${apiBase}/config-options/model`, { method: 'PUT', body: JSON.stringify({ value: '["remote","never-call"]' }) });
      assert.ok(response.status >= 400, 'Unknown model must fail with non-2xx');
      return response;
    });
    let textTurn;
    await check('chinese-stream-and-terminal', async () => {
      textTurn = await sendUI('stream 中文提交级输出与终态验收。');
      let activeObservation;
      await poll(async () => {
        const observed = await modelStream(textTurn.turn_id);
        if (observed.stream?.state === 'streaming' && observed.stream.chunks_sent >= 3 && !observed.stream.done_sent) { activeObservation = observed; return true; }
        return false;
      }, 'Model must actually stream at least three chunks before completion');
      assert.equal(activeObservation.status.runtime.turn_id, textTurn.turn_id);
      assert.equal(activeObservation.status.runtime.state, 'running');
      assert.equal(activeObservation.stream.stream, true);
      assert.equal(activeObservation.status.text_delivery.acp, 'committed_assistant_message');
      assert.equal(activeObservation.status.text_delivery.token_level, false);
      await waitIdle();
      await poll(async () => eventsFor(textTurn.turn_id, 'turn.completed').some((frame) => frame.data.outcome === 'completed' && frame.data.stop_reason === 'end_turn'), 'Expected real end_turn rather than merely an idle error runtime');
      const completed = await modelStream(textTurn.turn_id);
      assert.equal(completed.stream.state, 'completed');
      assert.equal(completed.stream.chunks_sent, 120);
      assert.equal(completed.stream.chunks_planned, 120);
      assert.equal(completed.stream.done_sent, true);
      const chunks = eventsFor(textTurn.turn_id, 'message.stream').filter((frame) => frame.data.type === 'text');
      assert.equal(chunks.length, 1, 'This dsh ACP delivers exactly one committed assistant text message, not token deltas');
      const expected = Array.from({ length: 120 }, (_, index) => `真实模型流片段${index} `).join('');
      assert.equal(chunks[0].data.data.content, expected);
      const textRow = page.locator('[data-message-type="text"][data-message-position="left"]').filter({ hasText: '真实模型流片段119' });
      await expect(textRow).toHaveCount(1);
      await expect(textRow).toContainText(expected.trim());
      await screenshot('chinese-committed-terminal');
      return { label: '中文提交级输出与终态', tokenLevelTextSupported: false, textDelivery: completed.status.text_delivery, activeObservation, modelStream: completed.stream, turn: textTurn, realAcpTextNotifications: chunks.length };
    });
    let allowed;
    let allowedApproval;
    let allowedAnswer;
    await check('ui-allow-with-effect', async () => {
      allowed = await sendUI('请允许 allow 本次隔离审批写入。');
      allowedApproval = await waitApproval();
      assert.equal(await effect(allowed.turn_id), null, 'No effect before approval');
      const summaries = page.locator('.tool-group-summary__header').filter({ visible: true });
      for (let index = 0; index < await summaries.count(); index++) {
        const header = summaries.nth(index);
        if (await header.locator('.tool-group-summary__arrow--open').count() === 0) await header.click();
      }
      const toolDescriptions = page.locator('.tool-group-summary__body span.cursor-pointer').filter({ hasText: allowedApproval.tool_call.raw_input.command }).filter({ visible: true });
      for (let index = 0; index < await toolDescriptions.count(); index++) await toolDescriptions.nth(index).click();
      await expect(page.locator('.tool-detail-content').filter({ hasText: 'danger-full-access' }).filter({ visible: true })).toHaveCount(1);
      await expect(page.locator('.tool-detail-content').filter({ hasText: 'danger-full-access' })).toContainText(allowedApproval.tool_call.raw_input.justification);
      const duplicateLive = (await permissionControls()).filter((item) => item.id === 'message-acp-permission-option-allow-once' && item.visible && !item.disabled).length > 1;
      await screenshot('allow-pending');
      if (duplicateLive) {
        await page.reload({ waitUntil: 'domcontentloaded' });
        await expect(page.getByTestId('sendbox-input')).toBeVisible();
        assert.equal((await waitApproval()).request_id, allowedApproval.request_id);
      }
      allowedAnswer = await answerUI(allowedApproval, 'allow-once');
      await waitIdle();
      assert.equal(await effect(allowed.turn_id), `effect:${allowed.turn_id}\n`);
      const tools = eventsFor(allowed.turn_id, 'message.stream').filter((frame) => frame.data.type === 'acp_tool_call');
      assert.ok(tools.some((frame) => frame.data.data?.update?.status === 'in_progress'), 'Missing true running tool frame');
      assert.ok(tools.some((frame) => frame.data.data?.update?.status === 'completed'), 'Missing true completed tool frame');
      await screenshot('allow-terminal');
      assert.equal(duplicateLive, false, 'Live approval was duplicated; diagnostic reload allowed actual side-effect verification but does not pass the live UI gate');
      return { turn: allowed, request_id: allowedApproval.request_id, toolFrames: tools.length };
    });
    await check('duplicate-approval-rejected', async () => {
      const original = (await fs.readFile(path.join(output, 'backend/acp-frames.jsonl'), 'utf8')).split('\n').filter(Boolean).map((line) => JSON.parse(line));
      const replies = original.filter((frame) => frame.direction === 'out' && frame.result?.outcome?.outcome === 'selected').length;
      const response = await json(`${apiBase}/confirmations/${encodeURIComponent(allowedApproval.request_id)}/confirm`, { method: 'POST', body: JSON.stringify({ ...allowedAnswer, data: 'reject-once' }) });
      assert.ok(response.status >= 400, 'Duplicate conflicting approval must fail');
      const replay = await json(`${apiBase}/confirmations/${encodeURIComponent(allowedApproval.request_id)}/confirm`, { method: 'POST', body: JSON.stringify(allowedAnswer) });
      assert.equal(replay.status, 200, 'Identical retry must return the original receipt, not execute again');
      const after = (await fs.readFile(path.join(output, 'backend/acp-frames.jsonl'), 'utf8')).split('\n').filter(Boolean).map((line) => JSON.parse(line));
      assert.equal(after.filter((frame) => frame.direction === 'out' && frame.result?.outcome?.outcome === 'selected').length, replies, 'Retry sent another ACP answer');
      assert.equal(await effect(allowed.turn_id), `effect:${allowed.turn_id}\n`);
      assert.equal(backend.state.pending().length, 0);
      return { conflict: response, identicalReceipt: replay, protocolReplies: replies };
    });
    await check('history-readonly-after-refresh', async () => {
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('sendbox-input')).toBeVisible();
      const historicalApproval = page.locator(`[id=${JSON.stringify(`message-${allowedApproval.request_id}`)}][data-message-type="acp_permission"]`);
      await expect(historicalApproval).toBeVisible();
      await expect(historicalApproval.getByTestId('message-acp-permission-option-allow-once')).toBeDisabled();
      await assertReadonly();
      const response = await json(`${apiBase}/confirmations/history-read-only/confirm`, { method: 'POST', body: JSON.stringify({ data: 'allow-once', epoch: 'historical', msg_id: 'history-permission-1' }) });
      assert.equal(response.status, 404, 'Historical seed must not become a live request');
      await screenshot('history-readonly');
      return { historicalRequest: allowedApproval.request_id, seedRejection: response };
    });
    await check('ui-reject-without-effect', async () => {
      const turn = await sendUI('请拒绝 reject 独立目标写入。');
      const approval = await waitApproval();
      const duplicateLive = (await permissionControls()).filter((item) => item.id === 'message-acp-permission-option-reject-once' && item.visible && !item.disabled).length > 1;
      if (duplicateLive) {
        await screenshot('reject-live-duplicate');
        await page.reload({ waitUntil: 'domcontentloaded' });
        await expect(page.getByTestId('sendbox-input')).toBeVisible();
        assert.equal((await waitApproval()).request_id, approval.request_id);
      }
      await answerUI(approval, 'reject-once');
      await waitIdle();
      assert.equal(await effect(turn.turn_id), null);
      const failed = eventsFor(turn.turn_id, 'message.stream').some((frame) => frame.data.type === 'acp_tool_call' && frame.data.data?.update?.status === 'failed');
      assert.ok(failed, 'Rejected real dsh tool must remain failed');
      await screenshot('reject-terminal');
      assert.equal(duplicateLive, false, 'Live reject approval was duplicated; reload verified real rejection but does not pass the live UI gate');
      return { turn, request_id: approval.request_id };
    });
    await check('pending-refresh-and-ui-reject', async () => {
      const turn = await sendUI('刷新后拒绝 reject 这个独立审批。');
      const before = await waitApproval();
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('sendbox-input')).toBeVisible();
      const after = await waitApproval();
      assert.equal(after.request_id, before.request_id);
      assert.equal(after.epoch, before.epoch);
      await screenshot('pending-after-refresh');
      await answerUI(after, 'reject-once');
      await waitIdle();
      assert.equal(await effect(turn.turn_id), null);
      return { turn, request_id: after.request_id };
    });
    let cancelledApproval;
    await check('pending-ui-cancel-without-effect', async () => {
      const turn = await sendUI('等待审批 allow 然后取消，不允许副作用。');
      cancelledApproval = await waitApproval();
      await page.locator('.sendbox-stop-button').click();
      await waitIdle();
      await assertReadonly();
      assert.equal(await effect(turn.turn_id), null);
      await screenshot('pending-cancelled');
      return { turn, request_id: cancelledApproval.request_id };
    });
    await check('stale-approval-rejected', async () => {
      const responses = [];
      for (const ref of [cancelledApproval.request_id, 'fake-old-request']) {
        const response = await json(`${apiBase}/confirmations/${encodeURIComponent(ref)}/confirm`, { method: 'POST', body: JSON.stringify({ data: 'allow-once', epoch: cancelledApproval.epoch, msg_id: 'forged-old-message' }) });
        assert.ok(response.status >= 400, 'Cancelled or forged request must fail');
        responses.push(response);
      }
      return responses;
    });
    let cancelledTurn;
    await check('stream-ui-cancel', async () => {
      cancelledTurn = await sendUI('cancel stream 中文生成中取消，允许部分文本在取消结果前提交。');
      let beforeCancel;
      await poll(async () => {
        const observed = await modelStream(cancelledTurn.turn_id);
        if (observed.stream?.state === 'streaming' && observed.stream.chunks_sent >= 3 && !observed.stream.done_sent) { beforeCancel = observed; return true; }
        return false;
      }, 'Use real model stream diagnostics to stop during generation, not after ACP text commit');
      assert.equal(beforeCancel.status.runtime.turn_id, cancelledTurn.turn_id);
      assert.equal(beforeCancel.status.runtime.state, 'running');
      const [cancelResponse] = await Promise.all([
        page.waitForResponse((response) => new URL(response.url()).pathname === `${apiBase}/cancel` && response.request().method() === 'POST'),
        page.locator('.sendbox-stop-button').click(),
      ]);
      assert.equal(cancelResponse.status(), 200);
      assert.equal(cancelResponse.request().postDataJSON().turn_id, cancelledTurn.turn_id);
      await waitIdle();
      await poll(async () => eventsFor(cancelledTurn.turn_id, 'turn.completed').some((frame) => frame.data.outcome === 'cancelled' && frame.data.stop_reason === 'cancelled'), 'Real ACP result must confirm cancelled');
      await poll(async () => { const { stream } = await modelStream(cancelledTurn.turn_id); return stream.state === 'aborted' && stream.abort_observed && !stream.done_sent; }, 'Model generation must observe abort without DONE');
      const stopped = await modelStream(cancelledTurn.turn_id);
      assert.ok(stopped.stream.chunks_sent >= 3 && stopped.stream.chunks_sent < stopped.stream.chunks_planned);
      const terminal = eventsFor(cancelledTurn.turn_id, 'turn.completed').at(-1);
      const texts = eventsFor(cancelledTurn.turn_id, 'message.stream').filter((frame) => frame.data.type === 'text');
      assert.ok(texts.every((frame) => frame.seq < terminal.seq), 'Partial commit must precede cancelled terminal');
      const cursor = terminal.seq;
      const chunkCount = stopped.stream.chunks_sent;
      const rechecks = [];
      const stableSince = Date.now();
      await poll(async () => {
        const observed = await modelStream(cancelledTurn.turn_id);
        assert.equal(observed.stream.chunks_sent, chunkCount, 'Aborted model chunk count must stop increasing');
        assert.equal(observed.stream.state, 'aborted');
        const events = unwrap((await json(`/api/probe/events?since=${cursor}`)).body);
        assert.ok(!events.some((event) => event.data?.turn_id === cancelledTurn.turn_id && event.name === 'message.stream' && ['text', 'acp_tool_call'].includes(event.data.type)), 'No text/tool events may follow cancelled terminal');
        rechecks.push({ observedAt: Date.now(), ...observed.stream });
        return Date.now() - stableSince >= 750;
      }, 'Aborted model and terminal event stream remain stable across five model emission intervals');
      if (texts.length) {
        const partial = texts.map((frame) => frame.data.data.content).join('');
        const paragraph = page.getByText(partial.trim(), { exact: true }).filter({ visible: true });
        await expect(paragraph).toHaveCount(1);
        await expect(paragraph).toBeVisible();
      }
      await page.getByTestId('sendbox-input').fill('取消后可再次发送的草稿');
      await expect(page.getByTestId('sendbox-send-btn')).toBeEnabled();
      await expect(page.locator('.sendbox-stop-button')).toHaveCount(0);
      await page.getByTestId('sendbox-input').fill('');
      await screenshot('stream-cancelled');
      return { turn: cancelledTurn, beforeCancel, modelStream: stopped.stream, realPartialTextNotifications: texts.length, terminal: terminal.data, stoppedChunkCount: chunkCount, rechecks, tokenLevelTextSupported: false };

    });
    await check('old-turn-cancel-cannot-stop-new-turn', async () => {
      const turn = await sendUI('慢速 slow 新回合不能被旧取消中断。');
      await poll(async () => eventsFor(turn.turn_id, 'message.stream').some((frame) => frame.data.type === 'acp_tool_call') && (await runtime()).turn_id === turn.turn_id, 'New real dsh tool must be active');
      const response = await json(`${apiBase}/cancel`, { method: 'POST', body: JSON.stringify({ turn_id: cancelledTurn.turn_id }) });
      assert.ok(response.status >= 400, 'Old turn cancellation must fail');
      assert.equal((await runtime()).turn_id, turn.turn_id, 'Old cancellation affected new turn');
      await page.locator('.sendbox-stop-button').click();
      await waitIdle();
      return { turn, response };
    });
    // The message queue over HTTP: one message handed to another conversation is only queued, and its
    // withdrawal keeps the state `withdrawn` instead of the mark a conversation's own delivery writes.
    await check('queue-withdrawal', async () => {
      const refusals = [];
      const refused = async (label, relative, method, payload, status, code) => {
        const result = await json(relative, { method, ...(payload === undefined ? {} : { body: JSON.stringify(payload) }) });
        assert.equal(result.status, status, `${label} answered ${result.status}, expected ${status} ${code} (${JSON.stringify(result.body)})`);
        assert.equal(result.body.code, code, `${label} code must be ${code}, got ${result.body.code}`);
        refusals.push({ label, status, code });
      };
      const sent = await json('/api/queue', { method: 'POST', body: JSON.stringify({ conversation_id: conversationId, kind: 'notify', text: '来自离线探针的队列通知。' }) });
      assert.equal(sent.status, 200, `queue.send answered ${sent.status} (${JSON.stringify(sent.body)})`);
      const queued = unwrap(sent.body);
      assert.ok(typeof queued.queue_id === 'string' && queued.queue_id, 'queue.send field queue_id must identify the queued message');
      assert.equal(queued.conversation_id, conversationId, 'queue.send field conversation_id must name the target conversation');
      assert.equal(queued.kind, 'notify', 'queue.send field kind must be the kind that was queued');
      assert.equal(queued.state, 'pending', `queue.send field state must be pending, got ${queued.state}`);
      assert.equal(queued.queued, true, 'queue.send field queued must claim the queue it wrote');
      assert.equal(queued.delivered, false, 'queue.send field delivered must stay false: nothing was delivered');
      const listed = unwrap((await json('/api/queue')).body);
      assert.equal(listed.items.length, 1, 'queue.list must show the message waiting for its conversation');
      assert.equal(listed.items[0].state, 'pending', 'queue.list row field state must be pending');
      assert.equal(listed.items[0].pending, true, 'queue.list row field pending must be true while it waits');
      assert.deepEqual(listed.stats, { stored: 1, pending: 1, delivered: 0, dropped: 0 }, `queue.list field stats must count what the queue holds, got ${JSON.stringify(listed.stats)}`);
      const cancelled = await json(`/api/queue/${encodeURIComponent(queued.queue_id)}`, { method: 'DELETE', body: '{}' });
      assert.equal(cancelled.status, 200, `queue.cancel answered ${cancelled.status} (${JSON.stringify(cancelled.body)})`);
      const withdrawn = unwrap(cancelled.body);
      assert.equal(withdrawn.cancelled, true, 'queue.cancel field cancelled must be true');
      assert.equal(withdrawn.queue_id, queued.queue_id, 'queue.cancel field queue_id must be the withdrawn message');
      assert.equal(withdrawn.delivered, false, 'queue.cancel field delivered must never claim a delivery');
      assert.equal(unwrap((await json('/api/queue')).body).items.length, 0, 'a withdrawn message must never be listed as waiting');
      const stored = (await probeStatus()).queue.rows.find((row) => row.queue_id === queued.queue_id);
      assert.ok(stored, 'queue.cancel must keep the withdrawn row in the queue rather than drop it');
      assert.equal(stored.state, 'withdrawn', `queue.cancel must record the row as withdrawn, got ${stored.state}`);
      await refused('queue.cancel of a withdrawn id', `/api/queue/${encodeURIComponent(queued.queue_id)}`, 'DELETE', {}, 404, 'QUEUE_NOT_FOUND');
      await refused('queue.cancel of a malformed id', `/api/queue/${'x'.repeat(129)}`, 'DELETE', {}, 422, 'INVALID_QUEUE_ID');
      await refused('queue.send with an unknown kind', '/api/queue', 'POST', { conversation_id: conversationId, kind: 'later', text: '中文' }, 422, 'INVALID_KIND');
      await refused('queue.send with empty text', '/api/queue', 'POST', { conversation_id: conversationId, kind: 'request', text: '   ' }, 422, 'INVALID_CONTENT');
      await refused('queue.send to a conversation this host does not hold', '/api/queue', 'POST', { conversation_id: 'conv-other', kind: 'notify', text: '中文' }, 404, 'CONVERSATION_NOT_FOUND');
      return { queueId: queued.queue_id, state: stored.state, refusals };
    });
    await check('index-clear', async () => {
      const first = await json('/api/index/clear', { method: 'POST', body: '{}' });
      assert.equal(first.status, 200, `index.clear answered ${first.status} (${JSON.stringify(first.body)})`);
      const cleared = unwrap(first.body);
      assert.deepEqual(cleared, { cleared: true, sessions_before: 1, sessions: 0 }, `index.clear must report the index it held and the one it leaves, got ${JSON.stringify(cleared)}`);
      const second = await json('/api/index/clear', { method: 'POST', body: '{}' });
      const again = unwrap(second.body);
      assert.deepEqual(again, { cleared: true, sessions_before: 0, sessions: 0 }, `index.clear on an empty index must report zero, not invent sessions, got ${JSON.stringify(again)}`);
      return { cleared, again };
    });
    await check('host-targets', async () => {
      const result = await json('/api/hosts');
      assert.equal(result.status, 200, `hosts.list answered ${result.status} (${JSON.stringify(result.body)})`);
      const hosts = unwrap(result.body);
      assert.ok(Array.isArray(hosts.items) && hosts.items.length === 3, `hosts.list must list every saved host, got ${JSON.stringify(hosts)}`);
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
      return { targets: hosts.items.map((row) => `${row.os_type}/${row.agent_target}/${row.target}/${row.target_supported}`) };
    });
    // The history store over HTTP: every page of the conversation states how much of that store the host could
    // read, one older page is served below the window, and the store's clear entry is its own — separate from
    // the session index cache the maintenance page drops through index.clear.
    await check('history-pages-and-clear', async () => {
      const refusals = [];
      const refused = async (label, relative, init, status, code) => {
        const result = await json(relative, init);
        assert.equal(result.status, status, `${label} answered ${result.status}, expected ${status} ${code} (${JSON.stringify(result.body)})`);
        assert.equal(result.body.code, code, `${label} must answer ${code}, got ${result.body.code}`);
        refusals.push({ label, status, code });
        return result;
      };
      const window = unwrap((await json(`${apiBase}/messages?limit=50`)).body);
      assert.ok(Array.isArray(window.items) && window.items.length > 0, 'messages.list must answer the loaded window');
      assert.ok(window.history && HISTORY_STATES.includes(window.history.state), `messages.list field history.state must be one of ${HISTORY_STATES.join('/')}, got ${JSON.stringify(window.history)}`);
      assert.equal(window.history.state, 'complete', `messages.list must report the history it read whole as complete, got ${window.history.state}`);
      assert.equal(window.history.note, null, 'messages.list field history.note must stay null while the history was read whole');
      assert.equal(window.has_more_before, true, 'the store holds one older row, so the window must not claim to be the start of the history');
      const cursor = window.oldest_cursor;
      assert.ok(cursor, 'messages.list field oldest_cursor must name the cursor the paging protocol asks with');
      const older = await json(`${apiBase}/messages/before?before=${encodeURIComponent(cursor)}&limit=50`);
      assert.equal(older.status, 200, `messages.before answered ${older.status} (${JSON.stringify(older.body)})`);
      const olderPage = unwrap(older.body);
      assert.ok(Array.isArray(olderPage.items) && olderPage.items.length >= 1, `messages.before must serve the older page the store holds, got ${JSON.stringify(olderPage.items)}`);
      assert.equal(olderPage.items.at(-1).conversation_id, conversationId, 'messages.before row field conversation_id must name this conversation');
      assert.ok(olderPage.history && HISTORY_STATES.includes(olderPage.history.state), `messages.before page field history.state must be one of ${HISTORY_STATES.join('/')}, got ${JSON.stringify(olderPage.history)}`);
      assert.equal(olderPage.history.state, 'complete', 'the older page states the same history the window does');
      assert.equal(olderPage.has_more_after, true, 'messages.before page field has_more_after must be true: the caller still holds the newer part');
      assert.equal(olderPage.has_more_before, false, 'nothing is below the oldest row the store holds');
      // The window's own reader, which asks the same cursor with `before=` on the messages route, is served the
      // same older page instead of an empty one.
      const windowPaging = unwrap((await json(`${apiBase}/messages?before=${encodeURIComponent(cursor)}&limit=50`)).body);
      assert.deepEqual(windowPaging.items.map((row) => row.id), olderPage.items.map((row) => row.id), 'the window route must answer the same older page the dedicated route serves');
      assert.equal(windowPaging.has_more_before, false, 'the window route must state the same end of the history');
      await refused('messages.before with a cursor outside the window', `${apiBase}/messages/before?before=never-loaded&limit=50`, {}, 422, 'INVALID_CURSOR');
      await refused('messages.before below the oldest row', `${apiBase}/messages/before?before=${encodeURIComponent(olderPage.oldest_cursor)}&limit=50`, {}, 409, 'HISTORY_EXHAUSTED');
      // A store the host could not read says so on the page, with the reason it knows, and refuses to serve a page
      // from it instead of answering an empty one that would read like the start of the history.
      const restore = { state: backend.history.state, note: backend.history.note };
      try {
        backend.history.state = 'unavailable';
        backend.history.note = '历史库未能读取。';
        const unreadable = unwrap((await json(`${apiBase}/messages?limit=50`)).body);
        assert.equal(unreadable.history.state, 'unavailable', `messages.list must report an unreadable history store as unavailable, got ${unreadable.history.state}`);
        assert.equal(unreadable.history.note, '历史库未能读取。', 'an unavailable history must carry the reason it knows');
        assert.ok(unreadable.items.length > 0, 'an unreadable history store must not empty the window the host already loaded');
        await refused('messages.before from an unreadable store', `${apiBase}/messages/before?before=${encodeURIComponent(cursor)}&limit=50`, {}, 500, 'HISTORY_LOAD_FAILED');
        backend.history.state = 'partial';
        backend.history.note = '更早的历史只读到了一部分。';
        const partial = unwrap((await json(`${apiBase}/messages?limit=50`)).body);
        assert.equal(partial.history.state, 'partial', 'a history that was read in part must say so rather than read as complete');
        assert.equal(partial.history.note, '更早的历史只读到了一部分。', 'a partly read history must carry what stayed unread');
      } finally {
        backend.history.state = restore.state;
        backend.history.note = restore.note;
      }
      const indexBefore = (await probeStatus()).index.sessions;
      const cleared = await json('/api/history/clear', { method: 'POST', body: '{}' });
      assert.equal(cleared.status, 200, `history.clear answered ${cleared.status} (${JSON.stringify(cleared.body)})`);
      assert.deepEqual(unwrap(cleared.body), { cleared: true, conversations: 1 }, `history.clear must report the conversations it cleared, got ${JSON.stringify(unwrap(cleared.body))}`);
      const again = unwrap((await json('/api/history/clear', { method: 'POST', body: '{}' })).body);
      assert.deepEqual(again, { cleared: true, conversations: 0 }, 'a history store that holds nothing must report zero conversations rather than invent one');
      assert.equal((await probeStatus()).index.sessions, indexBefore, 'history.clear must leave the session index cache alone; index.clear is the entry that drops it');
      // A store that could not be cleared is answered as that failure, with the reason it reported.
      backend.history.clearFailure = '历史存储被占用';
      try {
        await refused('history.clear from a store that cannot be cleared', '/api/history/clear', { method: 'POST', body: '{}' }, 500, 'HISTORY_CLEAR_FAILED');
      } finally {
        backend.history.clearFailure = null;
      }
      return { state: window.history.state, cursor, olderRows: olderPage.items.map((row) => row.id), refusals, cleared, again };
    });
    // The saved hosts' own credentials over HTTP: a row states how it authenticates, the key it uses and whether
    // it holds a password — never the password itself, and never for a host a request only updated.
    await check('host-auth-fields', async () => {
      const refusals = [];
      const refused = async (label, relative, init, status, code) => {
        const result = await json(relative, init);
        assert.equal(result.status, status, `${label} answered ${result.status}, expected ${status} ${code} (${JSON.stringify(result.body)})`);
        assert.equal(result.body.code, code, `${label} must answer ${code}, got ${result.body.code}`);
        refusals.push({ label, status, code });
      };
      const rows = unwrap((await json('/api/hosts')).body).items;
      for (const row of rows)
        for (const field of ['auth_method', 'key_id', 'key_name', 'has_password'])
          assert.ok(field in row, `hosts.list row ${row.id} must carry field ${field}`);
      for (const row of rows)
        assert.ok(AUTH_METHODS.includes(row.auth_method), `hosts.list row ${row.id} field auth_method must be one of ${AUTH_METHODS.join('/')}, got ${row.auth_method}`);
      const keyed = rows.find((row) => row.auth_method === 'key');
      assert.ok(keyed && Number.isInteger(keyed.key_id) && keyed.key_name, `a key-authenticated host must name the key it uses, got ${JSON.stringify(keyed)}`);
      assert.ok(!JSON.stringify(rows).includes(SEEDED_HOST_PASSWORD), 'hosts.list must never carry a stored password');
      await refused('hosts.create with an unknown auth method', '/api/hosts', { method: 'POST', body: JSON.stringify({ nickname: '探针主机-未知方式', hostname: '10.0.0.9', auth_method: 'agent' }) }, 422, 'AUTH_METHOD_INVALID');
      await refused('hosts.create on password authentication without a password', '/api/hosts', { method: 'POST', body: JSON.stringify({ nickname: '探针主机-无口令', hostname: '10.0.0.9', auth_method: 'password' }) }, 422, 'PASSWORD_REQUIRED');
      const created = unwrap((await json('/api/hosts', { method: 'POST', body: JSON.stringify({ nickname: '探针主机-口令', hostname: '10.0.0.9', port: 22, username: 'probe', os_type: 'linux', agent_target: 'native', auth_method: 'password', password: '离线探针新口令' }) })).body);
      assert.equal(created.auth_method, 'password', `hosts.create field auth_method must be the method it stored, got ${created.auth_method}`);
      assert.equal(created.key_id, null, 'hosts.create field key_id must stay null for a host that names no key');
      assert.equal(created.has_password, true, 'hosts.create must state that this host now holds a password');
      assert.ok(!JSON.stringify(created).includes('离线探针新口令'), 'hosts.create must never echo the password it stored');
      const afterCreate = unwrap((await json('/api/hosts')).body).items;
      assert.equal(afterCreate.length, rows.length + 1, 'hosts.list must list the host that was created');
      assert.ok(!JSON.stringify(afterCreate).includes('离线探针新口令'), 'hosts.list must never carry a stored password');
      const updated = unwrap((await json(`/api/hosts/${encodeURIComponent(keyed.id)}`, { method: 'PUT', body: JSON.stringify({ nickname: '重命名的离线主机', password: '离线探针改过的口令' }) })).body);
      assert.equal(updated.id, keyed.id, 'hosts.update must answer the same host it wrote');
      assert.equal(updated.key_id, keyed.key_id, 'hosts.update field key_id must stay the association the request did not name');
      assert.equal(updated.auth_method, keyed.auth_method, 'hosts.update field auth_method must stay what the request did not name');
      assert.equal(updated.has_password, true, 'hosts.update must state that this host holds a password');
      assert.ok(!JSON.stringify(updated).includes('离线探针改过的口令'), 'hosts.update must never echo the password it stored');
      assert.ok(!JSON.stringify(unwrap((await json('/api/hosts')).body)).includes('离线探针改过的口令'), 'hosts.list must never carry a stored password after an update');
      // A host switched to password authentication needs one: a stored credential counts, one it holds none of does not.
      await refused('hosts.update to password authentication without a password', '/api/hosts/host-3', { method: 'PUT', body: JSON.stringify({ auth_method: 'password' }) }, 422, 'PASSWORD_REQUIRED');
      return { refusals, created: { id: created.id, auth_method: created.auth_method, has_password: created.has_password }, keyed: { id: keyed.id, key_id: keyed.key_id, key_name: keyed.key_name } };
    });
    // A session whose working directory is gone states it on its own row, and its send is refused as that fact
    // instead of starting a turn the Agent could not run in that directory.
    await check('cwd-missing-send-refused', async () => {
      const sendableBefore = (await runtime()).can_send_message;
      const restore = { missing: backend.cwd.missing, note: backend.cwd.note };
      try {
        backend.cwd.missing = true;
        backend.cwd.note = '该会话的工作目录已被删除。';
        const read = unwrap((await json(apiBase)).body);
        assert.equal(read.cwd_missing, true, 'conversation.read field cwd_missing must report a directory that is gone');
        assert.equal(read.cwd_note, '该会话的工作目录已被删除。', 'conversation.read field cwd_note must carry the reason the host knows');
        assert.equal(read.runtime.can_send_message, false, 'a conversation whose directory is unusable must state that no message can be sent there');
        const listed = unwrap((await json('/api/conversations')).body).items.find((row) => row.id === conversationId);
        assert.equal(listed.cwd_missing, true, 'the conversation list row must carry the same fact as conversation.read');
        assert.equal(listed.cwd_note, '该会话的工作目录已被删除。', 'the conversation list row must carry the same note');
        const refused = await json(`${apiBase}/messages`, { method: 'POST', body: JSON.stringify({ content: '工作目录不存在的会话不能发送。', loading_id: 'cwd-missing-probe' }) });
        assert.equal(refused.status, 409, `messages.send for a session whose directory is gone answered ${refused.status} (${JSON.stringify(refused.body)})`);
        assert.equal(refused.body.code, 'CWD_MISSING', `messages.send must refuse this session with CWD_MISSING, got ${refused.body.code}`);
        assert.equal((await runtime()).turn_id, null, 'a send refused for a missing directory must not start a turn');
      } finally {
        backend.cwd.missing = restore.missing;
        backend.cwd.note = restore.note;
      }
      const restored = unwrap((await json(apiBase)).body);
      assert.equal(restored.cwd_missing, false, 'a directory that is there again must read as present');
      assert.equal(restored.cwd_note, null, 'a directory that is there carries no note');
      assert.equal(restored.runtime.can_send_message, sendableBefore, 'a usable directory must leave the sendability this conversation had before');
      return { note: '该会话的工作目录已被删除。', status: 409, code: 'CWD_MISSING' };
    });
    // Whether the host still holds the session a runtime was attached to: every reader of that runtime states it,
    // and a session that is gone or unreadable is reported as itself rather than as a live conversation.
    await check('conversation-recovery', async () => {
      const recoveryOf = async () => ({
        'conversation.read': unwrap((await json(apiBase)).body).recovery,
        'runtime.snapshot event': (report.frames.filter((frame) => frame.name === 'runtime.snapshot').at(-1)?.data ?? {}).recovery,
      });
      const attached = await recoveryOf();
      for (const [reader, stated] of Object.entries(attached)) {
        assert.ok(stated && RECOVERY_STATES.includes(stated.state), `${reader} field recovery.state must be one of ${RECOVERY_STATES.join('/')}, got ${JSON.stringify(stated)}`);
        assert.equal(stated.state, 'attached', `${reader} must report the session it holds as attached`);
        assert.equal(stated.note, null, `${reader} must carry no note while the runtime is attached`);
      }
      const observed = [];
      const restore = { state: backend.recovery.state, note: backend.recovery.note };
      try {
        for (const state of ['gone', 'unknown']) {
          backend.recovery.state = state;
          backend.recovery.note = `恢复状态：${state}`;
          const reports = await recoveryOf();
          assert.equal(reports['conversation.read'].state, state, `conversation.read must report recovery ${state}, got ${reports['conversation.read'].state}`);
          assert.equal(reports['conversation.read'].note, `恢复状态：${state}`, `conversation.read must carry the reason it knows with recovery ${state}`);
          observed.push({ state, read: reports['conversation.read'] });
        }
      } finally {
        backend.recovery.state = restore.state;
        backend.recovery.note = restore.note;
      }
      const back = unwrap((await json(apiBase)).body).recovery;
      assert.equal(back.state, 'attached', 'a runtime that is attached again must read as attached');
      return { attached, observed, readers: report.frames.filter((frame) => frame.name === 'runtime.snapshot').length };
    });
    await check('ws-reconnect-history-recovery', async () => {
      const turn = await sendUI('慢速 slow 断流后刷新恢复中文。');
      await poll(async () => eventsFor(turn.turn_id, 'message.stream').some((frame) => frame.data.type === 'text') && eventsFor(turn.turn_id, 'message.stream').some((frame) => frame.data.type === 'acp_tool_call'), 'Real stream and running tool before disconnect');
      const count = report.frames.length;
      const disconnected = await json('/api/probe/disconnect-stream', { method: 'POST', body: '{}' });
      assert.equal(disconnected.status, 200, 'Backend must disconnect real business sockets');
      await poll(async () => report.frames.slice(count).some((frame) => frame.name === 'runtime.snapshot' && frame.data?.runtime?.turn_id === turn.turn_id), 'Business WS must reconnect and receive an authoritative runtime snapshot');
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('sendbox-input')).toBeVisible();
      await expect(page.getByText('正在执行可取消的离线慢速任务。', { exact: false }).filter({ visible: true }).last()).toBeVisible();
      await page.locator('.sendbox-stop-button').click();
      await waitIdle();
      await screenshot('ws-recovered');
      return { turn, receivedAfterDisconnect: report.frames.length - count };
    });
    await check('network-and-page-errors', async () => {
      assert.deepEqual(report.blocked, [], 'Renderer attempted a forbidden exact-origin HTTP/WS request');
      assert.deepEqual(report.pageErrors, [], 'Renderer JavaScript errors');
      assert.ok(report.requests.every((request) => !request.url.includes('/api/conversations/conv-fixture-')), 'Old fixture conversation leaked');
      return { requests: report.requests.length, frames: report.frames.length };
    });
  } catch (error) {
    report.error = error.message;
  } finally {
    for (const id of requiredChecks) if (!report.checks.some((check) => check.id === id)) report.checks.push({ id, ok: false, notRun: true, error: 'Blocked by an earlier failure; not counted as passed' });
    report.ok = report.checks.length === requiredChecks.length && report.checks.every((check) => check.ok);
    report.summary = { passed: report.checks.filter((check) => check.ok).length, failed: report.checks.filter((check) => !check.ok).map((check) => check.id) };
    try { await context?.close(); await browser?.close(); } catch (error) { report.cleanupError = error.message; report.ok = false; }
    await fs.writeFile(path.join(output, 'browser-report.json'), JSON.stringify(report, null, 2) + '\n');
  }
  return report;
}
