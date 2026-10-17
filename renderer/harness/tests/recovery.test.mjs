// SPDX-License-Identifier: Apache-2.0
import { describe, expect, test } from 'bun:test';
import { mkdtempSync, writeFileSync, readFileSync, appendFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { SessionState, readJournal, appendJournal } from '../session-state.mjs';
import { startBackend } from '../backend.mjs';

const fresh = () => mkdtempSync(join(tmpdir(), 'rmagent-recovery-unit-'));
function baseline(dir) {
  const state = new SessionState(dir);
  state.addMessage('text', { content: '中文完整快照' }, 'persisted-text');
  state.emit('message.stream', { content: '中文完整快照' });
  state.emit('turn.completed', { status: 'finished' });
  writeFileSync(join(dir, 'fake-requests.jsonl'), '{"model":"fake-1","stage":"final"}\n');
  return state;
}

describe('journal recovery without an agent', () => {
  test('only a syntactically incomplete unterminated final record is tolerated', () => {
    for (const tail of ['{"name":', '{"content":"中文', '{"ok":tru', '{"items":[1,', '{"x":"\\u12']) {
      const path = join(fresh(), 'journal.jsonl');
      writeFileSync(path, '{"valid":true}\n' + tail);
      expect(readJournal(path).records).toEqual([{ valid: true }]);
      expect(readJournal(path).issues[0]).toMatchObject({ reason: 'incomplete_tail', line: 2 });
      expect(() => appendJournal(path, { next: true })).toThrow('incomplete journal');
      expect(readFileSync(path, 'utf8')).toEndWith(tail);
    }
    for (const damaged of ['{"x":bad', '{"x" 7', '{"x":"\\q', '{"x":01', 'garbage', '{"x":}\n']) {
      const path = join(fresh(), 'journal.jsonl');
      writeFileSync(path, '{"valid":true}\n' + damaged);
      expect(() => readJournal(path)).toThrow('Corrupt journal');
    }
  });

  test('incomplete UTF-8 at the final string boundary is retained, invalid interior UTF-8 is rejected', () => {
    const path = join(fresh(), 'journal.jsonl');
    const prefix = Buffer.from('{"valid":true}\n{"text":"中');
    writeFileSync(path, prefix.subarray(0, prefix.length - 1));
    expect(readJournal(path).issues).toHaveLength(1);
    writeFileSync(path, Buffer.concat([Buffer.from('{"x":"'), Buffer.from([0xff]), Buffer.from('"}\n')]));
    expect(() => readJournal(path)).toThrow('Corrupt UTF-8');
  });

  test('complete final JSON without newline is preserved before a subsequent append', () => {
    const path = join(fresh(), 'journal.jsonl');
    writeFileSync(path, '{"one":1}');
    appendJournal(path, { two: 2 });
    expect(readJournal(path)).toEqual({ records: [{ one: 1 }, { two: 2 }], issues: [] });
  });

  for (const file of ['events.jsonl', 'fake-requests.jsonl', 'fake-streams.jsonl']) {
    test(`${file}: a full snapshot and truncated tail recover as read-only without changing evidence`, async () => {
      const dir = fresh();
      const backend = await startBackend({ runDir: dir });
      await backend.close();
      baseline(dir);
      const snapshotBefore = readFileSync(join(dir, 'snapshot.json'));
      const journal = join(dir, file);
      appendFileSync(journal, '{"unfinished":');
      const bytesBefore = readFileSync(journal);
      const recovered = await startBackend({ runDir: dir });
      try {
        const status = await (await fetch(recovered.url + '/api/probe/status', { headers: recovered.probeHeaders })).json();
        expect(status.data.read_only).toBe(true);
        expect(status.data.recovery_issues.some((issue) => issue.file === journal)).toBe(true);
        expect(status.data.messages.some((message) => message.id === 'persisted-text')).toBe(true);
        expect(status.data.runtime.can_send_message).toBe(false);
        expect(status.data.fake_requests).toHaveLength(1);
        for (const route of ['/runtime/ensure', '/messages']) {
          const response = await fetch(recovered.url + '/api/conversations/conv-probe-1' + route, { method: 'POST', headers: { ...recovered.probeHeaders, 'Content-Type': 'application/json' }, body: '{"content":"must not start dsh"}' });
          expect(response.status).toBe(409);
        }
      } finally { await recovered.close(); }
      expect(readFileSync(journal)).toEqual(bytesBefore);
      expect(readFileSync(join(dir, 'snapshot.json'))).toEqual(snapshotBefore);
    });

    test(`${file}: interior corruption fails closed even with an intact snapshot`, async () => {
      const dir = fresh();
      const owner = await startBackend({ runDir: dir });
      await owner.close();
      baseline(dir);
      writeFileSync(join(dir, file), '{"valid":true}\n{"broken":\n{"valid":true}\n');
      await expect(startBackend({ runDir: dir })).rejects.toThrow('Corrupt journal');
    });
  }

  test('snapshot ahead of or behind the journal stays read-only; corrupt snapshots never silently reset', () => {
    for (const delta of [-1, 1]) {
      const dir = fresh();
      baseline(dir);
      const path = join(dir, 'snapshot.json');
      const snapshot = JSON.parse(readFileSync(path, 'utf8'));
      snapshot.seq += delta;
      writeFileSync(path, JSON.stringify(snapshot));
      const recovered = new SessionState(dir);
      expect(recovered.readOnly).toBe(true);
      expect(recovered.seq).toBe(Math.max(2, snapshot.seq));
      expect(recovered.recoveryIssues).toContainEqual({ reason: 'snapshot_journal_mismatch', snapshot_seq: snapshot.seq, journal_seq: 2 });
      expect(() => recovered.emit('write', {})).toThrow('read-only');
    }
    const dir = fresh();
    baseline(dir);
    writeFileSync(join(dir, 'snapshot.json'), '{"incomplete":');
    expect(() => new SessionState(dir)).toThrow();
  });
});
