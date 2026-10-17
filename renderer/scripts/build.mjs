import { copyFile, mkdir, readFile, rm, stat } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { args, assertPrepared, files, isolatedEnv, readJson, rendererRoot, sha256, writeJson } from './common.mjs';
import { auditAssets, emitLicenses } from './check-assets.mjs';

async function execute(workdir, command, argv, label, env) {
  const { createWriteStream } = await import('node:fs');
  const log = path.join(workdir, '.rmagent/logs', `${label}.log`);
  return new Promise((resolve, reject) => {
    const stream = createWriteStream(log);
    const child = spawn(command, argv, { cwd: workdir, env, stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', (chunk) => {
      stream.write(chunk);
      process.stdout.write(chunk);
    });
    child.stderr.on('data', (chunk) => {
      stream.write(chunk);
      process.stderr.write(chunk);
    });
    child.on('error', (error) => {
      stream.end();
      reject(error);
    });
    child.on('close', (code, signal) => {
      stream.end(() => resolve({ command: [command, ...argv], label, exitCode: code, signal, log }));
    });
  });
}
export async function build(options) {
  if (!options.workdir || !options.output)
    throw new Error('--workdir and --output are required; output must not exist');
  const workdir = path.resolve(options.workdir);
  const output = path.resolve(options.output);
  if (output === workdir || workdir.startsWith(`${output}${path.sep}`)) throw new Error('Invalid output location');
  try {
    await stat(output);
    throw new Error('Output already exists; use a new directory');
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  const { metadata, pin } = await assertPrepared(workdir);
  if (!pin.nodeEngineCompatible) throw new Error('Incompatible Node cannot release renderer');
  if (!metadata.dependencies || (!metadata.nodeModulesCache && !metadata.installation))
    throw new Error('Dependencies not verified; run explicit install.mjs or prepare with --node-modules');
  const env = isolatedEnv(workdir);
  const commands = [
    ['bun', ['run', 'lint'], 'lint'],
    ['bun', ['run', 'format:check'], 'format'],
    [process.execPath, ['node_modules/typescript/bin/tsc', '--noEmit'], 'type'],
    ['bun', ['run', 'i18n:types'], 'i18n-types'],
    [process.execPath, ['scripts/check-i18n.js'], 'i18n'],
    ['bun', ['run', 'test', '--project', 'node', '--project', 'dom'], 'unit'],
    ['bun', ['run', 'package'], 'package'],
  ];
  const results = [];
  await rm(path.join(workdir, 'out'), { recursive: true, force: true });
  for (const [command, argv, label] of commands) {
    const result = await execute(workdir, command, argv, label, env);
    results.push(result);
    await writeJson(path.join(workdir, '.rmagent/check-results.json'), {
      schemaVersion: 1,
      results,
    });
  }
  if (results.some((result) => result.exitCode !== 0))
    throw new Error(
      `Standard checks failed: ${results
        .filter((result) => result.exitCode !== 0)
        .map((result) => result.label)
        .join(', ')}. See .rmagent/check-results.json; no renderer is released.`
    );
  const original = path.join(workdir, pin.rendererOutput);
  const audit = await auditAssets({ dir: original, workdir });
  await mkdir(output, { recursive: false });
  try {
    for (const name of audit.files) {
      await mkdir(path.dirname(path.join(output, name)), { recursive: true });
      await copyFile(path.join(original, name), path.join(output, name));
    }
    await emitLicenses(output, audit, workdir, metadata);
    const records = [];
    for (const name of await files(output)) {
      const bytes = await readFile(path.join(output, name));
      records.push({ path: name, size: bytes.length, sha256: sha256(bytes) });
    }
    const manifest = {
      schemaVersion: 1,
      algorithm: 'SHA-256',
      entry: 'index.html',
      files: records,
    };
    await writeJson(path.join(output, 'asset-manifest.json'), manifest);
    const buildMetadata = {
      schemaVersion: 1,
      upstream: {
        repository: pin.repository,
        commit: pin.commit,
        packageSha256: pin.packageSha256,
        lockSha256: pin.lockSha256,
      },
      tools: metadata.tools,
      installation: metadata.installation,
      nodeEngineCompatible: pin.nodeEngineCompatible,
      patches: metadata.patches,
      overlay: metadata.overlay,
      buildScripts: metadata.buildScripts,
      dependenciesSha256: sha256(Buffer.from(JSON.stringify(metadata.dependencies))),
      sourceSha256: sha256(Buffer.from(JSON.stringify(metadata.preparedFiles))),
      assetPolicySha256: sha256(await readFile(path.join(rendererRoot, 'assets/policy.json'))),
      assetManifestSha256: sha256(await readFile(path.join(output, 'asset-manifest.json'))),
      checks: results.map(({ log: _log, ...result }) => result),
      entry: 'index.html',
      files: records.length,
      components: audit.components.length,
    };
    await writeJson(path.join(output, 'renderer-build.json'), buildMetadata);
    await verifyOutput(output);
    return buildMetadata;
  } catch (error) {
    await writeJson(path.join(output, 'BUILD-REJECTED.json'), { error: error.message });
    throw error;
  }
}
export async function verifyOutput(directory) {
  const manifest = await readJson(path.join(directory, 'asset-manifest.json'));
  const metadata = await readJson(path.join(directory, 'renderer-build.json'));
  if (
    manifest.schemaVersion !== 1 ||
    manifest.algorithm !== 'SHA-256' ||
    metadata.schemaVersion !== 1 ||
    metadata.entry !== manifest.entry
  )
    throw new Error('Unknown renderer manifest');
  if (metadata.nodeEngineCompatible !== true || !/^v(?:22|23|24)\./.test(metadata.tools?.node ?? ''))
    throw new Error('Incompatible Node cannot release renderer');
  if (metadata.assetManifestSha256 !== sha256(await readFile(path.join(directory, 'asset-manifest.json'))))
    throw new Error('Asset manifest hash mismatch');
  const expected = new Set(['asset-manifest.json', 'renderer-build.json']);
  for (const item of manifest.files) {
    if (
      expected.has(item.path) ||
      !item.path ||
      item.path.split('/').some((part) => ['', '.', '..'].includes(part)) ||
      path.isAbsolute(item.path) ||
      item.path.includes('\\')
    )
      throw new Error(`Invalid manifest path: ${item.path}`);
    expected.add(item.path);
    const bytes = await readFile(path.join(directory, item.path));
    if (bytes.length !== item.size || sha256(bytes) !== item.sha256) throw new Error(`Asset mismatch: ${item.path}`);
  }
  if (!expected.has(manifest.entry) || !expected.has('licenses/index.html') || !expected.has('THIRD_PARTY_NOTICES.txt'))
    throw new Error('Renderer entry or licenses missing');
  const actual = await files(directory);
  if (actual.some((name) => !expected.has(name)) || actual.length !== expected.size)
    throw new Error('Output file set differs from manifest');
  return metadata;
}
if (process.argv[1] && path.resolve(process.argv[1]) === path.join(rendererRoot, 'scripts/build.mjs')) {
  const options = args(process.argv.slice(2), ['workdir', 'output', 'verify']);
  (options.verify ? verifyOutput(path.resolve(options.verify)) : build(options))
    .then((result) => console.log(JSON.stringify(result, null, 2)))
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
