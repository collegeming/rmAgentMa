// SPDX-License-Identifier: Apache-2.0
import { mkdirSync, readdirSync, writeFileSync, existsSync, readFileSync, realpathSync } from 'node:fs';
import { resolve, join, dirname } from 'node:path';
import { AcpClient, toConfigOptions } from './acp-client.mjs';
import { startFakeModel, fakeOverlay, LOCAL_VALUES } from './fake-model.mjs';
import { HistoryStore, HostStore, MessageQueue, SessionIndex, cwdFields, seededOlderHistory } from './host-fixture.mjs';
import { SessionState, CONVERSATION_ID as C, PROJECT_ID as P, id, problem } from './session-state.mjs';

const ok = (data) => ({ success: true, data });
const json = (body, status = 200) => Response.json(body, { status, headers: { 'Cache-Control': 'no-store' } });
const emptyRoutes = new Set(['/api/assistants', '/api/agents/management', '/api/providers', '/api/mcp/servers', '/api/skills', '/api/cron/jobs', '/api/teams', '/api/extensions/acp-adapters']);

export async function startBackend({ runDir, dshBin = '/usr/sbin/dsh', port = 0 } = {}) {
  if (!runDir) throw new Error('runDir must be a new explicit isolated directory');
  if (!dshBin.includes('/')) dshBin = Bun.which(dshBin);
  if (!dshBin) throw new Error('Explicit dsh executable not found');
  dshBin = resolve(dshBin);
  runDir = resolve(runDir);
  mkdirSync(runDir, { recursive: true, mode: 0o700 });
  if (realpathSync(runDir) !== runDir) throw new Error('runDir must not contain symlinks');
  const marker = join(runDir, '.rmagent-probe');
  if (!existsSync(marker) && readdirSync(runDir).length) throw new Error('Refusing a non-empty unowned runDir');
  writeFileSync(marker, 'offline-probe-v1\n', { mode: 0o600 });
  for (const directory of ['home', 'dsh', 'config', 'data', 'cache', 'state', 'tmp', 'work', 'effects']) {
    const path = join(runDir, directory);
    mkdirSync(path, { recursive: true, mode: 0o700 });
    if (realpathSync(path) !== path) throw new Error('Isolation directories must not be symlinks');
  }
  const env = {
    PATH: [dirname(resolve(dshBin)), '/usr/bin', '/bin'].join(':'), HOME: join(runDir, 'home'),
    XDG_CONFIG_HOME: join(runDir, 'config'), XDG_DATA_HOME: join(runDir, 'data'), XDG_CACHE_HOME: join(runDir, 'cache'),
    XDG_STATE_HOME: join(runDir, 'state'), TMPDIR: join(runDir, 'tmp'), DSH_HOME: join(runDir, 'dsh'),
    LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', NO_COLOR: '1', FAKE_KEY: 'offline-probe-not-a-credential',
  };
  const probeHeaders = Object.freeze({ 'X-RmAgent-Probe-Token': id() });
  let allowedOrigins = new Set();
  function setAllowedOrigins(origins) {
    if (!Array.isArray(origins)) throw new TypeError('Expected an array of exact HTTP WebHost origins');
    const next = new Set(origins.map((origin) => {
      const parsed = new URL(origin);
      if (parsed.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(parsed.hostname) || parsed.origin !== origin) {
        throw new TypeError('Expected an exact loopback HTTP origin without path, credentials or wildcard');
      }
      return origin;
    }));
    allowedOrigins = next;
    for (const socket of sockets) if (socket.data.origin && !next.has(socket.data.origin)) socket.close(1008, 'WebHost origin revoked');
  }
  const state = new SessionState(runDir, { deferPersist: true });
  const fake = startFakeModel({ runDir });
  state.recoveryIssues.push(...fake.recoveryIssues);
  if (state.recoveryIssues.length) {
    state.readOnly = true;
    state.status = 'interrupted';
    state.error = 'Incomplete journal or inconsistent snapshot; history is read-only and no prompt will be replayed.';
  }
  state.persist();
  const patch = join(runDir, 'fake-overlay.yml');
  writeFileSync(patch, fakeOverlay(fake.url), { mode: 0o600 });
  const work = join(runDir, 'work');
  let client;
  let ensuring;
  let closed = false;
  let configBusy = false;
  const sockets = new Set();
  const tools = new Map();
  const cancelled = new Map();
  const queue = new MessageQueue();
  const index = new SessionIndex([{ session_id: C }]);
  const hosts = new HostStore();
  const history = new HistoryStore({ older: seededOlderHistory(C) });
  // The two facts this session states about itself: whether its working directory is still there, and whether the
  // host still holds the session its runtime was attached to. Both are what a check flips to prove a branch.
  const cwd = { missing: false, note: null };
  const recovery = { state: 'attached', note: null };
  const settings = { language: 'zh-CN', theme: 'light', colorScheme: 'default', notificationEnabled: false, cronNotificationEnabled: false, keepAwake: false, saveUploadToWorkspace: false };

  function publicApproval(approval) { const { rpc_id, ...result } = approval; return result; }
  function expireApprovals(reason, reply = false) {
    for (const approval of state.pending()) {
      approval.state = 'expired';
      approval.reason = reason;
      if (reply && client && !client.closed) client.reply(approval.rpc_id, { outcome: { outcome: 'cancelled' } });
      const message = state.messages.find((item) => item.content?.request_id === approval.request_id);
      if (message) { message.content.readonly = true; message.status = 'finish'; }
      state.emit('confirmation.remove', { conversation_id: C, id: approval.request_id, request_id: approval.request_id, call_id: approval.request_id, epoch: state.epoch });
    }
  }

  function onUpdate(frame) {
    if (frame.params?.sessionId !== state.nativeSessionId) return;
    const update = frame.params.update ?? {};
    if (update.sessionUpdate === 'config_option_update') {
      state.options = toConfigOptions(update.configOptions ?? []);
      return;
    }
    if (!state.turn) return;
    const kind = update.sessionUpdate;
    if (kind === 'agent_message_chunk' || kind === 'agent_thought_chunk') {
      if (update.content?.type !== 'text') return;
      const type = kind === 'agent_message_chunk' ? 'text' : 'thinking';
      const msgId = `${state.turn.id}-${type}-${update.messageId ?? 'main'}`;
      let message = state.messages.find((item) => item.id === msgId);
      if (!message) message = state.addMessage(type, { content: '' }, msgId);
      message.content.content += update.content.text;
      state.stream(type, { content: update.content.text, ...(type === 'thinking' ? { status: 'pending' } : {}) }, msgId);
    } else if (kind === 'tool_call' || kind === 'tool_call_update') {
      const old = tools.get(update.toolCallId) ?? {};
      const tool = { ...old, ...update };
      tools.set(update.toolCallId, tool);
      const wire = { sessionUpdate: kind, tool_call_id: update.toolCallId, ...Object.fromEntries(Object.entries(tool).filter(([key]) => key !== 'toolCallId' && key !== 'sessionUpdate')) };
      const content = { session_id: C, update: wire };
      const msgId = `${state.turn.id}-tool-${update.toolCallId}`;
      let message = state.messages.find((item) => item.id === msgId);
      if (!message) message = state.addMessage('acp_tool_call', content, msgId);
      else message.content = content;
      state.stream('acp_tool_call', content, msgId);
    } else if (kind === 'usage_update') {
      state.usage = { used: update.used, size: update.size, ...(update.cost !== undefined ? { cost: update.cost } : {}) };
      state.stream('acp_context_usage', state.usage);
    } else if (kind === 'plan') {
      const content = { session_id: C, entries: update.entries, turn_id: state.turn.id };
      const msgId = `${state.turn.id}-plan`;
      const old = state.messages.find((item) => item.id === msgId);
      if (old) old.content = content; else state.addMessage('plan', content, msgId);
      state.stream('plan', content, msgId);
    } else state.emit('probe.unmapped', { conversation_id: C, update });
  }

  function onRequest(frame) {
    if (frame.method !== 'session/request_permission') return client.reject(frame.id, 'Unsupported client capability');
    if (!state.turn || frame.params?.sessionId !== state.nativeSessionId || state.status === 'cancelling') {
      client.reply(frame.id, { outcome: { outcome: 'cancelled' } });
      return;
    }
    const requestId = id();
    const toolId = frame.params.toolCall?.toolCallId;
    const tool = tools.get(toolId) ?? {};
    const content = {
      session_id: C, request_id: requestId, epoch: state.epoch,
      options: frame.params.options.map((option) => ({ option_id: option.optionId, name: option.name, kind: option.kind })),
      tool_call: { tool_call_id: toolId, title: tool.title ?? 'Tool details unavailable', kind: tool.kind ?? 'other', raw_input: tool.rawInput, content: tool.content },
    };
    const approval = { ...content, id: requestId, call_id: requestId, title: tool.title ?? 'dsh permission', description: tool.rawInput?.justification ?? 'Explicit permission required',
      conversation_id: C, turn_id: state.turn.id, tool_call_id: toolId, rpc_id: frame.id, state: 'waiting', msg_id: requestId };
    state.approvals.set(requestId, approval);
    state.status = 'waiting_confirmation';
    state.addMessage('acp_permission', content, requestId, { status: 'pending' });
    state.stream('acp_permission', content, requestId);
    state.emit('runtime.updated', { conversation_id: C, runtime: state.runtime() });
  }

  async function ensure() {
    if (state.readOnly) throw problem(409, 'RECOVERY_READ_ONLY', state.error);
    if (state.ready) return { recovered: false, config_options: state.options, runtime: state.runtime() };
    if (ensuring) return ensuring;
    if (client?.closed) throw problem(503, 'RUNTIME_DISCONNECTED', state.error ?? 'Runtime disconnected');
    ensuring = (async () => {
      client = new AcpClient({ dshBin, cwd: work, env, patch, framesPath: join(runDir, 'acp-frames.jsonl') });
      client.on('notification', onUpdate);
      client.on('request', onRequest);
      client.on('disconnect', (error) => {
        state.ready = false;
        if (!closed) { state.error = error.message; state.status = 'interrupted'; expireApprovals('connection_lost'); state.emit('runtime.updated', { conversation_id: C, runtime: state.runtime(), error: state.error }); }
      });
      await client.request('initialize', { protocolVersion: 1, clientCapabilities: { fs: { readTextFile: false, writeTextFile: false } } });
      const recovered = Boolean(state.nativeSessionId);
      const created = await client.request(recovered ? 'session/resume' : 'session/new', { ...(recovered ? { sessionId: state.nativeSessionId } : {}), cwd: work, mcpServers: [] });
      state.nativeSessionId = created.sessionId ?? state.nativeSessionId;
      state.options = toConfigOptions(created.configOptions ?? []);
      if (!state.options.some((option) => option.id === 'model' && LOCAL_VALUES.includes(option.current_value))) throw problem(503, 'NONLOCAL_MODEL', 'Runtime did not select the isolated fake model');
      state.ready = true;
      if (state.status !== 'interrupted') state.status = 'idle';
      state.persist();
      return { recovered, config_options: state.options, runtime: state.runtime() };
    })().catch(async (error) => {
      state.error = error.message;
      state.status = 'interrupted';
      await client?.close();
      state.persist();
      throw error;
    }).finally(() => { ensuring = null; });
    return ensuring;
  }

  function conversation() {
    const current = state.options.find((option) => option.id === 'model')?.current_value ?? LOCAL_VALUES[0];
    return { id: C, name: 'dsh 离线完整回路', type: 'acp', created_at: state.messages[0].created_at, modified_at: Date.now(),
      model: { provider_id: 'fake', use_model: current, model: current }, extra: { workspace: work, backend: 'dsh', custom_workspace: true, agent_name: 'dsh' },
      project_id: P, status: state.turn ? 'running' : 'finished', fork_capability: { at_turn: false }, prompt_capability: { image: false, audio: false }, runtime: runtime(),
      ...cwdFields(cwd.missing, cwd.note) };
  }

  /**
   * The runtime of this session with the one fact a missing working directory adds: a conversation that has no
   * directory to run in cannot take a message, which its own runtime states instead of only a refused send.
   */
  function runtime() {
    const current = state.runtime();
    return cwd.missing ? { ...current, can_send_message: false } : current;
  }

  /** The same row as `conversation.read` answers it, which also states whether the runtime is still attached. */
  function conversationRead() {
    return { ...conversation(), recovery: { ...recovery } };
  }

  /** The runtime snapshot with the session's own recovery state, the fact this batch adds to every reader of it. */
  function snapshot() {
    return { ...state.snapshot(), runtime: runtime(), recovery: { ...recovery } };
  }

  function complete(turnId, result, error) {
    if (state.turn?.id !== turnId) return;
    if (error?.code === 'RPC_TIMEOUT' || client.closed) {
      state.status = 'interrupted';
      state.error = error?.message ?? 'ACP disconnected; result unknown';
      expireApprovals('result_unknown');
      state.stream('error', { message: state.error, code: error?.code ?? 'DISCONNECTED' });
      state.emit('runtime.updated', { conversation_id: C, runtime: state.runtime(), error: state.error });
      return;
    }
    const status = error ? 'error' : result?.stopReason === 'cancelled' ? 'cancelled' : result?.stopReason === 'end_turn' ? 'completed' : 'stopped';
    expireApprovals('turn_ended');
    for (const message of state.messages.filter((item) => item.turn_id === turnId)) message.status = 'finish';
    if (error) { state.addMessage('tips', { type: 'error', content: error.message }); state.stream('error', { message: error.message }); }
    state.stream('finish', { stop_reason: result?.stopReason, status });
    state.lastTurn = { turn_id: turnId, status, stop_reason: result?.stopReason, error: error?.message };
    state.turn = null;
    state.status = 'idle';
    state.emit('turn.completed', { session_id: C, conversation_id: C, turn_id: turnId, status: 'finished', outcome: status,
      stop_reason: result?.stopReason, state: error ? 'error' : status === 'cancelled' ? 'stopped' : 'ai_waiting_input',
      can_send_message: true, runtime: state.runtime(), last_message: state.messages.at(-1), model: conversation().model, workspace: work });
  }

  async function send(body, key) {
    if (typeof body.content !== 'string' || !body.content.trim()) throw problem(422, 'INVALID_CONTENT', 'content must be non-empty text');
    // A session whose working directory is gone cannot take a prompt: the refusal names that fact and starts no turn.
    if (cwd.missing) throw problem(409, 'CWD_MISSING', '该会话的工作目录已不在主机上，无法发送。');
    if ([body.files, body.sessions, body.inject_skills].some((value) => value && (!Array.isArray(value) || value.length))) throw problem(422, 'UNSUPPORTED_ATTACHMENT', 'Files, sessions and injected skills are not enabled');
    if (body.content.length > 64000) throw problem(413, 'CONTENT_TOO_LARGE', 'Prompt exceeds probe limit');
    key ??= body.loading_id;
    if (key !== undefined && (typeof key !== 'string' || !key || key.length > 200)) throw problem(422, 'INVALID_KEY', 'Invalid idempotency key');
    const existingReceipt = () => {
      if (!key || !state.receipts.has(key)) return null;
      const previous = state.receipts.get(key);
      if (previous.content !== body.content) throw problem(409, 'IDEMPOTENCY_CONFLICT', 'Key already used for different content');
      return previous.result;
    };
    const existing = existingReceipt();
    if (existing) return existing;
    await ensure();
    const acceptedDuringEnsure = existingReceipt();
    if (acceptedDuringEnsure) return acceptedDuringEnsure;
    if (state.status !== 'idle' || configBusy) throw problem(409, 'RUNTIME_BUSY', 'A turn or configuration update is already active');
    const model = state.options.find((option) => option.id === 'model');
    if (!LOCAL_VALUES.includes(model?.current_value)) throw problem(403, 'NONLOCAL_MODEL', 'Only fake local models may receive prompts');
    const turnId = id();
    const msgId = body.msg_id ?? body.loading_id ?? id();
    if (typeof msgId !== 'string' || !msgId || msgId.length > 200) throw problem(422, 'INVALID_MESSAGE_ID', 'Invalid message id');
    if (state.messages.some((message) => message.id === msgId)) throw problem(409, 'MESSAGE_ID_CONFLICT', 'Message id already exists');
    state.turn = { id: turnId, msg_id: msgId };
    state.status = 'running';
    fake.register(turnId, body.content);
    state.addMessage('text', { content: body.content }, msgId, { position: 'right' });
    const result = { msg_id: msgId, turn_id: turnId, runtime: state.runtime() };
    if (key) state.receipts.set(key, { content: body.content, result });
    state.persist();
    state.emit('message.userCreated', { conversation_id: C, msg_id: msgId, client_msg_id: body.loading_id, turn_id: turnId,
      content: body.content, position: 'right', status: 'finish', hidden: false, created_at: state.messages.at(-1).created_at });
    state.stream('start', {}, `${turnId}-start`);
    client.request('session/prompt', { sessionId: state.nativeSessionId, prompt: [{ type: 'text', text: `${body.content}\n[probe-turn:${turnId}]` }] }, 120000)
      .then((response) => complete(turnId, response), (error) => complete(turnId, null, error));
    return result;
  }

  function review(reference, body) {
    const approval = state.approvals.get(reference);
    if (!approval) throw problem(404, 'APPROVAL_NOT_FOUND', 'No live approval has this request_id');
    if (body.epoch !== approval.epoch || approval.epoch !== state.epoch) throw problem(409, 'STALE_EPOCH', 'Approval belongs to a different runtime epoch');
    const option = body.data;
    if (!approval.options.some((item) => item.option_id === option)) throw problem(422, 'INVALID_OPTION', 'Unknown original RPC option id');
    if (approval.state === 'answered') {
      if (approval.answer !== option) throw problem(409, 'ALREADY_ANSWERED', 'Approval was answered differently');
      return approval.result;
    }
    if (approval.state !== 'waiting' || approval.turn_id !== state.turn?.id || client?.closed) throw problem(410, 'APPROVAL_EXPIRED', 'Approval is no longer live');
    approval.state = 'responding';
    approval.answer = option;
    state.persist();
    try { client.reply(approval.rpc_id, { outcome: { outcome: 'selected', optionId: option } }); }
    catch (error) { approval.state = 'expired'; state.persist(); throw problem(503, 'APPROVAL_UNCONFIRMED', error.message); }
    approval.state = 'answered';
    approval.result = { request_id: reference, epoch: state.epoch, state: 'answered', option_id: option };
    const message = state.messages.find((item) => item.content?.request_id === reference);
    if (message) { message.content.readonly = true; message.content.selected = option; message.status = 'finish'; }
    state.status = 'running';
    state.emit('confirmation.remove', { conversation_id: C, id: reference, request_id: reference, call_id: reference, epoch: state.epoch });
    return approval.result;
  }

  function cancel(body) {
    if (!body.turn_id) throw problem(422, 'MISSING_TURN', 'turn_id is required');
    if (state.turn?.id !== body.turn_id) {
      if (!state.turn && cancelled.has(body.turn_id)) return cancelled.get(body.turn_id);
      throw problem(409, 'STALE_TURN', 'Only the current turn may be cancelled');
    }
    if (cancelled.has(body.turn_id)) return { runtime: state.runtime(), turn_id: body.turn_id };
    if (!client || client.closed) throw problem(503, 'CANCEL_UNCONFIRMED', 'ACP is disconnected; stop is unconfirmed');
    state.status = 'cancelling';
    client.notify('session/cancel', { sessionId: state.nativeSessionId });
    expireApprovals('cancelled', true);
    const response = { runtime: state.runtime(), turn_id: body.turn_id };
    cancelled.set(body.turn_id, response);
    state.emit('runtime.updated', { conversation_id: C, runtime: state.runtime() });
    return response;
  }

  const emitToSockets = (event) => {
    for (const socket of sockets) {
      if (socket.data.conversationId !== C || !socket.data.subscribed) continue;
      socket.send(JSON.stringify({ ...event, documentId: socket.data.documentId }));
    }
  };
  state.listeners.add(emitToSockets);
  function replay(socket, cursor = 0, epoch) {
    const current = snapshot();
    if (epoch === state.epoch && Number.isSafeInteger(cursor) && cursor >= 0 && cursor <= state.seq) {
      for (const event of state.events) if (event.epoch === epoch && event.seq > cursor) socket.send(JSON.stringify({ ...event, documentId: socket.data.documentId }));
    }
    socket.send(JSON.stringify({ type: 'event', documentId: socket.data.documentId, name: 'runtime.snapshot', data: { ...current, replace: true }, epoch: state.epoch, seq: state.seq }));
  }
  const server = Bun.serve({
    hostname: '127.0.0.1', port,
    async fetch(request, server) {
      try {
        const url = new URL(request.url);
        const host = new URL(`http://${request.headers.get('host') ?? url.host}`);
        if (!['127.0.0.1', 'localhost', '[::1]'].includes(host.hostname)) throw problem(403, 'HOST_DENIED', 'Non-loopback Host header denied');
        const origin = request.headers.get('origin');
        if (origin !== null) {
          if (!allowedOrigins.has(origin)) throw problem(403, 'ORIGIN_DENIED', 'WebHost origin is not registered for this backend');
        } else if (request.headers.get('X-RmAgent-Probe-Token') !== probeHeaders['X-RmAgent-Probe-Token']) {
          throw problem(403, 'PROBE_AUTH_REQUIRED', 'Originless runner requests require the private probe header');
        }
        if (request.headers.get('sec-fetch-site') === 'cross-site') throw problem(403, 'CROSS_SITE_DENIED', 'Cross-site requests are not allowed');
        const path = url.pathname;
        const method = request.method;
        if (path === '/ws') {
          const conversationId = url.searchParams.get('conversation_id') ?? C;
          if (conversationId !== C) throw problem(404, 'CONVERSATION_NOT_FOUND', 'Unknown subscription');
          if (server.upgrade(request, { data: { origin, conversationId, subscribed: true, documentId: url.searchParams.get('documentId') ?? '', cursor: Number(url.searchParams.get('since') ?? 0), epoch: url.searchParams.get('epoch') } })) return;
          throw problem(400, 'UPGRADE_FAILED', 'Expected WebSocket upgrade');
        }
        let body = {};
        if (!['GET', 'HEAD'].includes(method)) {
          if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers.get('content-type') ?? '')) {
            throw problem(415, 'JSON_REQUIRED', 'Mutation requests require application/json');
          }
          const text = await request.text();
          if (text.length > 128000) throw problem(413, 'BODY_TOO_LARGE', 'Request exceeds probe limit');
          try { body = text ? JSON.parse(text) : {}; } catch { throw problem(400, 'INVALID_JSON', 'Invalid JSON body'); }
          if (!body || typeof body !== 'object' || Array.isArray(body)) throw problem(422, 'INVALID_BODY', 'Expected object body');
          if (state.readOnly) throw problem(409, 'RECOVERY_READ_ONLY', state.error);
        }
        if (method === 'GET' && path === '/api/probe/status') return json(ok({ ...state.snapshot(), ready: state.ready, native_session_id: state.nativeSessionId,
          last_turn: state.lastTurn, models: LOCAL_VALUES, fake_requests: fake.requests, fake_streams: [...fake.streams.values()],
          text_delivery: { acp: 'committed_assistant_message', token_level: false, cancellation: 'partial_commit_before_cancelled_result',
            note: '本探针下 dsh 的文本为提交级，非 token 级；取消时可能提交已接收的部分文本；backend 不拆分最终全文。' },
          isolation: { cwd: work, home: env.HOME, dsh_home: env.DSH_HOME, environment_names: Object.keys(env) },
          queue: { rows: queue.all(), stats: queue.stats() },
          index: { sessions: index.count() },
          side_effects: [...fake.turns.values()].map((turn) => ({ ...turn, exists: existsSync(turn.target), content: existsSync(turn.target) ? readFileSync(turn.target, 'utf8') : null })) }));
        if (method === 'GET' && path === '/api/probe/events') return json(ok(state.events.filter((event) => event.seq > Number(url.searchParams.get('since') ?? 0))));
        if (method === 'POST' && ['/api/probe/disconnect', '/api/probe/disconnect-stream'].includes(path)) {
          for (const socket of sockets) socket.close(1012, 'probe stream interruption');
          return json(ok({ disconnected: true, epoch: state.epoch, seq: state.seq }));
        }
        if (method === 'GET' && path === '/api/auth/user') return json({ success: true, user: { id: 'probe-user-1', username: 'offline-probe' } });
        if (method === 'GET' && path === '/api/settings/client') return json(ok(settings));
        if (method === 'PUT' && path === '/api/settings/client') {
          const updates = body.settings ?? body;
          for (const key of Object.keys(updates)) if (!(key in settings)) throw problem(422, 'SETTING_DENIED', `Unsupported setting: ${key}`);
          Object.assign(settings, updates);
          return json(ok(settings));
        }
        if (method === 'GET' && path === '/api/settings') return json(ok({ language: settings.language, cross_session_message_enabled: false }));
        if (method === 'GET' && path === '/api/conversations') return json(ok({ items: [conversation()], total: 1, has_more: false }));
        if (method === 'GET' && path === '/api/projects') return json(ok([{ project_id: P, name: '离线探针项目' }]));
        if (method === 'GET' && path === `/api/projects/${P}`) return json(ok({ project_id: P, name: '离线探针项目', explorer: { workspace_pe_id: null, entries: [] } }));
        // The queue, the index cache and the saved hosts: state the renderer's cross-session surfaces read,
        // answered with the contract's own field names.
        if (method === 'GET' && path === '/api/queue') {
          const items = queue.pending(url.searchParams.get('session_id') ?? undefined);
          return json(ok({ items, total: items.length, stats: queue.stats() }));
        }
        if (method === 'POST' && path === '/api/queue') {
          const target = body.conversation_id ?? body.session_id;
          if (target !== C) throw problem(404, 'CONVERSATION_NOT_FOUND', 'This queue holds messages for the conversations the product already holds');
          const queued = queue.enqueue({ conversationId: C, hostId: body.host_id, agent: body.agent, sessionId: body.session_id ?? C, kind: body.kind, text: body.text });
          return json(ok({ queue_id: queued.queue_id, conversation_id: queued.conversation_id, host_id: queued.host_id, agent: queued.agent,
            session_id: queued.session_id, kind: queued.kind, state: queued.state, queued: true, delivered: false }));
        }
        const queueEntry = path.match(/^\/api\/queue\/([^/]+)$/);
        if (method === 'DELETE' && queueEntry) return json(ok(queue.cancel(decodeURIComponent(queueEntry[1]))));
        if (method === 'POST' && path === '/api/index/clear') return json(ok(index.clear()));
        // The history store is a store of its own: its clear entry reports the conversations it dropped, and the
        // session index cache keeps its own counts.
        if (method === 'POST' && path === '/api/history/clear') return json(ok(history.clear()));
        if (method === 'GET' && path === '/api/hosts') return json(ok(hosts.list()));
        if (method === 'POST' && path === '/api/hosts') return json(ok(hosts.create(body)));
        const hostEntry = path.match(/^\/api\/hosts\/([^/]+)$/);
        if (method === 'PUT' && hostEntry) return json(ok(hosts.update(decodeURIComponent(hostEntry[1]), body)));
        if (method === 'GET' && emptyRoutes.has(path)) return json(ok([]));
        const match = path.match(/^\/api\/conversations\/([^/]+)(.*)$/);
        if (match) {
          if (match[1] !== C) throw problem(404, 'CONVERSATION_NOT_FOUND', 'Unknown conversation');
          const route = match[2];
          if (method === 'GET' && route === '') return json(ok(conversationRead()));
          if (method === 'POST' && route === '/runtime/ensure') return json(ok(await ensure()));
          if (method === 'GET' && route === '/snapshot') return json(ok(snapshot()));
          if (method === 'GET' && route === '/config-options') return json(ok({ config_options: state.options }));
          if (method === 'PUT' && route.startsWith('/config-options/')) {
            await ensure();
            if (state.status !== 'idle' || configBusy) throw problem(409, 'RUNTIME_BUSY', 'Cannot change configuration during a turn');
            const optionId = decodeURIComponent(route.slice('/config-options/'.length));
            const option = state.options.find((item) => item.id === optionId);
            if (!option?.options.some((item) => item.value === body.value)) throw problem(422, 'INVALID_CONFIG', 'Unknown option or value');
            if (optionId === 'model' && !LOCAL_VALUES.includes(body.value)) throw problem(403, 'NONLOCAL_MODEL', 'Only fake local models can be selected');
            configBusy = true;
            try {
              const result = await client.request('session/set_config_option', { sessionId: state.nativeSessionId, configId: optionId, value: body.value });
              state.options = toConfigOptions(result.configOptions ?? []);
              state.persist();
              return json(ok({ confirmation: state.options.find((item) => item.id === optionId)?.current_value === body.value ? 'observed' : 'command_ack', config_options: state.options }));
            } catch (error) {
              if (error.code === 'RPC_TIMEOUT') {
                state.status = 'interrupted';
                state.error = 'Configuration result unconfirmed; no prompt will be sent until runtime recovery.';
                state.emit('runtime.updated', { conversation_id: C, runtime: state.runtime(), error: state.error });
              }
              throw error;
            } finally { configBusy = false; }
          }
          if (method === 'GET' && route === '/messages/latest') return json(ok([...state.messages].reverse().find((message) => message.type === url.searchParams.get('type')) ?? null));
          if (method === 'GET' && route === '/messages') {
            const limit = Math.min(200, Math.max(1, Number(url.searchParams.get('limit') ?? 50)));
            const before = url.searchParams.get('before');
            // A request that names the cursor of the page it holds is asking for the page below it, which is the
            // history store's own older page — the same page `messages.before` serves, so the window's own reader
            // never receives an empty page in place of the history it holds.
            if (before && !url.searchParams.has('after') && !url.searchParams.has('anchor_message_id')) {
              const older = history.page(state.messages, before, limit);
              return json(ok({ items: older.items, oldest_cursor: older.items[0]?.id ?? null, newest_cursor: older.items.at(-1)?.id ?? null,
                has_more_before: older.has_more_before, has_more_after: older.has_more_after, history: history.report() }));
            }
            const end = before ? state.messages.findIndex((item) => item.id === before) : state.messages.length;
            if (end < 0 || !Number.isFinite(limit)) throw problem(422, 'INVALID_CURSOR', 'Unknown cursor or invalid limit');
            const start = Math.max(0, end - limit);
            const items = structuredClone(state.messages.slice(start, end));
            for (const message of items) if (message.type === 'acp_permission') { message.content.readonly = true; message.status = 'finish'; }
            return json(ok({ items, oldest_cursor: items[0]?.id ?? null, newest_cursor: items.at(-1)?.id ?? null,
              has_more_before: start > 0 || history.hasOlder(), has_more_after: end < state.messages.length, history: history.report() }));
          }
          // One older page from the history store, answered in the window's own shape and stating what the host
          // could read of that store.
          if (method === 'GET' && route === '/messages/before') {
            const limit = Number(url.searchParams.get('limit') ?? 50);
            if (!Number.isFinite(limit) || limit < 1 || limit > 200) throw problem(422, 'INVALID_CURSOR', 'limit 必须是 1..200 的整数。');
            const page = history.page(state.messages, url.searchParams.get('before') ?? '', Math.floor(limit));
            return json(ok({ items: page.items, oldest_cursor: page.items[0]?.id ?? null, newest_cursor: page.items.at(-1)?.id ?? null,
              has_more_before: page.has_more_before, has_more_after: page.has_more_after, history: history.report() }));
          }
          if (method === 'POST' && route === '/messages') return json(ok(await send(body, request.headers.get('Idempotency-Key') ?? body.idempotency_key)));
          if (method === 'GET' && route === '/confirmations') return json(ok(state.pending().map(publicApproval)));
          const approval = route.match(/^\/confirmations\/([^/]+)\/confirm$/);
          if (method === 'POST' && approval) return json(ok(review(decodeURIComponent(approval[1]), body)));
          if (method === 'POST' && route === '/cancel') return json(ok(cancel(body)));
          if (method === 'POST' && route === '/active-lease') return json(ok({ expires_at: Date.now() + 60000, runtime: state.runtime() }));
          if (method === 'GET' && route === '/usage') return json(ok(state.usage));
          if (method === 'GET' && ['/artifacts', '/slash-commands'].includes(route)) return json(ok([]));
        }
        throw problem(404, 'ROUTE_NOT_FOUND', `Unsupported API: ${method} ${path}`);
      } catch (error) { return json({ success: false, code: error.code ?? 'BACKEND_ERROR', error: error.message }, error.status ?? 500); }
    },
    websocket: {
      open(socket) { sockets.add(socket); replay(socket, socket.data.cursor, socket.data.epoch); },
      close(socket) { sockets.delete(socket); },
      message(socket, text) {
        try {
          const frame = JSON.parse(String(text));
          if (frame.name === 'ping' || frame.type === 'ping') { socket.send(JSON.stringify({ name: 'pong' })); return; }
          if (frame.name === 'events.unsubscribe' || frame.action === 'events.unsubscribe') { socket.data.subscribed = false; return; }
          if (['events.subscribe', 'runtime.snapshot'].includes(frame.name ?? frame.action)) {
            const data = frame.data ?? frame.payload?.body ?? frame.payload ?? {};
            if ((data.conversation_id ?? C) !== C) throw new Error('Unknown conversation');
            socket.data.subscribed = true;
            replay(socket, data.seq ?? data.since ?? 0, data.epoch);
          }
        } catch (error) { socket.send(JSON.stringify({ name: 'realtime.error', data: { code: 'INVALID_SUBSCRIPTION', error: error.message } })); }
      },
    },
  });
  return { port: server.port, url: `http://127.0.0.1:${server.port}`, state, probeHeaders, setAllowedOrigins,
    // The fixture state a check flips to prove a branch, and the two stores its own answers are read from.
    history, hosts, cwd, recovery,
    async close() {
      closed = true;
      expireApprovals('backend_closed', true);
      if (state.turn && client && !client.closed) client.notify('session/cancel', { sessionId: state.nativeSessionId });
      for (const socket of sockets) socket.close(1001, 'backend closed');
      await client?.close();
      fake.close();
      server.stop(true);
      state.persist();
    },
  };
}
