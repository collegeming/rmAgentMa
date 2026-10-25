// SPDX-License-Identifier: Apache-2.0
import { problem } from './session-state.mjs';

/**
 * The cross-session state the offline harness serves beside the open conversation, mirroring the host's own
 * routes in `renderer/contracts/host-v1.json`: the message queue one conversation hands another, the session
 * index cache, and the saved hosts whose rows carry their Agent target. Every answer keeps the contract's field
 * names and stable codes, so an offline check names the same fact a device would.
 */

/** The states one queued message can hold; a withdrawn message is kept as withdrawn, never as delivered. */
export const QUEUE_STATES = Object.freeze(['pending', 'delivered', 'withdrawn']);
export const MAX_QUEUE_ID_LENGTH = 128;
export const MAX_QUEUE_TEXT_LENGTH = 8192;
export const QUEUE_KINDS = Object.freeze(['notify', 'request']);

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
 * The saved-host rows the harness serves. The Windows row carries the Agent on Windows itself under the target
 * `windows` — the literal this batch adds — beside a WSL distribution and a Linux host.
 */
export function fixtureHosts() {
  return [
    {
      id: 'host-1', nickname: '离线 Linux', hostname: '127.0.0.1', port: 22, username: 'probe', protocol: 'ssh',
      os_type: 'linux', agent_target: 'native', target: 'posix', target_supported: true, session_count: 1,
      detection: { checked: true, agents: [{ id: 'dsh', state: 'available', executable: '/usr/sbin/dsh', version: '0.2.1', capabilities: { acp: true }, error: '' }] },
    },
    {
      id: 'host-2', nickname: '离线 Windows', hostname: '10.0.0.2', port: 22, username: 'probe', protocol: 'ssh',
      os_type: 'windows', agent_target: 'windows', target: 'windows-native', target_supported: true, session_count: 0,
      detection: { checked: true, agents: [{ id: 'dsh', state: 'available', executable: 'C:\\dsh.exe', version: '0.2.1', capabilities: null, error: '' }] },
    },
    {
      id: 'host-3', nickname: '离线 Windows WSL', hostname: '10.0.0.3', port: 22, username: 'probe', protocol: 'ssh',
      os_type: 'windows', agent_target: 'wsl:Ubuntu', target: 'wsl:Ubuntu', target_supported: true, session_count: 0,
      detection: { checked: false, agents: [] },
    },
  ];
}
