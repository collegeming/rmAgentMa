// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';

const conversationId = 'conv-probe-1';
const apiBase = `/api/conversations/${conversationId}`;
const requiredChecks = [
  'fresh-renderer', 'model-catalog-and-ui-switch', 'unknown-model-rejected',
  'chinese-stream-and-terminal', 'ui-allow-with-effect', 'duplicate-approval-rejected',
  'history-readonly-after-refresh', 'ui-reject-without-effect', 'pending-refresh-and-ui-reject',
  'pending-ui-cancel-without-effect', 'stale-approval-rejected', 'stream-ui-cancel',
  'old-turn-cancel-cannot-stop-new-turn', 'ws-reconnect-history-recovery', 'network-and-page-errors',
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
  const report = { ok: false, url, buildId: build.buildId, checks: [], screenshots: [], requests: [], frames: [], blocked: [], pageErrors: [], console: [] };
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
    const filename = path.join(output, 'backend/effects', turnId, 'result.txt');
    try { return await fs.readFile(filename, 'utf8'); } catch (error) { if (error.code === 'ENOENT') return null; throw error; }
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
      textTurn = await sendUI('你好，验证中文分块输出。');
      await waitIdle();
      const chunks = eventsFor(textTurn.turn_id, 'message.stream').filter((frame) => frame.data.type === 'text');
      assert.ok(chunks.length >= 2, 'Expected multiple real dsh text chunks');
      await poll(async () => eventsFor(textTurn.turn_id, 'turn.completed').some((frame) => frame.data.outcome === 'completed' && frame.data.stop_reason === 'end_turn'), 'Expected real end_turn rather than merely an idle error runtime');
      await expect(page.getByText(/离线真实 dsh text 完成。/).filter({ visible: true }).last()).toBeVisible();
      await screenshot('chinese-terminal');
      return { turn: textTurn, chunks: chunks.length };
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
      await expect(page.getByText(`Tool Call ID: ${allowedApproval.tool_call_id}`, { exact: true })).toBeVisible();
      await expect(page.getByText('danger-full-access', { exact: false }).filter({ visible: true }).last()).toBeVisible();
      await screenshot('allow-pending');
      allowedAnswer = await answerUI(allowedApproval, 'allow-once');
      await waitIdle();
      assert.equal(await effect(allowed.turn_id), `effect:${allowed.turn_id}\n`);
      const tools = eventsFor(allowed.turn_id, 'message.stream').filter((frame) => frame.data.type === 'acp_tool_call');
      assert.ok(tools.some((frame) => frame.data.data?.update?.status === 'in_progress'), 'Missing true running tool frame');
      assert.ok(tools.some((frame) => frame.data.data?.update?.status === 'completed'), 'Missing true completed tool frame');
      await screenshot('allow-terminal');
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
      await answerUI(approval, 'reject-once');
      await waitIdle();
      assert.equal(await effect(turn.turn_id), null);
      const failed = eventsFor(turn.turn_id, 'message.stream').some((frame) => frame.data.type === 'acp_tool_call' && frame.data.data?.update?.status === 'failed');
      assert.ok(failed, 'Rejected real dsh tool must remain failed');
      await screenshot('reject-terminal');
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
      cancelledTurn = await sendUI('cancel 取消流式，验证中文模型分块的真正中断。');
      await poll(async () => eventsFor(cancelledTurn.turn_id, 'message.stream').filter((frame) => frame.data.type === 'text').length >= 2 && (await runtime()).turn_id === cancelledTurn.turn_id, 'Wait for at least two real model chunks before stopping');
      await page.locator('.sendbox-stop-button').click();
      await waitIdle();
      assert.ok(eventsFor(cancelledTurn.turn_id, 'turn.completed').length > 0);
      await screenshot('stream-cancelled');
      return cancelledTurn;
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
    await check('ws-reconnect-history-recovery', async () => {
      const turn = await sendUI('慢速 slow 断流后刷新恢复中文。');
      await poll(async () => eventsFor(turn.turn_id, 'message.stream').some((frame) => frame.data.type === 'text') && eventsFor(turn.turn_id, 'message.stream').some((frame) => frame.data.type === 'acp_tool_call'), 'Real stream and running tool before disconnect');
      const count = report.frames.length;
      for (const socket of sockets) socket.close({ code: 1012, reason: 'probe reconnect' });
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
