import { copyFile, mkdir, readFile, readdir, realpath, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { args, files, readJson, rendererRoot, safeRelative, sha256, writeJson } from './common.mjs';
import { lockedPackages } from './dependencies.mjs';

export function deniedPath(name, policy) {
  return policy.deniedPaths.some((denied) => name.toLowerCase().replaceAll('\\', '/').includes(denied.toLowerCase()));
}
export function assertResourceClosure(content, name, outputFiles) {
  if (!/\.(?:html?|css)$/i.test(name)) return;
  const references = [...content.matchAll(/(?:src|href)\s*=\s*["']([^"']+)["']|url\(\s*["']?([^\s)'";]+)/gi)].map(
    (match) => match[1] ?? match[2]
  );
  for (const reference of references) {
    if (/^(?:data:|blob:|#)/i.test(reference)) continue;
    if (/^[a-z][a-z\d+.-]*:/i.test(reference) || reference.startsWith('//'))
      throw new Error(`Nonlocal resource in ${name}: ${reference}`);
    const clean = decodeURIComponent(reference.split(/[?#]/)[0]);
    const resolved = clean.startsWith('/')
      ? clean.slice(1)
      : path.posix.normalize(path.posix.join(path.posix.dirname(name), clean));
    safeRelative(resolved);
    if (!outputFiles.includes(resolved)) throw new Error(`Missing local resource in ${name}: ${reference}`);
  }
}
export function assertLocalReferences(content, name) {
  if (/\.html?$|\.css$/i.test(name)) {
    const references = [...content.matchAll(/(?:src|href)\s*=\s*["']([^"']+)["']|url\(\s*["']?([^\s)'";]+)/gi)].map(
      (match) => match[1] ?? match[2]
    );
    if (references.some((reference) => /^(?:https?:)?\/\//i.test(reference)))
      throw new Error(`Remote resource in ${name}`);
  }
  if (
    /\.js$/i.test(name) &&
    /(?:https?:)?\/\/(?:cdn\.jsdelivr\.net|unpkg\.com|cdnjs\.cloudflare\.com|fonts\.googleapis\.com|fonts\.gstatic\.com)/i.test(
      content
    )
  )
    throw new Error(`Remote CDN in ${name}`);
  if (/\.js$/i.test(name) && /serviceWorker\s*\.\s*register\s*\(/.test(content))
    throw new Error(`Service worker registration in ${name}`);
}
async function packageFor(moduleId) {
  let directory = path.dirname(await realpath(moduleId.split('?')[0]));
  while (directory !== path.dirname(directory)) {
    try {
      const manifest = await readJson(path.join(directory, 'package.json'));
      if (
        manifest.name &&
        manifest.version &&
        directory.replaceAll('\\', '/').endsWith(`/node_modules/${manifest.name}`)
      )
        return { directory, manifest };
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    directory = path.dirname(directory);
  }
  throw new Error(`No package metadata: ${moduleId}`);
}
async function licenseFiles(directory, relative = '') {
  const result = [];
  for (const entry of await readdir(path.join(directory, relative), { withFileTypes: true })) {
    if (['node_modules', '.git'].includes(entry.name)) continue;
    const name = path.posix.join(relative, entry.name);
    if (entry.isDirectory()) result.push(...(await licenseFiles(directory, name)));
    else if (
      entry.isFile() &&
      /^(?:licen[sc]e|copying|notice|thirdpartynotices|copyright)(?:[._ -].*)?$/i.test(entry.name)
    )
      result.push(name);
  }
  return result.sort();
}
export async function auditAssets(options) {
  const directory = path.resolve(options.dir);
  if (!options.workdir) throw new Error('Missing prepared workspace');
  const policy = await readJson(options.policy ?? path.join(rendererRoot, 'assets/policy.json'));
  const audit = await readJson(path.join(directory, 'rmagent-modules.json'));
  if (audit.schemaVersion !== 1 || !audit.chunks?.length || !audit.sourceAssets || !audit.assets || !audit.external)
    throw new Error('Missing actual Rollup module/asset audit');
  if (audit.external.length) throw new Error(`External runtime modules forbidden: ${audit.external.join(', ')}`);
  const outputFiles = await files(directory);
  if (!outputFiles.includes('index.html') || !outputFiles.some((name) => name.endsWith('.js')))
    throw new Error('Renderer output is empty');
  const deniedModules = [
    ...new Set(
      audit.chunks
        .flatMap((chunk) => chunk.modules)
        .filter((item) => deniedPath(item.id, policy))
        .map((item) => item.id)
    ),
  ];
  if (deniedModules.length)
    throw new Error(`Unadmitted modules (${deniedModules.length}):\n${deniedModules.join('\n')}`);
  const modules = new Set();
  const packageInstances = new Map();
  const assets = [];
  for (const chunk of audit.chunks) {
    safeRelative(chunk.path);
    if (!outputFiles.includes(chunk.path)) throw new Error(`Chunk missing: ${chunk.path}`);
    for (const item of chunk.modules) {
      if (deniedPath(item.id, policy)) throw new Error(`Unadmitted module: ${item.id}`);
      modules.add(item.id);
      if (item.id.includes('/node_modules/') && path.isAbsolute(item.id.split('?')[0]) && !item.id.includes('\0')) {
        const component = await packageFor(item.id);
        packageInstances.set(component.directory, component);
      }
    }
  }
  for (const source of audit.sourceAssets) {
    if (deniedPath(source.path, policy)) throw new Error(`Unadmitted source asset: ${source.path}`);
    if (/\.(?:svg|png|jpe?g|webp|gif|ico|woff2?|ttf|otf|wasm)$/i.test(source.path)) {
      const admission = policy.sourceAssets.find(
        (entry) =>
          source.path.replaceAll('\\', '/').endsWith(`/${entry.path}`) &&
          entry.sha256 === source.sha256 &&
          entry.license &&
          entry.source
      );
      if (!admission)
        throw new Error(`Source asset needs explicit provenance/admission: ${source.path} (${source.sha256})`);
      if (sha256(await readFile(source.path)) !== source.sha256)
        throw new Error(`Source asset changed: ${source.path}`);
      assets.push({ ...source, provenance: admission });
    }
  }
  for (const name of outputFiles) {
    if (deniedPath(`/${name}`, policy) || policy.deniedOutputExtensions.includes(path.extname(name).toLowerCase()))
      throw new Error(`Unadmitted output file: ${name}`);
    const bytes = await readFile(path.join(directory, name));
    if (/\.(?:html|css|js)$/.test(name)) assertLocalReferences(bytes.toString('utf8'), name);
    assertResourceClosure(bytes.toString('utf8'), name, outputFiles);
    if (/\.(?:svg|png|jpe?g|webp|gif|ico|wasm)$/i.test(name) && !assets.some((asset) => asset.sha256 === sha256(bytes)))
      throw new Error(`Unmapped copied asset: ${name}`);
    if (!/\.(?:html|css|js|json|svg|png|jpe?g|webp|gif|ico|wasm)$/.test(name))
      throw new Error(`Unknown output file type: ${name}`);
  }
  const components = [];
  const locked = lockedPackages(await readFile(path.join(options.workdir, 'bun.lock'), 'utf8'));
  for (const { directory: packageRoot, manifest } of packageInstances.values()) {
    const identity = `${manifest.name}@${manifest.version}`;
    const integrity = locked.get(identity);
    if (!integrity) throw new Error(`Actual bundle component absent from fixed lock: ${identity}`);
    const decision = policy.licenseDecisions[identity];
    if (decision?.texts?.length) {
      const evidence = await readJson(path.join(rendererRoot, 'licenses/source-evidence.json'));
      if (
        !evidence.packages.some(
          (record) =>
            record.name === manifest.name &&
            record.version === manifest.version &&
            record.integrity === integrity &&
            record.gitHead
        )
      )
        throw new Error(`Archived license npm/lock evidence mismatch: ${identity}`);
    }
    const concluded = decision?.licenseConcluded ?? manifest.license;
    if (!policy.allowedPackageLicenses.includes(concluded))
      throw new Error(`License not admitted: ${identity} (${JSON.stringify(manifest.license)})`);
    const texts = [];
    for (const name of await licenseFiles(packageRoot)) {
      const bytes = await readFile(path.join(packageRoot, name));
      texts.push({ path: name, sha256: sha256(bytes), text: bytes.toString('utf8') });
    }
    for (const record of decision?.texts ?? []) {
      safeRelative(record.path);
      const bytes = await readFile(path.join(rendererRoot, record.path));
      if (sha256(bytes) !== record.sha256 || !record.source)
        throw new Error(`Archived license provenance mismatch: ${identity}`);
      texts.push({ path: record.path, sha256: record.sha256, text: bytes.toString('utf8'), source: record.source });
    }
    if (!texts.length) throw new Error(`No license text for actual bundle component: ${identity}`);
    components.push({
      name: manifest.name,
      version: manifest.version,
      instance: packageRoot,
      integrity,
      licenseDeclared: manifest.license ?? 'NOASSERTION',
      licenseConcluded: concluded,
      decision: decision?.reason ?? null,
      modules: [...modules].filter((id) => id.startsWith(`${packageRoot}/`)),
      texts,
    });
  }
  return {
    schemaVersion: 1,
    modules: [...modules].sort(),
    sourceAssets: assets,
    components: components.sort((a, b) => a.name.localeCompare(b.name, 'en')),
    files: outputFiles,
  };
}
export async function emitLicenses(output, audit, workdir, metadata) {
  const directory = path.join(output, 'licenses');
  await mkdir(directory, { recursive: false });
  await copyFile(path.join(workdir, 'LICENSE'), path.join(directory, 'AionUi-Apache-2.0.txt'));
  const evidence = await readJson(path.join(rendererRoot, 'licenses/source-evidence.json'));
  await writeJson(path.join(directory, 'source-evidence.json'), {
    ...evidence,
    packages: evidence.packages.filter((record) =>
      audit.components.some((component) => component.name === record.name && component.version === record.version)
    ),
  });
  const copyright = `rmAgentMa renderer probe\n\nContains modified AionUi ${metadata.upstream.commit}.\nCopyright 2025 AionUi (aionui.com). Licensed under Apache-2.0.\nModifications: transport, probe entry, permission history, mobile input and asset restrictions.\n`;
  await writeFile(path.join(output, 'NOTICE'), copyright);
  const notices = [copyright, '\nAionUi license: licenses/AionUi-Apache-2.0.txt\n'];
  const inventory = [];
  for (const component of audit.components) {
    const records = [];
    notices.push(
      `\n${component.name} ${component.version}\nDeclared: ${JSON.stringify(component.licenseDeclared)}\nConcluded: ${component.licenseConcluded}\n${component.decision ?? ''}\n`
    );
    for (const license of component.texts) {
      const filename = `${license.sha256}.txt`;
      await writeFile(path.join(directory, filename), license.text);
      records.push({
        sourcePath: license.path,
        path: `licenses/${filename}`,
        sha256: license.sha256,
        source: license.source ?? null,
      });
      notices.push(`\n--- ${license.path} ---\n${license.text}\n`);
    }
    const { texts: _texts, ...record } = component;
    inventory.push({ ...record, texts: records });
  }
  await writeFile(path.join(output, 'THIRD_PARTY_NOTICES.txt'), notices.join(''));
  await writeJson(path.join(directory, 'components.json'), {
    schemaVersion: 1,
    upstream: metadata.upstream.commit,
    components: inventory,
  });
  await writeFile(
    path.join(directory, 'index.html'),
    '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>开源许可</title><h1>开源许可</h1><ul><li><a href="../NOTICE">NOTICE</a></li><li><a href="../THIRD_PARTY_NOTICES.txt">完整第三方声明</a></li><li><a href="components.json">实际构建组件清单</a></li><li><a href="AionUi-Apache-2.0.txt">AionUi Apache-2.0</a></li></ul></html>\n'
  );
}
if (process.argv[1] && path.resolve(process.argv[1]) === path.join(rendererRoot, 'scripts/check-assets.mjs')) {
  const options = args(process.argv.slice(2), ['dir', 'workdir', 'policy']);
  if (!options.dir || !options.workdir) throw new Error('--dir and --workdir are required');
  auditAssets(options)
    .then((result) =>
      console.log(
        JSON.stringify({
          files: result.files.length,
          components: result.components.length,
          modules: result.modules.length,
        })
      )
    )
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
