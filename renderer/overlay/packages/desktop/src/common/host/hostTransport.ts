/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */

type HostReply = { requestId: string; documentId: string; ok: boolean; status: number; body: unknown };
type HostEvent = {
  type: 'event';
  documentId: string;
  name: string;
  data: unknown;
  epoch: number | string;
  seq: number;
};
type HostPayload = { params: Record<string, string>; query: Record<string, string>; body: unknown };
type HostBridge = { postMessage: (message: string) => void; onmessage?: (event: MessageEvent<string>) => void };
type Listener = (name: string, data: unknown) => void;

declare global {
  interface Window {
    RmAgentHost?: HostBridge;
  }
}

const ORIGIN = 'https://appassets.androidplatform.net';
const ROUTES = [
  ['GET', '/api/auth/user', 'auth.read'],
  ['GET', '/api/settings/client', 'settings.client.read'],
  ['PUT', '/api/settings/client', 'settings.client.write'],
  ['GET', '/api/settings', 'settings.read'],
  ['GET', '/api/conversations', 'conversations.list'],
  ['POST', '/api/conversations', 'conversations.create'],
  ['GET', '/api/conversations/:conversationId', 'conversation.read'],
  ['GET', '/api/conversations/:conversationId/messages', 'messages.list'],
  ['GET', '/api/conversations/:conversationId/messages/latest', 'messages.latest'],
  ['POST', '/api/conversations/:conversationId/runtime/ensure', 'runtime.ensure'],
  ['GET', '/api/conversations/:conversationId/config-options', 'config.read'],
  ['PUT', '/api/conversations/:conversationId/config-options/:optionId', 'config.set'],
  ['POST', '/api/conversations/:conversationId/messages', 'messages.send'],
  ['POST', '/api/conversations/:conversationId/cancel', 'turn.cancel'],
  ['GET', '/api/conversations/:conversationId/confirmations', 'approvals.list'],
  ['POST', '/api/conversations/:conversationId/confirmations/:requestRef/confirm', 'approvals.review'],
  ['POST', '/api/conversations/:conversationId/active-lease', 'runtime.visible'],
  ['GET', '/api/conversations/:conversationId/slash-commands', 'commands.list'],
  ['GET', '/api/conversations/:conversationId/usage', 'usage.read'],
  ['GET', '/api/conversations/:conversationId/artifacts', 'artifacts.list'],
  // Escape hatches: the host opens the terminal, and builds the Agent's own TUI command itself.
  ['POST', '/api/conversations/:conversationId/terminal', 'terminal.open'],
  ['POST', '/api/conversations/:conversationId/terminal/command', 'terminal.command.start'],
  ['GET', '/api/projects', 'projects.list'],
  ['GET', '/api/projects/:projectId', 'project.read'],
  ['GET', '/api/hosts', 'hosts.list'],
  ['POST', '/api/hosts', 'hosts.create'],
  ['PUT', '/api/hosts/:hostId', 'hosts.update'],
  ['DELETE', '/api/hosts/:hostId', 'hosts.delete'],
  ['POST', '/api/hosts/:hostId/detection/refresh', 'detection.refresh'],
  ['POST', '/api/hosts/:hostId/terminal', 'terminal.open'],
  ['POST', '/api/hosts/:hostId/terminal/command', 'terminal.command.start'],
  ['GET', '/api/assistants', 'assistants.list'],
  ['GET', '/api/agents/management', 'agents.list'],
  ['GET', '/api/providers', 'providers.list'],
  ['GET', '/api/mcp/servers', 'mcp.list'],
  ['GET', '/api/skills', 'skills.list'],
  ['GET', '/api/cron/jobs', 'cron.list'],
  ['GET', '/api/teams', 'teams.list'],
  ['GET', '/api/extensions/acp-adapters', 'adapters.list'],
] as const;

export function isNativeHost(): boolean {
  return typeof window !== 'undefined' && window.location?.origin === ORIGIN;
}

export function resolveHostRoute(method: string, path: string): { action: string; payload: HostPayload } {
  const url = new URL(path, ORIGIN);
  if (url.origin !== ORIGIN) throw new Error('HOST_ORIGIN_DENIED');
  for (const [verb, pattern, action] of ROUTES) {
    if (verb !== method.toUpperCase()) continue;
    const actual = url.pathname.split('/');
    const expected = pattern.split('/');
    if (actual.length !== expected.length) continue;
    const params: Record<string, string> = {};
    if (
      !expected.every((part, i) => {
        if (!part.startsWith(':')) return part === actual[i];
        if (!actual[i]) return false;
        params[part.slice(1)] = decodeURIComponent(actual[i]);
        return true;
      })
    )
      continue;
    return { action, payload: { params, query: Object.fromEntries(url.searchParams), body: null } };
  }
  throw new Error(`HOST_ACTION_UNSUPPORTED: ${method} ${url.pathname}`);
}

export class NativeTransport {
  private documentId = '';
  private serial = 0;
  private epoch: number | string | undefined;
  private retiredEpochs = new Set<number | string>();
  private seq = -1;
  private listeners = new Set<Listener>();
  private pending = new Map<
    string,
    { resolve: (reply: HostReply) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }
  >();

  constructor(
    private readonly bridge: HostBridge,
    private readonly timeoutMs = 15000
  ) {
    bridge.onmessage = (event) => this.receive(event.data);
  }

  async initialize(): Promise<void> {
    const reply = await this.call('host.handshake');
    const result = reply.body as { documentId?: string; protocolVersion?: number };
    if (
      !reply.ok ||
      reply.status !== 200 ||
      result?.protocolVersion !== 1 ||
      !result.documentId ||
      reply.documentId !== result.documentId
    ) {
      throw new Error('HOST_HANDSHAKE_INVALID');
    }
    this.documentId = result.documentId;
  }

  call(action: string, payload: HostPayload = { params: {}, query: {}, body: null }): Promise<HostReply> {
    if (!this.documentId && action !== 'host.handshake') return Promise.reject(new Error('HOST_NOT_READY'));
    if (action !== 'host.handshake' && !ROUTES.some((route) => route[2] === action) && !EXTRA_ACTIONS.has(action))
      return Promise.reject(new Error('HOST_ACTION_UNSUPPORTED'));
    const requestId = `renderer-${++this.serial}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        reject(new Error(`HOST_TIMEOUT: ${action}; result unknown`));
      }, this.timeoutMs);
      this.pending.set(requestId, { resolve, reject, timer });
      try {
        const message = JSON.stringify({ requestId, documentId: this.documentId, action, payload });
        if (new TextEncoder().encode(message).byteLength > 100 * 1024) throw new Error('HOST_MESSAGE_TOO_LARGE');
        this.bridge.postMessage(message);
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(requestId);
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
  }

  on(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  close(): void {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(new Error('HOST_DOCUMENT_CLOSED'));
    }
    this.pending.clear();
    this.listeners.clear();
    this.bridge.onmessage = undefined;
    this.documentId = '';
  }

  private receive(raw: string): void {
    let message: HostReply | HostEvent;
    try {
      message = JSON.parse(raw) as HostReply | HostEvent;
    } catch {
      return;
    }
    if (!message || typeof message !== 'object') return;
    if ('type' in message && message.type === 'event') {
      if (
        !this.documentId ||
        message.documentId !== this.documentId ||
        typeof message.name !== 'string' ||
        !(Number.isSafeInteger(message.epoch) || (typeof message.epoch === 'string' && message.epoch.length > 0)) ||
        !Number.isSafeInteger(message.seq) ||
        message.seq < 0
      )
        return;
      if (this.retiredEpochs.has(message.epoch) || (message.epoch === this.epoch && message.seq <= this.seq)) return;
      if (typeof message.epoch === 'number' && typeof this.epoch === 'number' && message.epoch < this.epoch) return;
      if (this.epoch !== undefined && message.epoch !== this.epoch) this.retiredEpochs.add(this.epoch);
      this.epoch = message.epoch;
      this.seq = message.seq;
      for (const listener of this.listeners) {
        try {
          listener(message.name, message.data);
        } catch (error) {
          console.warn('[host] event consumer failed', error);
        }
      }
      return;
    }
    if (!('requestId' in message) || typeof message.status !== 'number' || typeof message.ok !== 'boolean') return;
    const pending = this.pending.get(message.requestId);
    if (!pending) return;
    if (this.documentId && message.documentId !== this.documentId) return;
    clearTimeout(pending.timer);
    this.pending.delete(message.requestId);
    pending.resolve(message);
  }
}

let nativeTransport: NativeTransport | undefined;
let hostGeneration = 0;

export function captureHostDocument(): () => void {
  const generation = hostGeneration;
  return () => {
    if (generation !== hostGeneration) throw new Error('HOST_DOCUMENT_CLOSED');
  };
}

export async function initializeHost(): Promise<void> {
  if (!isNativeHost()) return;
  if (!window.RmAgentHost) throw new Error('HOST_BRIDGE_UNAVAILABLE');
  const transport = new NativeTransport(window.RmAgentHost);
  await transport.initialize();
  nativeTransport = transport;
  hostGeneration++;
  window.addEventListener(
    'pagehide',
    () => {
      hostGeneration++;
      transport.close();
    },
    { once: true }
  );
  const reply = await transport.call('events.subscribe');
  if (!reply.ok) throw new Error('HOST_EVENTS_UNAVAILABLE');
}

/**
 * The five saved-host actions this build declares, each with its route in ROUTES: `hosts.list`, `hosts.create`,
 * `hosts.update`, `hosts.delete` and `detection.refresh`. Structured fields travel in the request body, because
 * the bridge carries only string parameters.
 */
/** Operating system a saved host holds. */
export const HOST_OS_LINUX = 'linux';
export const HOST_OS_WINDOWS = 'windows';

/** Agent target of a saved host: the host itself, or one WSL distribution of a Windows host. */
export const AGENT_TARGET_NATIVE = 'native';
export const AGENT_TARGET_WSL_PREFIX = 'wsl:';

/** The probe verdicts the host itself derives for one Agent; the renderer adds only a not-probed reading. */
export const HOST_PROBE_STATES = [
  'available',
  'not_installed',
  'start_failed',
  'no_protocol_capability',
  'connect_failed',
  'target_unsupported',
  'unknown_failure',
] as const;

/** One Agent's verdict on one host, with the absolute executable and version the probe verified. */
export type HostAgentProbe = {
  id: string;
  /** One of HOST_PROBE_STATES; an unrecognized value is read as a probe failure, never as a name. */
  state: string;
  executable: string;
  version: string;
  capabilities: Record<string, unknown> | null;
  error: string;
};

/** What the product holds for one host: `checked` is false until some probe answered for it. */
export type HostDetection = { checked: boolean; agents: HostAgentProbe[] };

/** One saved SSH host, as `hosts.list`, `hosts.create` and `hosts.update` answer it. */
export type SavedHost = {
  /** The opaque `host-<id>` identity a project row carries too. */
  id: string;
  nickname: string;
  hostname: string;
  port: number;
  username: string;
  protocol: string;
  os_type: string;
  agent_target: string;
  /** The shell context the Agent would run in: `posix`, `wsl:<distribution>` or `windows-native`. */
  target: string;
  target_supported: boolean;
  detection: HostDetection;
};

/** The fields one create or update body carries; an update rewrites only the fields it names. */
export type HostFields = {
  nickname: string;
  hostname: string;
  port: number;
  username: string;
  os_type: string;
  agent_target: string;
};

/** What `detection.refresh` answers: the target that was probed and the verdicts it produced. */
export type HostDetectionRefresh = {
  host_id: string;
  target: string;
  target_supported: boolean;
  detection: HostDetection;
};

/** What `hosts.delete` answers once the host is gone. */
export type HostDeletion = { deleted: boolean; host_id: string };

const EXTRA_ACTIONS = new Set([
  'events.subscribe',
  'events.unsubscribe',
  'runtime.snapshot',
  'draft.load',
  'draft.save',
  'attachment.begin',
  'attachment.chunk',
  'attachment.finish',
  'attachment.abort',
]);

export async function hostAction<T>(action: string, conversationId: string, body: unknown = null): Promise<T> {
  if (!nativeTransport) throw new Error('HOST_NOT_READY');
  if (!EXTRA_ACTIONS.has(action)) throw new Error('HOST_ACTION_UNSUPPORTED');
  const reply = await nativeTransport.call(action, { params: { conversationId }, query: {}, body });
  if (!reply.ok || reply.status < 200 || reply.status >= 300)
    throw new Error(`HOST_ACTION_FAILED: ${action} (${reply.status})`);
  const result = reply.body;
  if (result && typeof result === 'object' && 'data' in result) return result.data as T;
  return result as T;
}

export function onHostEvent(listener: Listener): () => void {
  if (!nativeTransport) throw new Error('HOST_NOT_READY');
  return nativeTransport.on(listener);
}

export async function hostFetch(input: string, init: RequestInit = {}): Promise<Response> {
  if (!isNativeHost()) return fetch(input, init);
  if (!nativeTransport) throw new Error('HOST_NOT_READY');
  if (init.signal?.aborted) throw new DOMException('Aborted', 'AbortError');
  const { action, payload } = resolveHostRoute(init.method ?? 'GET', input);
  if (init.body !== undefined && init.body !== null) {
    if (typeof init.body !== 'string') throw new Error('HOST_JSON_BODY_REQUIRED');
    payload.body = JSON.parse(init.body) as unknown;
  }
  const reply = await nativeTransport.call(action, payload);
  return new Response(reply.status === 204 ? null : JSON.stringify(reply.body), {
    status: reply.status,
    headers: { 'Content-Type': 'application/json' },
  });
}

export async function hostPlatformRequest(name: string, _data?: unknown): Promise<unknown> {
  switch (name) {
    case 'app.get-zoom-factor':
      return 1;
    case 'auto-update.restore-downloaded':
    case 'update.installer-last-failure.consume':
      return null;
    case 'webui.get-status':
      return { running: false };
    case 'app.write-renderer-log':
      return undefined;
    default:
      throw new Error(`HOST_SERVICE_UNSUPPORTED: ${name}`);
  }
}
