// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runBrowserChecks } from './browser-check.mjs';

const upstreamCommit = '6744099b279b991c17e31c243f0920477bd31cb6';
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');

export function parseArgs(args, env = process.env) {
  const values = {};
  const accepted = new Set(['aionui-root', 'output', 'renderer-dir', 'build-manifest', 'dsh-bin', 'chrome-bin']);
  for (let i = 0; i < args.length; i++) {
    const key = args[i].replace(/^--/, '');
    if (!args[i].startsWith('--') || !accepted.has(key) || !args[i + 1] || args[i + 1].startsWith('--')) {
      throw new Error(`Unknown or incomplete argument: ${args[i]}`);
    }
    if (values[key]) throw new Error(`Duplicate argument: --${key}`);
    values[key] = args[++i];
  }
  const root = values['aionui-root'] || env.AIONUI_ROOT;
  const dshBin = values['dsh-bin'] || env.DSH_BIN;
  const chromeBin = values['chrome-bin'] || env.CHROME_BIN;
  if (!root || !dshBin || !chromeBin || !values.output) {
    throw new Error('Required: --aionui-root (or AIONUI_ROOT), --output NEW_EMPTY_DIR, DSH_BIN and CHROME_BIN');
  }
  return {
    aionuiRoot: path.resolve(root), output: path.resolve(values.output), dshBin: path.resolve(dshBin),
    chromeBin: path.resolve(chromeBin), rendererDir: path.resolve(values['renderer-dir'] || path.join(root, 'out/renderer')),
    buildManifest: path.resolve(values['build-manifest'] || path.join(values['renderer-dir'] || path.join(root, 'out/renderer'), 'renderer-build.json')),
  };
}

export async function claimOutput(output) {
  try {
    const stat = await fs.lstat(output);
    if (!stat.isDirectory() || stat.isSymbolicLink() || (await fs.readdir(output)).length) {
      throw new Error('--output must be a new or empty real directory; existing evidence is never overwritten');
    }
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    await fs.mkdir(output, { recursive: true, mode: 0o700 });
  }
  await fs.writeFile(path.join(output, '.run-owner'), `${process.pid}\n`, { flag: 'wx', mode: 0o600 });
}

export async function verifyBuild({ rendererDir, buildManifest }) {
  const bytes = await fs.readFile(buildManifest);
  const identity = JSON.parse(bytes);
  assert.equal(identity.schemaVersion, 1, 'Missing fresh renderer build identity; old out/ is refused');
  assert.equal(identity.upstream?.commit, upstreamCommit, 'Build is not the fixed AionUi upstream');
  const { inputFiles, rendererRoot, sha256 } = await import('../scripts/common.mjs');
  const { verifyOutput } = await import('../scripts/build.mjs');
  const pin = JSON.parse(await fs.readFile(path.join(rendererRoot, 'upstream.json'), 'utf8'));
  assert.equal(identity.upstream.packageSha256, pin.packageSha256, 'Original upstream package mismatch');
  assert.equal(identity.upstream.lockSha256, pin.lockSha256, 'Original upstream lock mismatch');
  for (const kind of ['patches', 'overlay']) {
    const current = await inputFiles(path.join(rendererRoot, kind));
    assert.ok(current.length, `Missing current ${kind}`);
    assert.deepEqual(identity[kind], current, `Renderer is stale: ${kind} differ from this implementation`);
  }
  assert.equal(identity.assetPolicySha256, sha256(await fs.readFile(path.join(rendererRoot, 'assets/policy.json'))), 'Stale asset admission policy');
  const required = ['lint', 'format', 'type', 'i18n-types', 'i18n', 'unit', 'package'];
  assert.ok(required.every((label) => identity.checks?.filter((check) => check.label === label).length === 1 && identity.checks.find((check) => check.label === label).exitCode === 0), 'Renderer standard checks have not all passed');
  assert.equal(identity.nodeEngineCompatible, true, 'Build Node version is outside the upstream >=22 <25 engine range');
  assert.match(identity.tools?.node ?? '', /^v2[234]\./, 'A compatible Node 22/23/24 build is required');
  assert.deepEqual(identity.tools, pin.tools, 'Released renderer does not match the current pinned Node/Bun versions');
  assert.deepEqual(identity.buildScripts, await inputFiles(path.join(rendererRoot, 'scripts')), 'Renderer is stale: build scripts changed');
  const checked = await verifyOutput(rendererDir);
  assert.deepEqual(checked, identity, '--build-manifest does not identify the served output');
  return { ...identity, buildId: digest(bytes), contractSha256: digest(await fs.readFile(path.join(projectRoot, 'renderer/contracts/host-v1.json'))) };
}

export async function startProbeProxy(backend) {
  let expectedOrigin;
  let proxyPort;
  const requests = [];
  const sockets = new Set();
  const agent = new http.Agent({ keepAlive: false });
  const reject = (response, code) => { response.writeHead(403, { 'content-type': 'application/json' }); response.end(JSON.stringify({ success: false, code })); };
  const server = http.createServer((request, response) => {
    const suppliedOrigin = request.headers.origin;
    const referer = request.headers.referer;
    const site = request.headers['sec-fetch-site'];
    const record = { method: request.method, path: request.url, origin: suppliedOrigin ?? null, referer: referer ?? null, site: site ?? null, accepted: false };
    requests.push(record);
    if (request.headers.host !== `127.0.0.1:${proxyPort}` || !expectedOrigin) return reject(response, 'PROXY_HOST_DENIED');
    if (Object.keys(request.headers).some((key) => key.toLowerCase() === 'x-rmagent-probe-token')) return reject(response, 'CLIENT_TOKEN_DENIED');
    if (suppliedOrigin !== undefined && suppliedOrigin !== expectedOrigin) return reject(response, 'PROXY_ORIGIN_DENIED');
    if (site !== undefined && site !== 'same-origin') return reject(response, 'PROXY_FETCH_SITE_DENIED');
    if (referer !== undefined) {
      try { if (new URL(referer).origin !== expectedOrigin) return reject(response, 'PROXY_REFERER_DENIED'); }
      catch { return reject(response, 'PROXY_REFERER_DENIED'); }
    }
    if (suppliedOrigin === undefined && !(site === 'same-origin' && referer && new URL(referer).origin === expectedOrigin && ['GET', 'HEAD'].includes(request.method))) return reject(response, 'PROXY_SOURCE_REQUIRED');
    record.accepted = true;
    const emptyControl = request.method === 'POST' && /^\/api\/conversations\/conv-probe-1\/(runtime\/ensure|active-lease)$/.test(request.url) && !request.headers['transfer-encoding'] && !Number(request.headers['content-length'] ?? 0) && !request.headers['content-type'];
    const headers = { ...request.headers, ...backend.probeHeaders, host: `127.0.0.1:${backend.port}`, connection: 'close' };
    if (emptyControl) { headers['content-type'] = 'application/json'; headers['content-length'] = '2'; }
    const outgoing = http.request({ hostname: '127.0.0.1', port: backend.port, method: request.method, path: request.url, agent, headers }, (upstream) => {
      response.writeHead(upstream.statusCode, upstream.headers);
      upstream.pipe(response);
    });
    outgoing.on('socket', (socket) => { sockets.add(socket); socket.once('close', () => sockets.delete(socket)); });
    outgoing.on('error', () => { if (!response.headersSent) response.writeHead(502); response.end(); });
    if (emptyControl) { request.resume(); outgoing.end('{}'); } else request.pipe(outgoing);
  });
  server.on('connection', (socket) => { sockets.add(socket); socket.once('close', () => sockets.delete(socket)); });
  const wsSockets = new Set();
  const websocketServer = Bun.serve({ hostname: '127.0.0.1', port: 0,
    fetch(request, server) {
      const url = new URL(request.url);
      requests.push({ transport: 'ws', host: request.headers.get('host'), origin: request.headers.get('origin'), path: url.pathname });
      if (!expectedOrigin || request.headers.get('host') !== new URL(expectedOrigin).host || request.headers.get('origin') !== expectedOrigin || request.headers.has('x-rmagent-probe-token') || url.pathname !== '/ws') return new Response('Denied', { status: 403 });
      const upstream = new WebSocket(`${backend.url.replace('http:', 'ws:')}${url.pathname}${url.search}`, { headers: { Origin: expectedOrigin } });
      const data = { upstream, queue: [], socket: null };
      upstream.onopen = () => { for (const message of data.queue) upstream.send(message); data.queue = []; };
      upstream.onmessage = (event) => data.socket?.send(event.data);
      upstream.onclose = (event) => data.socket?.close(event.code === 1006 ? 1011 : event.code, event.reason);
      upstream.onerror = () => data.socket?.close(1011, 'upstream error');
      if (server.upgrade(request, { data })) return;
      upstream.close(); return new Response('Upgrade required', { status: 400 });
    }, websocket: {
      open(socket) { socket.data.socket = socket; wsSockets.add(socket); },
      message(socket, message) { if (socket.data.upstream.readyState === 1) socket.data.upstream.send(message); else socket.data.queue.push(message); },
      close(socket) { wsSockets.delete(socket); socket.data.upstream.close(); },
    },
  });
  const front = net.createServer((client) => {
    sockets.add(client); client.once('close', () => sockets.delete(client));
    client.once('data', (first) => {
      const target = /^GET \/ws(?:[ ?])/.test(first.toString()) ? websocketServer.port : server.address().port;
      const outgoing = net.connect(target, '127.0.0.1', () => { outgoing.write(first); client.pipe(outgoing); outgoing.pipe(client); });
      sockets.add(outgoing); outgoing.once('close', () => { sockets.delete(outgoing); client.destroy(); });
      client.once('close', () => outgoing.destroy()); outgoing.on('error', () => client.destroy()); client.on('error', () => outgoing.destroy());
    });
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  await new Promise((resolve, reject) => { front.once('error', reject); front.listen(0, '127.0.0.1', resolve); });
  proxyPort = front.address().port;
  return { port: proxyPort, requests, setOrigin(value) { expectedOrigin = value; }, async close() { agent.destroy(); for (const socket of wsSockets) socket.close(); websocketServer.stop(true); for (const socket of sockets) socket.destroy(); server.closeAllConnections?.(); await Promise.all([new Promise((resolve) => server.close(resolve)), new Promise((resolve) => front.close(resolve))]); } };
}

export async function run(options) {
  await claimOutput(options.output);
  const reportPath = path.join(options.output, 'run-report.json');
  const report = { ok: false, startedAt: new Date().toISOString(), options, assertions: [] };
  let backend;
  let webHost;
  let proxy;
  const hostSockets = new Set();
  const hostServers = new Set();
  const stop = async () => {
    for (const socket of hostSockets) socket.destroy();
    for (const server of hostServers) server.closeAllConnections?.();
    try { await proxy?.close(); } finally { try { await backend?.close(); } finally { await webHost?.stop(); } }
  };
  const interrupted = () => { void stop().finally(() => process.exit(130)); };
  process.once('SIGINT', interrupted);
  process.once('SIGTERM', interrupted);
  try {
    if (!globalThis.Bun) throw new Error('Run with Bun: backend requires Bun.serve; Node 22 can run --self-test, not the Bun backend');
    for (const key of ['aionuiRoot', 'rendererDir', 'buildManifest', 'dshBin', 'chromeBin', 'output']) options[key] = await fs.realpath(options[key]);
    for (const bin of [options.dshBin, options.chromeBin]) await fs.access(bin, 1);
    const nodePath = Bun.which('node');
    assert.ok(nodePath, 'Node executable must be explicitly available on PATH');
    const nodeVersion = execFileSync(nodePath, ['--version'], { encoding: 'utf8' }).trim();
    report.tools = { runner: 'bun', bun: Bun.version, bunExecutable: await fs.realpath(process.execPath), node: nodeVersion, nodeExecutable: await fs.realpath(nodePath), upstreamNodeEngines: '>=22 <25', nodeEngineCompatible: /^v2[234]\./.test(nodeVersion) };
    assert.ok(report.tools.nodeEngineCompatible, 'Put Node 22/23/24 on PATH before running the harness; Node 26 is unsupported by upstream');
    report.build = await verifyBuild(options);
    const { startBackend } = await import('./backend.mjs');
    backend = await startBackend({ runDir: path.join(options.output, 'backend'), dshBin: options.dshBin, port: 0 });
    assert.ok(Number.isInteger(backend.port) && backend.port > 0 && typeof backend.close === 'function', 'Invalid backend handle');
    const { startWebHost } = await import(pathToFileURL(path.join(options.aionuiRoot, 'packages/web-host/src/index.ts')).href);
    const dataDir = path.join(options.output, 'web-host');
    await fs.mkdir(dataDir, { recursive: true, mode: 0o700 });
    proxy = await startProbeProxy(backend);
    const listen = net.Server.prototype.listen;
    net.Server.prototype.listen = function (...args) {
      hostServers.add(this);
      this.on('connection', (socket) => { hostSockets.add(socket); socket.once('close', () => hostSockets.delete(socket)); });
      return listen.apply(this, args);
    };
    try {
      webHost = await startWebHost({
        app: { version: `0.0.0-g1d-${report.build.buildId}`, isPackaged: false, resourcesPath: options.aionuiRoot, userDataPath: dataDir },
        staticDir: options.rendererDir, port: 0, allowRemote: false, dataDir, logDir: path.join(dataDir, 'logs'),
        dirs: { cacheDir: dataDir, workDir: dataDir, logDir: path.join(dataDir, 'logs') },
        backend: { kind: 'useExistingBackend', port: proxy.port },
      });
    } finally { net.Server.prototype.listen = listen; }
    assert.equal(typeof backend.setAllowedOrigins, 'function', 'Backend must support exact WebHost origin registration');
    backend.setAllowedOrigins([new URL(webHost.localUrl).origin]);
    proxy.setOrigin(new URL(webHost.localUrl).origin);
    report.web = { url: webHost.localUrl, port: webHost.port, backendPort: backend.port, proxyPort: proxy.port, allowedOrigins: [new URL(webHost.localUrl).origin] };
    report.browser = await runBrowserChecks({ ...options, url: webHost.localUrl, backend, build: report.build });
    report.ok = report.browser.ok;
  } catch (error) {
    report.error = { message: error.message, stack: error.stack };
  } finally {
    try { await stop(); } catch (error) { report.cleanupError = error.message; report.ok = false; }
    process.removeListener('SIGINT', interrupted);
    process.removeListener('SIGTERM', interrupted);
    report.proxyRequests = proxy?.requests;
    report.finishedAt = new Date().toISOString();
    await fs.writeFile(reportPath, JSON.stringify(report, null, 2) + '\n');
  }
  console.log(JSON.stringify({ ok: report.ok, reportPath, error: report.error?.message, browser: report.browser?.summary }));
  return report.ok ? 0 : 1;
}

async function selfTest() {
  assert.throws(() => parseArgs([], {}), /Required/);
  assert.throws(() => parseArgs(['--output', 'x', '--output', 'y'], {}), /Duplicate/);
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'rmagentma-runner-test-'));
  try {
    const out = path.join(temp, 'output');
    await claimOutput(out);
    await assert.rejects(claimOutput(out), /new or empty/);
    await assert.rejects(verifyBuild({ rendererDir: out, buildManifest: path.join(out, 'missing.json') }), /ENOENT/);
    await fs.writeFile(path.join(out, 'identity.json'), '{}');
    await assert.rejects(verifyBuild({ rendererDir: out, buildManifest: path.join(out, 'identity.json') }), /fresh renderer build identity/);
    const { verifyOutput } = await import('../scripts/build.mjs');
    const assetDir = path.join(temp, 'assets');
    await fs.mkdir(path.join(assetDir, 'licenses'), { recursive: true });
    const records = [];
    for (const name of ['index.html', 'licenses/index.html', 'THIRD_PARTY_NOTICES.txt']) {
      await fs.writeFile(path.join(assetDir, name), 'synthetic self-test');
      records.push({ path: name, size: 19, sha256: digest('synthetic self-test') });
    }
    const assets = JSON.stringify({ schemaVersion: 1, algorithm: 'SHA-256', entry: 'index.html', files: records });
    await fs.writeFile(path.join(assetDir, 'asset-manifest.json'), assets);
    await fs.writeFile(path.join(assetDir, 'renderer-build.json'), JSON.stringify({ schemaVersion: 1, entry: 'index.html', nodeEngineCompatible: true, tools: { node: 'v22.23.3' }, assetManifestSha256: digest(assets) }));
    await verifyOutput(assetDir);
    await fs.writeFile(path.join(assetDir, 'index.html'), 'stale');
    await assert.rejects(verifyOutput(assetDir), /Asset mismatch/);
    console.log('runner self-test: arguments, output ownership, missing build identity and stale asset gates passed');
  } finally { await fs.rm(temp, { recursive: true, force: true }); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.slice(2).join(' ') === '--self-test') await selfTest();
    else process.exit(await run(parseArgs(process.argv.slice(2))));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
