import { copyFile, mkdir, readFile, realpath, stat, symlink } from 'node:fs/promises';
import path from 'node:path';
import {
  args,
  files,
  git,
  gitBlob,
  inputFiles,
  readJson,
  regularFile,
  rendererRoot,
  safeRelative,
  sensitive,
  sha256,
  tools,
  run,
  writeJson,
} from './common.mjs';
import { inspectDependencies } from './dependencies.mjs';

export async function prepare(options) {
  if (!options.source || !options.workdir)
    throw new Error('--source and --workdir are required; the destination must not exist');
  const source = await realpath(options.source);
  const workdir = path.resolve(options.workdir);
  if (workdir === source || workdir.startsWith(`${source}${path.sep}`))
    throw new Error('Build workspace must be outside the source');
  const pin = await readJson(path.join(rendererRoot, 'upstream.json'));
  const versions = await tools(pin);
  const commit = git(source, 'rev-parse', 'HEAD').toString().trim();
  if (commit !== pin.commit) throw new Error(`Wrong upstream commit: ${commit}`);
  const inputs = git(source, 'ls-tree', '-rz', '--full-tree', pin.commit)
    .toString()
    .split('\0')
    .filter(Boolean)
    .map((line) => {
      const [mode, type, blob, name] = line.match(/^(\d+) (\w+) ([a-f0-9]+)\t(.*)$/s).slice(1);
      safeRelative(name);
      if (type !== 'blob' || !['100644', '100755'].includes(mode))
        throw new Error(`Unsupported tracked entry: ${name}`);
      if (/^(?:node_modules|out|dist|\.git)(\/|$)/.test(name)) throw new Error(`Forbidden tracked input: ${name}`);
      return { path: name, mode, blob };
    })
    .filter((item) => !sensitive(item.path) && !/^resources\/.*\.(?:gif|png|jpe?g|mp4|webm)$/i.test(item.path));
  for (const item of inputs) {
    const bytes = await readFile(await regularFile(source, item.path));
    if (gitBlob(bytes) !== item.blob) throw new Error(`Source missing or not original: ${item.path}`);
  }
  for (const [name, expected] of [
    ['package.json', pin.packageSha256],
    ['bun.lock', pin.lockSha256],
  ]) {
    if (sha256(await readFile(path.join(source, name))) !== expected) throw new Error(`Original ${name} mismatch`);
  }
  const patches = await inputFiles(path.join(rendererRoot, 'patches'));
  const overlay = await inputFiles(path.join(rendererRoot, 'overlay'));
  if (!patches.length || !patches.some((item) => item.path.endsWith('.patch')))
    throw new Error('No renderer patches; refusing an unmodified upstream build');
  let cache;
  let dependencies = null;
  if (options['node-modules']) {
    cache = await realpath(options['node-modules']);
    if (!(await stat(cache)).isDirectory()) throw new Error('node_modules cache is not a directory');
    const cacheRoot = path.dirname(cache);
    if (
      sha256(await readFile(path.join(cacheRoot, 'bun.lock'))) !== pin.lockSha256 ||
      sha256(await readFile(path.join(cacheRoot, 'package.json'))) !== pin.packageSha256
    )
      throw new Error('Dependency cache must belong to the fixed original package and lock');
    if (!(await stat(path.join(cache, '.bin/electron-vite'))).isFile())
      throw new Error('Dependency cache has no electron-vite');
    dependencies = await inspectDependencies(cache, path.join(source, 'bun.lock'));
  }
  await mkdir(workdir, { recursive: false });
  for (const item of inputs) {
    const destination = path.join(workdir, item.path);
    await mkdir(path.dirname(destination), { recursive: true });
    await copyFile(path.join(source, item.path), destination);
  }
  for (const patch of patches.filter((item) => item.path.endsWith('.patch'))) {
    const location = path.join(rendererRoot, 'patches', patch.path);
    git(workdir, 'apply', '--check', location);
    git(workdir, 'apply', location);
  }
  for (const item of overlay) {
    const destination = path.join(workdir, item.path);
    safeRelative(item.path);
    if (/^(?:node_modules|out|dist|\.git|\.rmagent)(\/|$)/.test(item.path))
      throw new Error(`Forbidden overlay destination: ${item.path}`);
    await mkdir(path.dirname(destination), { recursive: true });
    await copyFile(path.join(rendererRoot, 'overlay', item.path), destination);
  }
  if (sha256(await readFile(path.join(workdir, 'bun.lock'))) !== pin.lockSha256)
    throw new Error('Patches must preserve the full upstream lock');
  await mkdir(path.join(workdir, '.rmagent'), { recursive: true });
  await copyFile(
    path.join(rendererRoot, 'scripts/audit-plugin.mjs'),
    path.join(workdir, '.rmagent/renderer-audit.mjs')
  );
  const configPath = path.join(workdir, 'packages/desktop/electron.vite.config.ts');
  let config = await readFile(configPath, 'utf8');
  if (config.split('iconParkPlugin(),').length !== 2 || !config.includes("publicDir: resolve('public'),"))
    throw new Error('Cannot inject renderer audit into the patched standard config');
  config = config.replace("publicDir: resolve('public'),", 'publicDir: false,');
  for (const entry of [
    "            pet: resolve(rendererRoot, 'pet/pet.html'),\n",
    "            'pet-hit': resolve(rendererRoot, 'pet/pet-hit.html'),\n",
    "            'pet-confirm': resolve(rendererRoot, 'pet/pet-confirm.html'),\n",
  ]) {
    if (!config.includes(entry)) throw new Error('Cannot restrict renderer HTML entrypoints');
    config = config.replace(entry, '');
  }
  const { writeFile } = await import('node:fs/promises');
  await writeFile(
    configPath,
    `import { rendererAudit } from '../../.rmagent/renderer-audit.mjs';\n${config.replace('iconParkPlugin(),', 'iconParkPlugin(),\n        rendererAudit(),')}`
  );
  const ignoreFile = path.join(workdir, '.prettierignore');
  await writeFile(ignoreFile, `${await readFile(ignoreFile, 'utf8')}\n.rmagent/\n`);
  if (cache) {
    run(
      path.join(cache, '.bin/oxfmt'),
      ['--config', path.join(workdir, '.oxfmtrc.json'), configPath, path.join(workdir, '.rmagent/renderer-audit.mjs')],
      workdir
    );
  }
  const preparedFiles = [];
  for (const name of await files(workdir)) {
    preparedFiles.push({ path: name, sha256: sha256(await readFile(path.join(workdir, name))) });
  }
  if (cache) {
    await symlink(cache, path.join(workdir, 'node_modules'), 'dir');
    const cacheRoot = path.dirname(cache);
    for (const workspace of await (await import('node:fs/promises')).readdir(path.join(workdir, 'packages'))) {
      const cached = path.join(cacheRoot, 'packages', workspace, 'node_modules');
      try {
        if ((await stat(cached)).isDirectory())
          await symlink(await realpath(cached), path.join(workdir, 'packages', workspace, 'node_modules'), 'dir');
      } catch (error) {
        if (error.code !== 'ENOENT') throw error;
      }
    }
  }
  for (const name of ['home/config', 'home/cache', 'tmp', 'logs'])
    await mkdir(path.join(workdir, '.rmagent', name), { recursive: true });
  const metadata = {
    schemaVersion: 1,
    source,
    workdir: await realpath(workdir),
    upstream: pin,
    tools: versions,
    nodeModulesCache: cache ?? null,
    dependencies,
    installation: cache ? { mode: 'explicit-cache', lockSha256: pin.lockSha256, cacheSource: cache } : null,
    buildScripts: await inputFiles(path.join(rendererRoot, 'scripts')),
    patches,
    overlay,
    preparedFiles,
    excludedInputs: {
      credentialsAndLocalConfig: true,
      promotionalMedia: 'resources/**/*.{gif,png,jpg,jpeg,mp4,webm}',
      publicCopies: 'disabled',
      rendererHtmlEntries: ['index.html'],
    },
  };
  await writeJson(path.join(workdir, '.rmagent/prepare.json'), metadata);
  return metadata;
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.join(rendererRoot, 'scripts/prepare.mjs')) {
  prepare(args(process.argv.slice(2), ['source', 'workdir', 'node-modules']))
    .then((result) =>
      console.log(JSON.stringify({ workdir: result.workdir, tools: result.tools, patches: result.patches }, null, 2))
    )
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
