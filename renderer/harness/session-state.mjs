// SPDX-License-Identifier: Apache-2.0
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync, renameSync, appendFileSync } from 'node:fs';
import { join } from 'node:path';

export const CONVERSATION_ID = 'conv-probe-1';
export const PROJECT_ID = 'project-probe-1';
export const id = () => randomUUID();
export function problem(status, code, message) { return Object.assign(new Error(message), { status, code }); }

function incompleteJson(text) {
  let offset = 0;
  const incomplete = Symbol('incomplete');
  const invalid = Symbol('invalid');
  const space = () => { while (/\s/.test(text[offset] ?? '') && offset < text.length) offset++; };
  const take = (char) => {
    if (offset === text.length) throw incomplete;
    if (text[offset++] !== char) throw invalid;
  };
  const string = () => {
    take('"');
    while (offset < text.length) {
      const char = text[offset++];
      if (char === '"') return;
      if (char.charCodeAt(0) < 32) throw invalid;
      if (char !== '\\') continue;
      if (offset === text.length) throw incomplete;
      const escaped = text[offset++];
      if (escaped === 'u') {
        for (let i = 0; i < 4; i++) {
          if (offset === text.length) throw incomplete;
          if (!/[\da-f]/i.test(text[offset++])) throw invalid;
        }
      } else if (!'"\\/bfnrt'.includes(escaped)) throw invalid;
    }
    throw incomplete;
  };
  const value = () => {
    space();
    if (offset === text.length) throw incomplete;
    const char = text[offset];
    if (char === '"') return string();
    if (char === '{' || char === '[') {
      offset++;
      space();
      const close = char === '{' ? '}' : ']';
      if (text[offset] === close) { offset++; return; }
      while (true) {
        if (char === '{') { string(); space(); take(':'); }
        value();
        space();
        if (offset === text.length) throw incomplete;
        if (text[offset] === close) { offset++; return; }
        take(',');
        space();
      }
    }
    for (const literal of ['true', 'false', 'null']) {
      if (char !== literal[0]) continue;
      for (const expected of literal) take(expected);
      return;
    }
    if (char !== '-' && !/\d/.test(char)) throw invalid;
    if (text[offset] === '-') offset++;
    if (offset === text.length) throw incomplete;
    if (text[offset] === '0') offset++;
    else {
      if (!/[1-9]/.test(text[offset])) throw invalid;
      while (/\d/.test(text[offset] ?? '')) offset++;
    }
    if (text[offset] === '.') {
      offset++;
      if (offset === text.length) throw incomplete;
      if (!/\d/.test(text[offset])) throw invalid;
      while (/\d/.test(text[offset] ?? '')) offset++;
    }
    if (/[eE]/.test(text[offset] ?? '')) {
      offset++;
      if (/[+-]/.test(text[offset] ?? '')) offset++;
      if (offset === text.length) throw incomplete;
      if (!/\d/.test(text[offset])) throw invalid;
      while (/\d/.test(text[offset] ?? '')) offset++;
    }
  };
  try { value(); space(); return false; } catch (error) { return error === incomplete; }
}

export function readJournal(path) {
  if (!existsSync(path)) return { records: [], issues: [] };
  const bytes = readFileSync(path);
  const records = [];
  const issues = [];
  let offset = 0;
  let line = 1;
  while (offset < bytes.length) {
    const newline = bytes.indexOf(10, offset);
    const end = newline < 0 ? bytes.length : newline;
    const raw = bytes.subarray(offset, end);
    let text;
    let utf8Incomplete = false;
    try { text = new TextDecoder('utf-8', { fatal: true }).decode(raw); }
    catch {
      if (newline >= 0) throw new Error(`Corrupt UTF-8 journal: ${path}:${line}`);
      const decoder = new TextDecoder('utf-8', { fatal: true });
      try { text = decoder.decode(raw, { stream: true }); } catch { throw new Error(`Corrupt UTF-8 journal: ${path}:${line}`); }
      utf8Incomplete = true;
    }
    try {
      if (utf8Incomplete) throw new Error('Incomplete UTF-8');
      const record = JSON.parse(text);
      if (!record || typeof record !== 'object' || Array.isArray(record)) throw new Error('Expected a journal object');
      records.push(record);
    } catch (error) {
      if (newline >= 0 || !incompleteJson(text)) throw new Error(`Corrupt journal: ${path}:${line}: ${error.message}`);
      issues.push({ file: path, line, offset, bytes: raw.length, reason: 'incomplete_tail' });
    }
    offset = end + 1;
    line++;
  }
  return { records, issues };
}

export function appendJournal(path, record) {
  if (existsSync(path)) {
    const bytes = readFileSync(path);
    if (bytes.length && bytes.at(-1) !== 10) {
      const journal = readJournal(path);
      if (journal.issues.length) throw new Error(`Refusing to append to incomplete journal: ${path}`);
      appendFileSync(path, '\n', { mode: 0o600 });
    }
  }
  appendFileSync(path, JSON.stringify(record) + '\n', { mode: 0o600 });
}

export class SessionState {
  constructor(runDir, { deferPersist = false } = {}) {
    this.runDir = runDir;
    this.path = join(runDir, 'snapshot.json');
    const previous = existsSync(this.path) ? JSON.parse(readFileSync(this.path, 'utf8')) : null;
    this.epoch = id();
    this.seq = previous?.seq ?? 0;
    this.nativeSessionId = previous?.nativeSessionId ?? null;
    this.messages = previous?.messages ?? [{
      id: 'history-permission-1', msg_id: 'history-permission-1', conversation_id: CONVERSATION_ID,
      type: 'acp_permission', position: 'left', created_at: Date.now(), status: 'finish',
      content: { session_id: CONVERSATION_ID, request_id: 'history-read-only', epoch: 'historical', readonly: true,
        options: [{ option_id: 'allow-once', name: 'Allow once', kind: 'allow_once' }, { option_id: 'reject-once', name: 'Reject', kind: 'reject_once' }],
        tool_call: { tool_call_id: 'history-tool', title: '历史审批（只读）', kind: 'other' } },
    }];
    for (const message of this.messages) if (message.type === 'acp_permission') { message.content.readonly = true; message.status = 'finish'; }
    this.receipts = new Map(previous?.receipts ?? []);
    this.approvals = new Map((previous?.approvals ?? []).map(([key, value]) => [key, { ...value, state: value.state === 'answered' ? 'answered' : 'expired' }]));
    const journal = readJournal(join(runDir, 'events.jsonl'));
    this.events = journal.records;
    this.recoveryIssues = [...journal.issues];
    let seq = 0;
    for (const event of this.events) {
      if (!Number.isSafeInteger(event.seq) || event.seq !== seq + 1 || typeof event.epoch !== 'string') throw new Error('Event journal sequence is corrupt');
      seq = event.seq;
    }
    if ((previous?.seq ?? 0) !== seq) this.recoveryIssues.push({ reason: 'snapshot_journal_mismatch', snapshot_seq: previous?.seq ?? 0, journal_seq: seq });
    this.seq = Math.max(this.seq, seq);
    this.readOnly = this.recoveryIssues.length > 0;
    this.status = this.readOnly || previous?.turn || previous?.status === 'interrupted' ? 'interrupted' : 'idle';
    this.turn = null;
    this.lastTurn = previous?.lastTurn ?? null;
    this.options = [];
    this.usage = null;
    this.ready = false;
    this.error = previous?.turn ? 'Backend restarted; previous turn result is unconfirmed. Prompt will not be replayed.' : null;
    this.draft = previous?.draft ?? '';
    this.listeners = new Set();
    if (!deferPersist) this.persist();
  }

  runtime() {
    return {
      state: this.status, can_send_message: this.ready && this.status === 'idle', has_task: Boolean(this.turn),
      is_processing: Boolean(this.turn), pending_confirmations: this.pending().length, turn_id: this.turn?.id ?? null,
      supports_midturn_delivery: false, epoch: this.epoch, seq: this.seq,
    };
  }

  pending() { return [...this.approvals.values()].filter((approval) => approval.state === 'waiting' && approval.epoch === this.epoch); }

  persist() {
    if (this.readOnly) return;
    const body = { epoch: this.epoch, seq: this.seq, nativeSessionId: this.nativeSessionId, messages: this.messages, status: this.status,
      receipts: [...this.receipts], approvals: [...this.approvals], turn: this.turn, lastTurn: this.lastTurn, draft: this.draft };
    writeFileSync(this.path + '.tmp', JSON.stringify(body), { mode: 0o600 });
    renameSync(this.path + '.tmp', this.path);
  }

  emit(name, data) {
    if (this.readOnly) throw problem(409, 'RECOVERY_READ_ONLY', 'Journal recovery is read-only');
    const event = { type: 'event', documentId: '', name, data: structuredClone(data), epoch: this.epoch, seq: this.seq + 1 };
    appendJournal(join(this.runDir, 'events.jsonl'), event);
    this.seq = event.seq;
    this.events.push(event);
    this.persist();
    for (const listener of this.listeners) listener(event);
    return event;
  }

  stream(type, data, msgId = id(), extra = {}) {
    return this.emit('message.stream', { type, data, msg_id: msgId, conversation_id: CONVERSATION_ID, turn_id: this.turn?.id, ...extra });
  }

  addMessage(type, content, msgId = id(), extra = {}) {
    const message = { id: msgId, msg_id: msgId, conversation_id: CONVERSATION_ID, turn_id: this.turn?.id,
      type, position: 'left', created_at: Date.now(), status: 'finish', content, ...extra };
    this.messages.push(message);
    return message;
  }

  snapshot() {
    const messages = structuredClone(this.messages);
    for (const message of messages) if (message.type === 'acp_permission') { message.content.readonly = true; message.status = 'finish'; }
    return { conversation_id: CONVERSATION_ID, epoch: this.epoch, seq: this.seq, runtime: this.runtime(),
      config_options: this.options, pending: this.pending().map(({ rpc_id, ...approval }) => approval), messages, error: this.error,
      read_only: this.readOnly, recovery_issues: this.recoveryIssues };
  }
}
