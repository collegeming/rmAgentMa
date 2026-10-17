import { mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { args, assertPrepared, isolatedEnv, rendererRoot, run, sha256, writeJson } from './common.mjs';
import { inspectDependencies } from './dependencies.mjs';

export async function install(options) {
  if (!options.workdir) throw new Error('--workdir is required; installation is explicit and never performed by build');
  const workdir = path.resolve(options.workdir);
  const { metadata, pin } = await assertPrepared(workdir);
  if (metadata.nodeModulesCache) throw new Error('Workspace already uses an explicit dependency cache');
  const env = isolatedEnv(workdir);
  await mkdir(env.HOME, { recursive: true });
  run('bun', ['install', '--frozen-lockfile', '--ignore-scripts'], workdir, env);
  if (sha256(await readFile(path.join(workdir, 'bun.lock'))) !== pin.lockSha256)
    throw new Error('Install changed original lock');
  const dependencies = await inspectDependencies(path.join(workdir, 'node_modules'), path.join(workdir, 'bun.lock'));
  run(
    path.join(workdir, 'node_modules/.bin/oxfmt'),
    ['--config', path.join(workdir, '.oxfmtrc.json'), path.join(workdir, 'packages/desktop/electron.vite.config.ts')],
    workdir,
    env
  );
  for (const record of metadata.preparedFiles) {
    if (record.path === 'packages/desktop/electron.vite.config.ts')
      record.sha256 = sha256(await readFile(path.join(workdir, record.path)));
  }
  metadata.dependencies = dependencies;
  metadata.installation = {
    command: ['bun', 'install', '--frozen-lockfile', '--ignore-scripts'],
    lockSha256: pin.lockSha256,
    lifecycleScripts: 'disabled',
    mode: 'frozen-install',
  };
  await writeJson(path.join(workdir, '.rmagent/prepare.json'), metadata);
  return metadata.installation;
}
if (process.argv[1] && path.resolve(process.argv[1]) === path.join(rendererRoot, 'scripts/install.mjs')) {
  install(args(process.argv.slice(2), ['workdir']))
    .then((result) => console.log(JSON.stringify(result)))
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
