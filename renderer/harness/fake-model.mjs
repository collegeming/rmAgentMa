// SPDX-License-Identifier: Apache-2.0
import { mkdirSync, readFileSync, existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { readJournal, appendJournal } from './session-state.mjs';

export const LOCAL_MODELS = ['fake-1', 'fake-2'];
export const LOCAL_VALUES = LOCAL_MODELS.map((model) => JSON.stringify(['fake', model]));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const quote = (text) => `'${text.replaceAll("'", "'\\''")}'`;

export function startFakeModel({ runDir }) {
  const targetsPath = join(runDir, 'fake-targets.json');
  const turns = new Map(existsSync(targetsPath) ? JSON.parse(readFileSync(targetsPath, 'utf8')) : []);
  const requestsPath = join(runDir, 'fake-requests.jsonl');
  const journal = readJournal(requestsPath);
  const requests = journal.records;
  const sockets = new Set();
  const server = Bun.serve({
    hostname: '127.0.0.1', port: 0,
    async fetch(request) {
      const path = new URL(request.url).pathname;
      if (request.method === 'GET' && path === '/v1/models') return Response.json({ object: 'list', data: LOCAL_MODELS.map((id) => ({ id, object: 'model' })) });
      if (request.method !== 'POST' || path !== '/v1/chat/completions') return Response.json({ error: 'Unknown fake-model route' }, { status: 404 });
      if (journal.issues.length) return Response.json({ error: 'Fake request journal recovery is read-only' }, { status: 409 });
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
      const record = { model: body.model, turn_id: marker, scenario: turn.scenario, stage, messages: messages.length };
      appendJournal(requestsPath, record);
      requests.push(record);
      const command = `printf '%s\\n' ${quote(`effect:${marker}`)} > ${quote(turn.target)} && cat ${quote(turn.target)}`;
      const chunk = (delta, finish_reason = null) => ({ id: `chatcmpl-${marker}`, object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000), model: body.model, choices: [{ index: 0, delta, finish_reason }] });
      let chunks;
      if (turn.scenario === 'cancel') chunks = Array.from({ length: 120 }, (_, index) => chunk({ content: `真实模型流片段${index} ` }));
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
      const streaming = new ReadableStream({
        async start(controller) {
          sockets.add(controller);
          try {
            for (const part of chunks) {
              if (request.signal.aborted) break;
              controller.enqueue(new TextEncoder().encode(`data: ${JSON.stringify(part)}\n\n`));
              await sleep(turn.scenario === 'slow' || turn.scenario === 'cancel' ? 150 : 8);
            }
            if (!request.signal.aborted) {
              controller.enqueue(new TextEncoder().encode(`data: ${JSON.stringify(chunk({}, 'stop'))}\n\ndata: [DONE]\n\n`));
              controller.close();
            }
          } catch {} finally { sockets.delete(controller); }
        },
      });
      return new Response(streaming, { headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' } });
    },
  });
  return {
    url: `http://127.0.0.1:${server.port}/v1`, requests, turns, recoveryIssues: journal.issues,
    register(turnId, text) {
      if (journal.issues.length) throw new Error('Fake request journal recovery is read-only');
      const scenario = /\bslow\b|慢速/i.test(text) ? 'slow' : /\bcancel\b|取消流式/i.test(text) ? 'cancel' : /\breject\b|拒绝/i.test(text) ? 'reject' : /\ballow\b|允许|审批/i.test(text) ? 'allow' : 'text';
      const dir = join(runDir, 'effects', turnId);
      mkdirSync(dir, { recursive: true, mode: 0o700 });
      const turn = { turn_id: turnId, scenario, target: join(dir, 'result.txt') };
      turns.set(turnId, turn);
      writeFileSync(targetsPath, JSON.stringify([...turns]), { mode: 0o600 });
      return turn;
    },
    close() {
      for (const controller of sockets) { try { controller.close(); } catch {} }
      sockets.clear();
      server.stop(true);
    },
  };
}

export function fakeOverlay(baseURL) {
  return `- id: llm-pi-ai\n  config:\n    providers:\n      fake:\n        displayName: Fake Local\n        apiKeyEnv: FAKE_KEY\n        api: openai-completions\n        baseURL: ${baseURL}\n        models:\n          - id: fake-1\n            name: Fake One\n            contextWindow: 32768\n          - id: fake-2\n            name: Fake Two\n            contextWindow: 32768\n- id: acp\n  config:\n    provider: fake\n    model: fake-1\n`;
}
