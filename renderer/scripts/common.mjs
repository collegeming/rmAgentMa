import { createHash } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { lstat, mkdir, readdir, readFile, realpath, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const rendererRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
export const gitBlob = (bytes) => createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
export const readJson = async (file) => JSON.parse(await readFile(file, 'utf8'));
export const writeJson = async (file, data) => {
  await mkdir(path.dirname(file), { recursive: true });
  await writeFile(file, `${JSON.stringify(data, null, 2)}\n`);
};
export function args(argv, allowed) {
  const result = {};
  for (let index = 0; index < argv.length; index++) {
    const key = argv[index];
    if (
      !key.startsWith('--') ||
      !allowed.includes(key.slice(2)) ||
      !argv[index + 1] ||
      argv[index + 1].startsWith('--')
    ) {
      throw new Error(`Invalid option: ${key}. Expected ${allowed.map((item) => `--${item} VALUE`).join(' ')}`);
    }
    if (result[key.slice(2)] !== undefined) throw new Error(`Duplicate option ${key}`);
    result[key.slice(2)] = argv[++index];
  }
  return result;
}
export function git(cwd, ...argv) {
  return execFileSync('git', ['-C', cwd, ...argv], {
    env: {
      ...process.env,
      GIT_NO_LAZY_FETCH: '1',
      GIT_TERMINAL_PROMPT: '0',
      ...(argv[0] === 'apply' ? { GIT_CEILING_DIRECTORIES: path.dirname(cwd) } : {}),
    },
    maxBuffer: 32 * 1024 * 1024,
  });
}
export async function files(root, relative = '') {
  const output = [];
  for (const entry of (await readdir(path.join(root, relative), { withFileTypes: true })).sort((a, b) =>
    a.name.localeCompare(b.name, 'en')
  )) {
    const name = path.posix.join(relative, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Symlink not allowed: ${name}`);
    if (entry.isDirectory()) output.push(...(await files(root, name)));
    else if (entry.isFile()) output.push(name);
    else throw new Error(`Special file not allowed: ${name}`);
  }
  return output;
}
export function safeRelative(name) {
  if (
    !name ||
    name.includes('\\') ||
    name.includes('\0') ||
    path.posix.isAbsolute(name) ||
    name.split('/').some((part) => part === '..' || part === '.' || part === '')
  )
    throw new Error(`Unsafe path: ${name}`);
  return name;
}
export function sensitive(name) {
  return (
    /(^|\/)(?:\.env(?:\..*)?|id_(?:rsa|ed25519|ecdsa)(?:\..*)?|credentials(?:\..*)?|\.npmrc|\.bunfig\.toml|bunfig\.toml)$/i.test(
      name
    ) && !/\.env\.(?:example|sample|template)$/.test(name)
  );
}
export async function regularFile(root, name) {
  safeRelative(name);
  let current = root;
  for (const part of name.split('/')) {
    current = path.join(current, part);
    if ((await lstat(current)).isSymbolicLink()) throw new Error(`Symlink not allowed: ${name}`);
  }
  if (!(await lstat(current)).isFile()) throw new Error(`Missing regular file: ${name}`);
  return current;
}
export function assertNodeCompatible(version, pin) {
  const major = Number(version.match(/^v?(\d+)\./)?.[1]);
  if (!pin.nodeEngineCompatible || major < 22 || major >= 25 || !Number.isInteger(major))
    throw new Error(`Node ${version} does not satisfy upstream ${pin.upstreamNodeEngines}; release is forbidden`);
}
export async function tools(pin) {
  assertNodeCompatible(process.version, pin);
  const actual = {
    node: process.version,
    bun: execFileSync('bun', ['--version'], { encoding: 'utf8' }).trim(),
  };
  if (actual.node !== pin.tools.node || actual.bun !== pin.tools.bun)
    throw new Error(`Tool mismatch: expected ${JSON.stringify(pin.tools)}, got ${JSON.stringify(actual)}`);
  return actual;
}
export async function inputFiles(root) {
  const output = [];
  try {
    for (const name of await files(root)) {
      if (sensitive(name)) throw new Error(`Sensitive build input forbidden: ${name}`);
      output.push({ path: name, sha256: sha256(await readFile(path.join(root, name))) });
    }
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  return output;
}
export async function assertPrepared(workdir) {
  const metadata = await readJson(path.join(workdir, '.rmagent/prepare.json'));
  const pin = await readJson(path.join(rendererRoot, 'upstream.json'));
  await tools(pin);
  if (
    metadata.upstream.commit !== pin.commit ||
    sha256(await readFile(path.join(workdir, 'bun.lock'))) !== pin.lockSha256
  )
    throw new Error('Prepared input mismatch');
  if (JSON.stringify(await inputFiles(path.join(rendererRoot, 'scripts'))) !== JSON.stringify(metadata.buildScripts))
    throw new Error('Build scripts changed; prepare a new workspace');
  for (const kind of ['patches', 'overlay']) {
    const current = await inputFiles(path.join(rendererRoot, kind));
    if (JSON.stringify(current) !== JSON.stringify(metadata[kind]))
      throw new Error(`${kind} changed; prepare a new workspace`);
  }
  for (const record of metadata.preparedFiles) {
    if (sha256(await readFile(await regularFile(workdir, record.path))) !== record.sha256)
      throw new Error(`Prepared source changed: ${record.path}`);
  }
  for (const component of metadata.dependencies?.instances ?? []) {
    if (sha256(await readFile(path.join(component.instance, 'package.json'))) !== component.manifestSha256)
      throw new Error(`Dependency cache manifest changed: ${component.name}@${component.version}`);
  }
  if ((await realpath(workdir)) !== metadata.workdir) throw new Error('Workspace moved; prepare again');
  return { metadata, pin };
}
export function run(command, argv, cwd, env = process.env) {
  const result = spawnSync(command, argv, { cwd, env, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} ${argv.join(' ')} failed (${result.status ?? result.signal})`);
}
export function isolatedEnv(workdir) {
  const env = {};
  for (const key of ['PATH', 'SystemRoot', 'WINDIR', 'PATHEXT', 'LANG', 'LC_ALL', 'TZ'])
    if (process.env[key]) env[key] = process.env[key];
  return {
    ...env,
    HOME: path.join(workdir, '.rmagent/home'),
    XDG_CONFIG_HOME: path.join(workdir, '.rmagent/home/config'),
    XDG_CACHE_HOME: path.join(workdir, '.rmagent/home/cache'),
    TMPDIR: path.join(workdir, '.rmagent/tmp'),
    CI: 'true',
    DO_NOT_TRACK: '1',
    SENTRY_DSN: '',
    SENTRY_AUTH_TOKEN: '',
    SENTRY_UPLOAD_SOURCE_MAPS: 'false',
  };
}
