// SPDX-License-Identifier: Apache-2.0
import { mkdirSync, readFileSync, existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { readJournal, appendJournal } from './session-state.mjs';

export const LOCAL_MODELS = ['fake-1', 'fake-2'];
export const LOCAL_VALUES = LOCAL_MODELS.map((model) => JSON.stringify(['fake', model]));
const quote = (text) => `'${text.replaceAll("'", "'\\''")}'`;

export function startFakeModel({ runDir }) {
  const targetsPath = join(runDir, 'fake-targets.json');
  const turns = new Map(existsSync(targetsPath) ? JSON.parse(readFileSync(targetsPath, 'utf8')) : []);
  const requestsPath = join(runDir, 'fake-requests.jsonl');
  const journal = readJournal(requestsPath);
  const requests = journal.records;
  const streamsPath = join(runDir, 'fake-streams.jsonl');
  const streamJournal = readJournal(streamsPath);
  const streams = new Map(streamJournal.records.map((record) => [record.request_id, record]));
  const saveStream = (stream) => {
    appendJournal(streamsPath, stream);
    streams.set(stream.request_id, stream);
  };
  const sockets = new Map();
  const server = Bun.serve({
    hostname: '127.0.0.1', port: 0,
    async fetch(request) {
      const path = new URL(request.url).pathname;
      if (request.method === 'GET' && path === '/v1/models') return Response.json({ object: 'list', data: LOCAL_MODELS.map((id) => ({ id, object: 'model' })) });
      if (request.method !== 'POST' || path !== '/v1/chat/completions') return Response.json({ error: 'Unknown fake-model route' }, { status: 404 });
      if (journal.issues.length || streamJournal.issues.length) return Response.json({ error: 'Fake request journal recovery is read-only' }, { status: 409 });
      let body;
      try { body = await request.json(); } catch { return Response.json({ error: 'Invalid JSON' }, { status: 400 }); }
      if (!LOCAL_MODELS.includes(body.model) || !body.stream) return Response.json({ error: 'Only local streaming models allowed' }, { status: 422 });
      const messages = body.messages ?? [];
      let userIndex = -1;
      let marker;
      for (let i = messages.length - 1; i >= 0; i--) {
        if (messages[i].role !== 'user') continue;
        const content = messages[i].content;
        const text = typeof content === 'string' ? content : (content ?? []).map((part) => part.text ?? '').join('');
        const match = [...text.matchAll(/\[probe-turn:([a-zA-Z0-9-]+)\]/g)].at(-1);
        if (match) { userIndex = i; marker = match[1]; break; }
      }
      const turn = turns.get(marker);
      if (!turn) return Response.json({ error: 'Prompt was not registered by the isolated backend' }, { status: 403 });
      const tail = messages.slice(userIndex + 1);
      const calls = tail.flatMap((message) => message.tool_calls ?? []);
      const stage = calls.some((call) => call.id === `${marker}-escalate`) ? 'final' : calls.some((call) => call.id === `${marker}-sandbox`) ? 'escalate' : 'sandbox';
      const record = { request_id: crypto.randomUUID(), model: body.model, turn_id: marker, scenario: turn.scenario, stage, messages: messages.length,
        stream: body.stream === true, acp_text_delivery: 'committed_assistant_message_not_token_level' };
      appendJournal(requestsPath, record);
      requests.push(record);
      const command = `printf '%s\\n' ${quote(`effect:${marker}`)} > ${quote(turn.target)} && cat ${quote(turn.target)}`;
      const chunk = (delta, finish_reason = null) => ({ id: `chatcmpl-${marker}`, object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000), model: body.model, choices: [{ index: 0, delta, finish_reason }] });
      let chunks;
      if (turn.scenario === 'cancel' || turn.scenario === 'stream') chunks = Array.from({ length: 120 }, (_, index) => chunk({ content: `真实模型流片段${index} ` }));
      else if (turn.scenario === 'slow' && calls.length === 0) {
        chunks = [chunk({ role: 'assistant', content: '正在执行可取消的离线慢速任务。' }),
          chunk({ tool_calls: [{ index: 0, id: `${marker}-slow`, type: 'function', function: { name: 'bash', arguments: JSON.stringify({ command: 'sleep 20', description: 'Cancellable isolated slow task.' }) } }] }),
          chunk({}, 'tool_calls')];
      } else if (['text', 'slow', 'cancel'].includes(turn.scenario) || stage === 'final') chunks = ['离线', '真实 dsh ', `${turn.scenario} 完成。`].map((content) => chunk({ content }));
      else {
        const escalated = stage === 'escalate';
        const args = JSON.stringify({ command, description: 'Write the isolated probe target outside the workspace.', ...(escalated ? { sandbox_permissions: 'danger-full-access', justification: 'This isolated probe target is outside the workspace; explicit user approval is required.' } : {}) });
        chunks = [chunk({ role: 'assistant', content: escalated ? '沙箱写入失败，正在请求明确审批。' : '正在验证隔离工作区外的受控写入。' }),
          chunk({ tool_calls: [{ index: 0, id: `${marker}-${escalated ? 'escalate' : 'sandbox'}`, type: 'function', function: { name: 'bash', arguments: '' } }] })];
        for (let i = 0; i < args.length; i += 32) chunks.push(chunk({ tool_calls: [{ index: 0, function: { arguments: args.slice(i, i + 32) } }] }));
        chunks.push(chunk({}, 'tool_calls'));
      }
      const stream = { request_id: record.request_id, turn_id: marker, scenario: turn.scenario, stream: true,
        state: 'streaming', started_at: Date.now(), ended_at: null, chunks_planned: chunks.length, chunks_sent: 0,
        done_sent: false, abort_observed: false, termination_source: null };
      saveStream(stream);
      let stopDelay;
      let controllerRef;
      const finish = (state, source) => {
        if (stream.state !== 'streaming') return;
        stream.state = state;
        stream.termination_source = source;
        stream.ended_at = Date.now();
        stream.abort_observed = state === 'aborted';
        saveStream(stream);
        stopDelay?.();
        request.signal.removeEventListener('abort', onAbort);
        sockets.delete(controllerRef);
      };
      const onAbort = () => finish('aborted', 'request_signal');
      request.signal.addEventListener('abort', onAbort, { once: true });
      const streaming = new ReadableStream({
        async start(controller) {
          controllerRef = controller;
          sockets.set(controller, () => finish('host_closed', 'backend_shutdown'));
          try {
            if (request.signal.aborted) onAbort();
            for (const part of chunks) {
              if (stream.state !== 'streaming') break;
              controller.enqueue(new TextEncoder().encode(`data: ${JSON.stringify(part)}\n\n`));
              stream.chunks_sent++;
              saveStream(stream);
              await new Promise((resolve) => {
                const timer = setTimeout(resolve, ['slow', 'cancel', 'stream'].includes(turn.scenario) ? 150 : 8);
                stopDelay = () => { clearTimeout(timer); resolve(); };
              });
              stopDelay = null;
            }
            if (stream.state === 'streaming') {
              controller.enqueue(new TextEncoder().encode(`data: ${JSON.stringify(chunk({}, 'stop'))}\n\ndata: [DONE]\n\n`));
              stream.done_sent = true;
              controller.close();
              finish('completed', 'sse_done');
            }
          } catch (error) {
            finish(request.signal.aborted ? 'aborted' : 'write_error', request.signal.aborted ? 'request_signal' : 'response_write');
          }
        },
        cancel() { finish('aborted', 'response_cancel'); },
      });
      return new Response(streaming, { headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' } });
    },
  });
  return {
    url: `http://127.0.0.1:${server.port}/v1`, requests, turns, streams, recoveryIssues: [...journal.issues, ...streamJournal.issues],
    register(turnId, text) {
      if (journal.issues.length || streamJournal.issues.length) throw new Error('Fake request journal recovery is read-only');
      const scenario = /\bslow\b|慢速/i.test(text) ? 'slow' : /\bcancel\b|取消流式/i.test(text) ? 'cancel' : /\breject\b|拒绝/i.test(text) ? 'reject' : /\ballow\b|允许|审批/i.test(text) ? 'allow' : /\bstream\b|流式/i.test(text) ? 'stream' : 'text';
      const dir = join(runDir, 'effects', turnId);
      mkdirSync(dir, { recursive: true, mode: 0o700 });
      const turn = { turn_id: turnId, scenario, target: join(dir, 'result.txt') };
      turns.set(turnId, turn);
      writeFileSync(targetsPath, JSON.stringify([...turns]), { mode: 0o600 });
      return turn;
    },
    close() {
      for (const [controller, stop] of sockets) { stop(); try { controller.close(); } catch {} }
      sockets.clear();
      server.stop(true);
    },
  };
}

export function fakeOverlay(baseURL) {
  return `- id: llm-pi-ai\n  config:\n    providers:\n      fake:\n        displayName: Fake Local\n        apiKeyEnv: FAKE_KEY\n        api: openai-completions\n        baseURL: ${baseURL}\n        models:\n          - id: fake-1\n            name: Fake One\n            contextWindow: 32768\n          - id: fake-2\n            name: Fake Two\n            contextWindow: 32768\n- id: acp\n  config:\n    provider: fake\n    model: fake-1\n`;
}
