// SPDX-License-Identifier: Apache-2.0
import { problem } from './session-state.mjs';

/**
 * The cross-session state the offline harness serves beside the open conversation, mirroring the host's own
 * routes in `renderer/contracts/host-v1.json`: the message queue one conversation hands another, the session
 * index cache, the history store one conversation's older pages are read from, the recovery state of its
 * runtime, and the saved hosts whose rows carry their Agent target and how they authenticate. Every answer
 * keeps the contract's field names and stable codes, so an offline check names the same fact a device would.
 */

/** The states one queued message can hold; a withdrawn message is kept as withdrawn, never as delivered. */
export const QUEUE_STATES = Object.freeze(['pending', 'delivered', 'withdrawn']);
export const MAX_QUEUE_ID_LENGTH = 128;
export const MAX_QUEUE_TEXT_LENGTH = 8192;
export const QUEUE_KINDS = Object.freeze(['notify', 'request']);

/** The two ways a saved host authenticates, as the requirement states them: a key it holds, or a password. */
export const AUTH_METHODS = Object.freeze(['key', 'password']);

/** The keys this fixture's product holds; a host names one as `key_id` and reads its label as `key_name`. */
export const FIXTURE_KEYS = Object.freeze([
  { id: 42, name: '离线探针密钥' },
  { id: 77, name: '备用离线密钥' },
]);

/** The password the seeded hosts hold: it lives in the host store alone, and no row ever carries it. */
export const SEEDED_HOST_PASSWORD = '离线探针口令不得回显';

/** How much of a conversation's history store the host could read, which every page of it has to state. */
export const HISTORY_STATES = Object.freeze(['complete', 'partial', 'unavailable']);

/** Whether the host still holds the session a runtime was attached to. */
export const RECOVERY_STATES = Object.freeze(['attached', 'gone', 'unknown']);

/** The two fields one session row states about its own working directory; a directory that is there has no note. */
export function cwdFields(missing = false, note = null) {
  return { cwd_missing: Boolean(missing), cwd_note: missing ? note : null };
}

/** One older row the history store holds below the window the caller already has: the page paging asks for. */
export function seededOlderHistory(conversationId) {
  return [
    {
      id: 'history-older-1', msg_id: 'history-older-1', conversation_id: conversationId, turn_id: null,
      type: 'text', position: 'left', created_at: 1700000000000, status: 'finish',
      content: { content: '更早的一页历史：离线探针的旧记录。' },
    },
  ];
}

/**
 * The one history row the native fixture's window starts with, above `seededOlderHistory`. Without a loaded row
 * there is no cursor to page from, so the older page could never be reached at all.
 */
export function seededHistoryWindow(conversationId) {
  return [
    {
      id: 'history-window-1', msg_id: 'history-window-1', conversation_id: conversationId, turn_id: null,
      type: 'text', position: 'left', created_at: 1700000000001, status: 'finish',
      content: { content: '已加载的一页历史：离线探针的记录。' },
    },
  ];
}

/**
 * The history store the older pages of a conversation are read from, below the window the caller already holds.
 * Its state is what the host could read of it — `complete`, `partial` (the note says what stayed unread) or
 * `unavailable` — and a store that could not be read refuses a page instead of answering an empty one that would
 * read like the start of the history. `conversations` is what its own clear entry reports.
 */
export class HistoryStore {
  constructor({ older = [], conversations = 1, state = 'complete', note = null } = {}) {
    if (!HISTORY_STATES.includes(state)) throw new Error(`Unknown history state: ${state}`);
    this.older = structuredClone(older);
    this.conversations = conversations;
    this.state = state;
    this.note = note;
  }

  /** What every page of this conversation states about the part of the history the host could read. */
  report() {
    return { state: this.state, note: this.note };
  }

  /** Whether this store holds a row below the caller's window, which the window page has to state. */
  hasOlder() {
    return this.older.length > 0;
  }

  /**
   * One page of the rows older than `cursor`, where `window` is what the caller already holds. A cursor this
   * conversation never held is refused as itself, and a cursor with nothing below it is refused as the end of
   * the history rather than answered as an empty page.
   */
  page(window, cursor, limit) {
    if (this.state === 'unavailable') throw problem(500, 'HISTORY_LOAD_FAILED', '更早的历史未能读取。');
    const all = [...this.older, ...window];
    const end = all.findIndex((row) => row.id === cursor);
    if (end < 0) throw problem(422, 'INVALID_CURSOR', '游标不在当前已加载的历史中。');
    const start = Math.max(0, end - limit);
    if (start === end) throw problem(409, 'HISTORY_EXHAUSTED', '该会话没有更早的一页历史。');
    return { items: structuredClone(all.slice(start, end)), has_more_before: start > 0, has_more_after: true };
  }

  clear() {
    if (this.clearFailure) throw problem(500, 'HISTORY_CLEAR_FAILED', this.clearFailure);
    const conversations = this.conversations;
    this.older = [];
    this.conversations = 0;
    this.state = 'complete';
    this.note = null;
    return { cleared: true, conversations };
  }
}

export class MessageQueue {
  // shortcut: rows live in memory for one probe run, back them with the run directory when a check has to
  // survive a backend restart the way the product's queue file does.
  constructor({ now = () => Date.now() } = {}) {
    this.now = now;
    this.rows = [];
    this.sequence = 0;
    // The harness queue is never full, so its retention cap drops nothing.
    this.dropped = 0;
  }

  /** Queues one message for another conversation. It is held here; nothing is delivered by this call. */
  enqueue({ conversationId, hostId, agent, sessionId, kind, text }) {
    if (!QUEUE_KINDS.includes(kind)) throw problem(422, 'INVALID_KIND', 'kind is neither notify nor request');
    if (typeof text !== 'string' || !text.trim() || text.length > MAX_QUEUE_TEXT_LENGTH) throw problem(422, 'INVALID_CONTENT', 'text must be non-empty and bounded');
    const row = {
      queue_id: `queue-probe-${++this.sequence}`,
      conversation_id: conversationId,
      host_id: hostId ?? null,
      agent: agent ?? null,
      session_id: sessionId ?? null,
      kind,
      text,
      created_at: this.now(),
      state: 'pending',
    };
    this.rows.push(row);
    return { ...row, pending: true, queued: true, delivered: false };
  }

  /** Every message still waiting for its conversation, oldest first. Reading the queue delivers nothing. */
  pending(sessionId) {
    const rows = this.rows.filter((row) => row.state === 'pending');
    const filtered = sessionId ? rows.filter((row) => row.session_id === sessionId) : rows;
    return filtered.map((row) => ({ ...row, pending: true }));
  }

  /** All rows the queue holds, withdrawn and delivered ones included: fixture diagnostics, not a route answer. */
  all() {
    return this.rows.map((row) => ({ ...row, pending: row.state === 'pending' }));
  }

  stats() {
    return {
      stored: this.rows.length,
      pending: this.rows.filter((row) => row.state === 'pending').length,
      delivered: this.rows.filter((row) => row.state === 'delivered').length,
      dropped: this.dropped,
    };
  }

  /** The conversation took the message; only fixture diagnostics call this, no route does. */
  deliver(queueId) {
    return this.transition(queueId, 'delivered');
  }

  /**
   * Withdraws one queued message so it can never be delivered later. A message its conversation already took is
   * refused as itself, and an id the queue no longer holds as pending is not found.
   */
  cancel(queueId) {
    if (typeof queueId !== 'string' || !queueId || queueId.length > MAX_QUEUE_ID_LENGTH || /[\u0000-\u001f]/.test(queueId))
      throw problem(422, 'INVALID_QUEUE_ID', 'queueId is empty, over-long or carries control characters');
    const row = this.rows.find((item) => item.queue_id === queueId);
    if (!row) throw problem(404, 'QUEUE_NOT_FOUND', 'The queue holds no pending message with this id');
    if (row.state === 'delivered') throw problem(409, 'QUEUE_NOT_PENDING', 'Its conversation already took this message');
    if (row.state === 'withdrawn') throw problem(404, 'QUEUE_NOT_FOUND', 'The queue holds no pending message with this id');
    row.state = 'withdrawn';
    return { cancelled: true, queue_id: queueId, delivered: false };
  }

  transition(queueId, state) {
    const row = this.rows.find((item) => item.queue_id === queueId);
    if (!row) throw problem(404, 'QUEUE_NOT_FOUND', 'Unknown queue id');
    row.state = state;
    return row;
  }
}

/** The session index cache, whose clear entry reports what the index held before and what it holds after. */
export class SessionIndex {
  constructor(rows = []) {
    this.rows = [...rows];
  }

  count() {
    return this.rows.length;
  }

  clear() {
    const before = this.rows.length;
    this.rows = [];
    return { cleared: true, sessions_before: before, sessions: this.rows.length };
  }
}

/**
 * The saved hosts the harness serves, with the credential each one holds. A password lives in this store alone:
 * no row it answers carries one, only the `has_password` fact, so a create or an update that names a password
 * answers the row without it. The write rules are the host's own: a method the request names has to be one this
 * app can use, password authentication needs a password the request brings or the host already holds, an absent
 * or empty password field keeps the stored one, and an explicit null clears it.
 */
export class HostStore {
  constructor(rows = fixtureHosts()) {
    this.rows = rows.map((row) => ({ ...row }));
    this.passwords = new Map(this.rows.filter((row) => row.has_password).map((row) => [row.id, SEEDED_HOST_PASSWORD]));
    this.sequence = this.rows.length;
  }

  list() {
    const items = this.rows.map((row) => this.publicRow(row));
    return { items, total: items.length };
  }

  create(body) {
    const nickname = typeof body?.nickname === 'string' ? body.nickname.trim() : '';
    if (!nickname) throw problem(422, 'INVALID_REQUEST', '昵称不能为空。');
    const osType = typeof body.os_type === 'string' ? body.os_type : 'linux';
    const agentTarget = typeof body.agent_target === 'string' ? body.agent_target : 'native';
    const row = {
      id: `host-${++this.sequence}`, nickname,
      hostname: typeof body.hostname === 'string' ? body.hostname : '127.0.0.1',
      port: Number.isInteger(body.port) ? body.port : 22,
      username: typeof body.username === 'string' ? body.username : '',
      protocol: 'ssh', os_type: osType, agent_target: agentTarget, target: hostTarget(osType, agentTarget),
      target_supported: true, session_count: 0, detection: { checked: false, agents: [] },
      // A new SSH host starts on key authentication and names no key: that is what the product creates.
      auth_method: 'key', key_id: null, key_name: null, has_password: false,
    };
    this.applyAuth(row, body);
    if (this.rows.some((item) => item.nickname === nickname)) throw problem(409, 'HOST_NICKNAME_TAKEN', `已有同名主机：${nickname}。`);
    this.rows.push(row);
    return this.publicRow(row);
  }

  /** Rewrites only the fields the request names, so a changed address leaves the key association it does not name. */
  update(hostId, body) {
    const row = this.rows.find((item) => item.id === hostId);
    if (!row) throw problem(404, 'HOST_NOT_FOUND', '没有这台主机。');
    for (const field of ['nickname', 'hostname', 'username', 'os_type', 'agent_target'])
      if (typeof body?.[field] === 'string') row[field] = body[field];
    if (Number.isInteger(body?.port)) row.port = body.port;
    row.target = hostTarget(row.os_type, row.agent_target);
    this.applyAuth(row, body);
    return this.publicRow(row);
  }

  /** The credential fields one create or update body carries: the method, the key that method names, the password. */
  applyAuth(row, body) {
    const named = body?.auth_method !== undefined && body.auth_method !== null;
    if (named && !AUTH_METHODS.includes(body.auth_method))
      throw problem(422, 'AUTH_METHOD_INVALID', `认证方式只能是 ${AUTH_METHODS.join(' 或 ')}。`);
    const method = named ? body.auth_method : row.auth_method;
    const password = passwordChange(body);
    // A host left on password authentication needs a password: either the one this request brings or one it holds.
    if (method === 'password' && password.change !== 'store' && !this.passwords.has(row.id))
      throw problem(422, 'PASSWORD_REQUIRED', '认证方式为密码时必须给出密码；这台主机还没有保存过密码。');
    if (named) row.auth_method = method;
    // A key the request names has to be a positive id; one it leaves out — or sends as an explicit null, which this
    // host reads as not naming one — keeps the association the row holds.
    if (body?.key_id !== undefined && body.key_id !== null) {
      if (!Number.isInteger(body.key_id) || body.key_id <= 0) throw problem(422, 'INVALID_REQUEST', '密钥 id 必须是正整数。');
      row.key_id = body.key_id;
      // Only a key this product holds has a label; an id it holds no key for names none.
      row.key_name = FIXTURE_KEYS.find((key) => key.id === body.key_id)?.name ?? null;
    }
    if (password.change === 'clear') this.passwords.delete(row.id);
    if (password.change === 'store') this.passwords.set(row.id, password.value);
    row.has_password = this.passwords.has(row.id);
  }

  /** One row as the host answers it; the password itself stays in this store. */
  publicRow(row) {
    return { ...row, has_password: this.passwords.has(row.id) };
  }
}

/** The shell context one host's OS and Agent target add up to, the way the product derives it. */
function hostTarget(osType, agentTarget) {
  if (String(agentTarget).startsWith('wsl:')) return agentTarget;
  return osType === 'windows' || agentTarget === 'windows' ? 'windows-native' : 'posix';
}

/** The password one request carries: an absent or empty field says nothing, an explicit null clears it. */
function passwordChange(body) {
  if (body?.password === undefined) return { change: 'keep' };
  if (body.password === null) return { change: 'clear' };
  if (typeof body.password !== 'string') throw problem(422, 'INVALID_REQUEST', '密码必须是字符串。');
  return body.password === '' ? { change: 'keep' } : { change: 'store', value: body.password };
}

/**
 * The saved-host rows the harness serves. The Windows row carries the Agent on Windows itself under the target
 * `windows` — the literal this batch adds — beside a WSL distribution and a Linux host. The four credential
 * fields state how each host authenticates, which key it names and whether it holds a password.
 */
export function fixtureHosts() {
  return [
    {
      id: 'host-1', nickname: '离线 Linux', hostname: '127.0.0.1', port: 22, username: 'probe', protocol: 'ssh',
      os_type: 'linux', agent_target: 'native', target: 'posix', target_supported: true, session_count: 1,
      auth_method: 'key', key_id: 42, key_name: '离线探针密钥', has_password: false,
      detection: { checked: true, agents: [{ id: 'dsh', state: 'available', executable: '/usr/sbin/dsh', version: '0.2.1', capabilities: { acp: true }, error: '' }] },
    },
    {
      id: 'host-2', nickname: '离线 Windows', hostname: '10.0.0.2', port: 22, username: 'probe', protocol: 'ssh',
      os_type: 'windows', agent_target: 'windows', target: 'windows-native', target_supported: true, session_count: 0,
      auth_method: 'password', key_id: null, key_name: null, has_password: true,
      detection: { checked: true, agents: [{ id: 'dsh', state: 'available', executable: 'C:\\dsh.exe', version: '0.2.1', capabilities: null, error: '' }] },
    },
    {
      id: 'host-3', nickname: '离线 Windows WSL', hostname: '10.0.0.3', port: 22, username: 'probe', protocol: 'ssh',
      os_type: 'windows', agent_target: 'wsl:Ubuntu', target: 'wsl:Ubuntu', target_supported: true, session_count: 0,
      auth_method: 'key', key_id: null, key_name: null, has_password: false,
      detection: { checked: false, agents: [] },
    },
  ];
}
