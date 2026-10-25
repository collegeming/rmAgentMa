// SPDX-License-Identifier: Apache-2.0
import { randomUUID } from 'node:crypto';
import { AttachmentStore } from './attachment-store.mjs';
import { MessageQueue, SessionIndex, fixtureHosts } from './host-fixture.mjs';

const C = 'conv-probe-1';
const P = 'project-probe-1';
const envelope = (data, status = 200) => ({ status, body: { success: true, data } });
const error = (status, code) => Object.assign(new Error(code), { status, code });

/**
 * The native bridge's offline host: one conversation, one draft, one model, a real attachment upload store
 * (`attachment.begin/chunk/finish/abort`), the message queue, the index cache and the saved-host rows. It
 * answers the frozen renderer exactly where the Kotlin host would, so the packaged-asset probe covers the
 * same contract without a device.
 */
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
    // The injected failures one check turns on: a refused send, and no open conversation to attach to.
    this.failNextSend = false;
    // The conversation an attachment belongs to; with none open the host refuses uploads as unsupported.
    this.openConversation = C;
    this.uploads = new AttachmentStore();
    this.queue = new MessageQueue();
    this.index = new SessionIndex([{ session_id: C }]);
  }
  /** The finished attachments, by reference — the map `messages.send` and the checks read. */
  get attachments() {
    return this.uploads.finished;
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
  validateFiles(files) { this.uploads.validate(files); }
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
    if (action === 'draft.load') return { status: 200, body: { text: this.draft, files: [], conversation_id: C } };
    if (action === 'draft.save') {
      if (typeof body.text !== 'string' || body.text.length > 16384) throw error(413, 'DRAFT_TOO_LARGE');
      // A draft is the text alone: an attachment belongs to the message that names it, so a save that carries
      // files is refused here exactly as the product host refuses it.
      if ((body.files ?? []).length > 0) throw error(422, 'ATTACHMENT_UNSUPPORTED');
      this.draft = body.text;
      this.draftFiles = [];
      return { status: 200, body: { text: this.draft, files: [], saved: true } };
    }
    if (action.startsWith('attachment.')) return this.attachment(action, body, documentId);
    if (action === 'queue.list') {
      const items = this.queue.pending(query.session_id);
      return envelope({ items, total: items.length, stats: this.queue.stats() });
    }
    if (action === 'queue.send') {
      const target = body.conversation_id ?? C;
      if (target !== C) throw error(404, 'CONVERSATION_NOT_FOUND');
      const queued = this.queue.enqueue({ conversationId: target, hostId: body.host_id, agent: body.agent, sessionId: body.session_id, kind: body.kind, text: body.text });
      return envelope({
        queue_id: queued.queue_id, conversation_id: queued.conversation_id, host_id: queued.host_id, agent: queued.agent,
        session_id: queued.session_id, kind: queued.kind, state: queued.state, queued: true, delivered: false,
      });
    }
    if (action === 'queue.cancel') return envelope(this.queue.cancel(params.queueId));
    if (action === 'index.clear') return envelope(this.index.clear());
    if (action === 'hosts.list') {
      const items = fixtureHosts();
      return envelope({ items, total: items.length });
    }
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
  /**
   * The four upload actions of the shared host store. An upload belongs to the open conversation, so with none
   * open the host refuses them as unsupported rather than accepting bytes nothing could send.
   */
  attachment(action, body, documentId) {
    if (!this.openConversation) throw error(501, 'ATTACHMENT_UNSUPPORTED');
    if (action === 'attachment.begin') return { status: 201, body: this.uploads.begin(documentId, body) };
    if (action === 'attachment.chunk') return { status: 200, body: this.uploads.chunk(documentId, body) };
    if (action === 'attachment.finish') return { status: 201, body: this.uploads.finish(documentId, body) };
    if (action === 'attachment.abort') return { status: 200, body: this.uploads.abort(documentId, body) };
    throw error(404, 'UNKNOWN_ACTION');
  }
}

export { C as PROBE_CONVERSATION_ID };
