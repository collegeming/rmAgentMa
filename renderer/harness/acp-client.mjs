// SPDX-License-Identifier: Apache-2.0
import { spawn } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { EventEmitter } from 'node:events';
import { StringDecoder } from 'node:string_decoder';

export class AcpClient extends EventEmitter {
  constructor({ dshBin, cwd, env, patch, framesPath }) {
    super();
    this.pending = new Map();
    this.nextId = 1;
    this.closed = false;
    this.framesPath = framesPath;
    this.child = spawn(dshBin, ['acp', '--patch', patch], { cwd, env, stdio: ['pipe', 'pipe', 'pipe'] });
    this.child.stdin.on('error', (error) => this.fail(error));
    this.child.on('error', (error) => this.fail(error));
    this.child.on('exit', (code, signal) => this.fail(new Error(`dsh exited (${code ?? signal})`)));
    this.child.stderr.on('data', (data) => this.log('stderr', { text: data.toString() }));
    let buffer = '';
    const decoder = new StringDecoder('utf8');
    this.child.stdout.on('end', () => {
      const remainder = decoder.end();
      if (remainder || buffer.trim()) this.fail(new Error('ACP stdout ended with an incomplete frame'));
    });
    this.child.stdout.on('data', (data) => {
      buffer += decoder.write(data);
      if (buffer.length > 8 * 1024 * 1024) return this.fail(new Error('ACP frame size exceeded'));
      let newline;
      while ((newline = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, newline).trim();
        buffer = buffer.slice(newline + 1);
        if (!line) continue;
        let frame;
        try { frame = JSON.parse(line); } catch { this.log('invalid', { line }); continue; }
        this.log('in', frame);
        if (frame.id !== undefined && ('result' in frame || 'error' in frame)) {
          const pending = this.pending.get(frame.id);
          if (!pending) continue;
          this.pending.delete(frame.id);
          clearTimeout(pending.timer);
          if (frame.error) pending.reject(Object.assign(new Error(frame.error.message), { rpcError: frame.error }));
          else pending.resolve(frame.result);
        } else if (frame.method && frame.id !== undefined) this.emit('request', frame);
        else if (frame.method) this.emit('notification', frame);
      }
    });
  }

  log(direction, frame) {
    appendFileSync(this.framesPath, JSON.stringify({ at: Date.now(), direction, ...frame }) + '\n', { mode: 0o600 });
  }

  write(frame) {
    if (this.closed) throw new Error('ACP connection closed');
    this.log('out', frame);
    this.child.stdin.write(JSON.stringify({ jsonrpc: '2.0', ...frame }) + '\n');
  }

  request(method, params, timeoutMs = 30000) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(Object.assign(new Error(`ACP timeout: ${method}; result unknown`), { code: 'RPC_TIMEOUT' }));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      try { this.write({ id, method, params }); } catch (error) {
        this.pending.delete(id);
        clearTimeout(timer);
        reject(error);
      }
    });
  }

  notify(method, params) { this.write({ method, params }); }
  reply(id, result) { this.write({ id, result }); }
  reject(id, message) { this.write({ id, error: { code: -32601, message } }); }

  fail(error) {
    if (this.closed) return;
    this.closed = true;
    for (const { reject, timer } of this.pending.values()) { clearTimeout(timer); reject(error); }
    this.pending.clear();
    this.emit('disconnect', error);
  }

  async close() {
    if (!this.child.pid || this.child.exitCode !== null || this.child.signalCode !== null) return;
    const exited = new Promise((resolve) => this.child.once('exit', resolve));
    this.fail(new Error('ACP client closed'));
    this.child.stdin.end();
    this.child.kill('SIGTERM');
    const timer = setTimeout(() => this.child.kill('SIGKILL'), 2000);
    await exited;
    clearTimeout(timer);
  }
}

export function toConfigOptions(options) {
  return options.map((option) => ({
    id: option.id, name: option.name, category: option.category, type: option.type,
    current_value: option.currentValue,
    options: (option.options ?? []).flatMap((item) => item.options ?? [item]).map(({ value, name, description }) => ({ value, name, ...(description ? { description } : {}) })),
  }));
}
