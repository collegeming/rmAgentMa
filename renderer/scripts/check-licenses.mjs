import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { args, files, readJson, rendererRoot, safeRelative, sha256, writeJson } from './common.mjs';

export const aionuiLicensePath = 'licenses/AionUi-Apache-2.0.txt';
const fontPattern = /\.(?:woff2?|ttf|otf)$/i;
const statementFiles = new Set([
  'NOTICE',
  'THIRD_PARTY_NOTICES.txt',
  'asset-manifest.json',
  'renderer-build.json',
]);

/** Files that carry no per-file licence entry: the statements themselves and the build's own metadata records. */
export function productFileExempt(name) {
  return statementFiles.has(name) || name.startsWith('licenses/');
}

/**
 * Maps every shipped product file to the licence texts that cover it, so the built directory can be checked in
 * one direction: product file -> licence entry -> complete licence text on disk.
 */
export async function licenseCoverage(output, audit, inventory, metadata) {
  const componentLicenses = new Map(
    inventory.map((component) => [`${component.name}@${component.version}`, component.texts.map((text) => text.path)])
  );
  const componentByModule = new Map();
  for (const component of audit.components)
    for (const module of component.modules) componentByModule.set(module, `${component.name}@${component.version}`);
  const chunkModules = new Map(audit.chunks.map((chunk) => [chunk.path, chunk.modules]));
  const admittedBySha = new Map(audit.sourceAssets.map((asset) => [asset.sha256, asset]));
  const bundled = [...componentLicenses.keys()].sort();
  const referenced = new Set([aionuiLicensePath]);
  const entries = [];
  for (const name of audit.files) {
    if (productFileExempt(name)) continue;
    const hash = sha256(await readFile(path.join(output, name)));
    let kind = 'renderer-content';
    let components = bundled;
    let basis = 'Modified AionUi renderer content; every component bundled into this build applies.';
    const modules = chunkModules.get(name);
    if (modules) {
      kind = 'bundled-chunk';
      components = [...new Set(modules.map((module) => componentByModule.get(module)).filter(Boolean))].sort();
      basis = 'Rollup chunk; only the components whose modules landed in this file.';
    } else if (admittedBySha.has(hash)) {
      const source = admittedBySha.get(hash).provenance;
      kind = 'admitted-source-asset';
      components = [];
      basis = `Admitted asset ${source.path} (${source.license}) — ${source.source}`;
    }
    const licenses = [...new Set([aionuiLicensePath, ...components.flatMap((component) => componentLicenses.get(component) ?? [])])].sort();
    for (const license of licenses) referenced.add(license);
    entries.push({ file: name, sha256: hash, kind, components, licenses, basis });
  }
  const texts = {};
  for (const name of [...referenced].sort()) texts[name] = sha256(await readFile(path.join(output, name)));
  return {
    schemaVersion: 1,
    upstream: { repository: metadata.upstream.repository, commit: metadata.upstream.commit },
    policySha256: audit.policySha256,
    fontDecision: audit.fontDecision,
    statements: {
      notice: 'NOTICE',
      thirdParty: 'THIRD_PARTY_NOTICES.txt',
      offlinePage: 'licenses/index.html',
      components: 'licenses/components.json',
    },
    texts,
    entries,
  };
}

export async function verifyLicenseClosure(directory, options = {}) {
  const root = path.resolve(directory);
  const policyFile = options.policy ?? path.join(rendererRoot, 'assets/policy.json');
  const policy = await readJson(policyFile);
  const pin = await readJson(path.join(rendererRoot, 'upstream.json'));
  const all = await files(root);
  const actual = new Set(all.filter((name) => !productFileExempt(name)));
  const coverage = await readJson(path.join(root, 'licenses/coverage.json'));
  if (coverage.schemaVersion !== 1 || !Array.isArray(coverage.entries) || !coverage.entries.length)
    throw new Error('Licence closure: missing product file to licence mapping (licenses/coverage.json)');
  if (coverage.policySha256 !== sha256(await readFile(policyFile)))
    throw new Error(`Licence closure: mapping was produced under another asset policy (rule closure-policy-mismatch)`);
  if (coverage.upstream?.commit !== pin.commit || coverage.upstream?.repository !== pin.repository)
    throw new Error(`Licence closure: mapping names another upstream than ${pin.repository}@${pin.commit}`);
  if (coverage.fontDecision !== policy.fontDecision?.decision)
    throw new Error(
      `Licence closure: font decision ${JSON.stringify(coverage.fontDecision)} differs from the asset policy (rule closure-font-decision)`
    );
  const fonts = [...actual].filter((name) => fontPattern.test(name));
  if (fonts.length)
    throw new Error(`Licence closure: fonts in the product outside the font decision: ${fonts.join(', ')} (rule unaccounted-font)`);
  for (const [statement, name] of Object.entries(coverage.statements ?? {})) {
    safeRelative(name);
    if (!all.includes(name))
      throw new Error(`Licence closure: missing ${statement} (${name}) (rule closure-statement-missing)`);
  }
  const notice = await readFile(path.join(root, 'NOTICE'), 'utf8');
  if (!notice.includes(pin.commit))
    throw new Error(`Licence closure: NOTICE does not name the packaged upstream commit (rule closure-notice-incomplete)`);
  const notices = await readFile(path.join(root, 'THIRD_PARTY_NOTICES.txt'), 'utf8');
  const components = await readJson(path.join(root, 'licenses/components.json'));
  const identities = new Set((components.components ?? []).map((component) => `${component.name}@${component.version}`));
  const referenced = new Set();
  const listed = new Set();
  for (const entry of coverage.entries) {
    safeRelative(entry.file);
    if (!actual.has(entry.file))
      throw new Error(`Licence closure: mapped file is not packaged: ${entry.file} (rule closure-phantom-entry)`);
    if (listed.has(entry.file)) throw new Error(`Licence closure: duplicate mapping for ${entry.file}`);
    listed.add(entry.file);
    if (sha256(await readFile(path.join(root, entry.file))) !== entry.sha256)
      throw new Error(`Licence closure: packaged ${entry.file} differs from its mapped hash (rule closure-file-changed)`);
    if (!entry.licenses?.length)
      throw new Error(`Licence closure: no licence entry for ${entry.file} (rule closure-missing-license)`);
    for (const license of entry.licenses) {
      safeRelative(license);
      const recorded = coverage.texts?.[license];
      if (!recorded)
        throw new Error(
          `Licence closure: ${entry.file} names uninventoried licence text ${license} (rule closure-license-unknown)`
        );
      let text;
      try {
        text = await readFile(path.join(root, license), 'utf8');
      } catch (error) {
        if (error.code !== 'ENOENT') throw error;
      }
      if (!text?.trim())
        throw new Error(`Licence closure: licence text ${license} for ${entry.file} is empty or missing`);
      if (sha256(Buffer.from(text)) !== recorded)
        throw new Error(`Licence closure: licence text ${license} differs from its recorded hash`);
      referenced.add(license);
    }
    for (const component of entry.components ?? [])
      if (!identities.has(component))
        throw new Error(
          `Licence closure: ${entry.file} names component ${component} that the component inventory does not hold (rule closure-component-unknown)`
        );
  }
  const uncovered = [...actual].filter((name) => !listed.has(name));
  if (uncovered.length)
    throw new Error(`Licence closure: product files without a licence entry: ${uncovered.join(', ')} (rule closure-missing-entry)`);
  const orphaned = all
    .filter((name) => /^licenses\/.*\.txt$/.test(name))
    .filter((name) => !referenced.has(name));
  if (orphaned.length)
    throw new Error(
      `Licence closure: licence texts that no product file uses: ${orphaned.join(', ')} (rule closure-orphan-license)`
    );
  for (const component of components.components ?? []) {
    const identity = `${component.name}@${component.version}`;
    if (!component.texts?.length)
      throw new Error(`Licence closure: no licence text for ${identity} (rule closure-component-text-missing)`);
    for (const text of component.texts)
      if (!referenced.has(text.path))
        throw new Error(`Licence closure: licence text ${text.path} of ${identity} is not used by any product file`);
    if (!notices.includes(`${component.name} ${component.version}`))
      throw new Error(`Licence closure: ${identity} is missing from THIRD_PARTY_NOTICES.txt`);
  }
  return {
    schemaVersion: 1,
    ok: true,
    entries: coverage.entries.length,
    components: identities.size,
    texts: coverage.texts ?? {},
    fontDecision: coverage.fontDecision,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.join(rendererRoot, 'scripts/check-licenses.mjs')) {
  const options = args(process.argv.slice(2), ['dir', 'policy', 'report']);
  if (!options.dir) throw new Error('--dir is required');
  verifyLicenseClosure(options.dir, { policy: options.policy })
    .then(async (result) => {
      const summary = {
        ok: result.ok,
        entries: result.entries,
        components: result.components,
        licenseTexts: Object.keys(result.texts).length,
        fontDecision: result.fontDecision,
      };
      if (options.report) await writeJson(options.report, summary);
      console.log(JSON.stringify(summary));
    })
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
