import { readdir, readFile, realpath } from 'node:fs/promises';
import path from 'node:path';
import { readJson, sha256 } from './common.mjs';

export function lockedPackages(text) {
  const result = new Map();
  for (const line of text.split('\n')) {
    const match = line.match(/^\s{4}"[^"]+": \["(.+?)@([^"@]+)",.*"(sha(?:256|512)-[A-Za-z0-9+/=]+)"\],?$/);
    if (match) result.set(`${match[1]}@${match[2]}`, match[3]);
  }
  if (!result.size) throw new Error('Cannot enumerate locked dependency identities');
  return result;
}
export async function inspectDependencies(nodeModules, lockfile) {
  const locked = lockedPackages(await readFile(lockfile, 'utf8'));
  const instances = [];
  const store = path.join(await realpath(nodeModules), '.bun');
  for (const entry of await readdir(store, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name === 'node_modules') continue;
    const directory = path.join(store, entry.name, 'node_modules');
    for (const group of await readdir(directory, { withFileTypes: true })) {
      const candidates =
        group.name.startsWith('@') && group.isDirectory()
          ? (await readdir(path.join(directory, group.name))).map((name) => path.join(directory, group.name, name))
          : [path.join(directory, group.name)];
      for (const candidate of candidates) {
        const resolved = await realpath(candidate);
        if (!resolved.startsWith(`${path.join(store, entry.name)}${path.sep}`)) continue;
        let manifest;
        try {
          manifest = await readJson(path.join(resolved, 'package.json'));
        } catch (error) {
          if (error.code === 'ENOENT') continue;
          throw error;
        }
        const identity = `${manifest.name}@${manifest.version}`;
        const integrity = locked.get(identity);
        if (!integrity) throw new Error(`Cached dependency is not in fixed lock: ${identity} (${resolved})`);
        instances.push({
          name: manifest.name,
          version: manifest.version,
          instance: resolved,
          integrity,
          manifestSha256: sha256(await readFile(path.join(resolved, 'package.json'))),
        });
      }
    }
  }
  for (const name of ['electron-vite', 'vite', 'vitest', 'typescript', 'oxfmt', 'oxlint']) {
    const resolved = await realpath(path.join(nodeModules, name));
    const manifest = await readJson(path.join(resolved, 'package.json'));
    if (
      !instances.some((item) => item.instance === resolved && item.name === name && item.version === manifest.version)
    )
      throw new Error(`Required tool not in verified lock/cache: ${name}`);
  }
  return {
    schemaVersion: 1,
    verification:
      'Installed package identities and manifest hashes cross-checked against the original Bun lock; cached tarball bytes are not re-downloaded.',
    instances,
  };
}
