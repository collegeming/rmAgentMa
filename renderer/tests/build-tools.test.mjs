import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {
  args,
  assertNodeCompatible,
  git,
  gitBlob,
  safeRelative,
  sensitive,
  sha256,
  writeJson,
} from '../scripts/common.mjs';
import { assertLocalReferences, assertResourceClosure, auditAssets, deniedPath, enforcedRules, matchContentRule, matchDeniedPath } from '../scripts/check-assets.mjs';
import { verifyOutput } from '../scripts/build.mjs';
import { verifyLicenseClosure } from '../scripts/check-licenses.mjs';
import { lockedPackages } from '../scripts/dependencies.mjs';
import { rendererAudit } from '../scripts/audit-plugin.mjs';

const temporary = [];
afterEach(async () => {
  await Promise.all(temporary.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});
async function fixture() {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'rmagent-build-test-'));
  temporary.push(directory);
  return directory;
}

describe('build input boundaries', () => {
  it('never permits incompatible Node or a false compatibility pin', () => {
    const pin = { nodeEngineCompatible: true, upstreamNodeEngines: '>=22 <25' };
    expect(() => assertNodeCompatible('v22.22.2', pin)).not.toThrow();
    expect(() => assertNodeCompatible('v26.5.0', pin)).toThrow('release is forbidden');
    expect(() => assertNodeCompatible('v22.22.2', { ...pin, nodeEngineCompatible: false })).toThrow();
  });
  it('applies patches only in the isolated workspace even below another git checkout', async () => {
    const directory = await fixture();
    git(directory, 'init', '--quiet');
    await writeFile(path.join(directory, 'input.txt'), 'outside\n');
    const workdir = path.join(directory, 'build');
    await mkdir(workdir);
    await writeFile(path.join(workdir, 'input.txt'), 'before\n');
    const patch = path.join(directory, 'change.patch');
    await writeFile(
      patch,
      'diff --git a/input.txt b/input.txt\n--- a/input.txt\n+++ b/input.txt\n@@ -1 +1 @@\n-before\n+after\n'
    );
    git(workdir, 'apply', '--check', patch);
    git(workdir, 'apply', patch);
    expect(await readFile(path.join(workdir, 'input.txt'), 'utf8')).toBe('after\n');
    expect(await readFile(path.join(directory, 'input.txt'), 'utf8')).toBe('outside\n');
  });
  it('rejects unknown, duplicate and missing CLI options', () => {
    expect(args(['--source', '/a'], ['source'])).toEqual({ source: '/a' });
    for (const argv of [['--source'], ['--wat', 'x'], ['--source', 'a', '--source', 'b']])
      expect(() => args(argv, ['source'])).toThrow();
  });
  it('refuses escaping paths and identifies config/credentials without reading them', () => {
    for (const name of ['../config', '/etc/a', 'a/../../b', 'a\\b', 'a/./b'])
      expect(() => safeRelative(name)).toThrow();
    for (const name of ['.env', 'a/.env.local', 'a/id_rsa', '.npmrc', 'bunfig.toml'])
      expect(sensitive(name)).toBe(true);
    expect(sensitive('.env.example')).toBe(false);
    expect(safeRelative('packages/a.ts')).toBe('packages/a.ts');
    expect(gitBlob(Buffer.from('hello\n'))).toBe('ce013625030ba8dba906f756967f9e9ca394464a');
  });
  it('enumerates exact locked npm identities including scoped and nested versions', () => {
    const locked = lockedPackages(
      '    "p": ["p@1.2.3", "", {}, "sha512-abc="],\n    "x/@s/p": ["@s/p@4.5.6", "", {}, "sha512-def="],'
    );
    expect([...locked]).toEqual([
      ['p@1.2.3', 'sha512-abc='],
      ['@s/p@4.5.6', 'sha512-def='],
    ]);
    expect(() => lockedPackages('{}')).toThrow();
  });
});
describe('actual artifact audit', () => {
  it('requires every local HTML/CSS reference to exist in the emitted set', () => {
    expect(() =>
      assertResourceClosure('<script src="./assets/x.js"></script>', 'index.html', ['index.html', 'assets/x.js'])
    ).not.toThrow();
    expect(() => assertResourceClosure('a{src:url(./bad.woff)}', 'assets/x.css', ['assets/x.css'])).toThrow(
      'Missing local resource'
    );
    expect(() => assertResourceClosure('<img src="../../outside">', 'index.html', [])).toThrow('Unsafe path');
  });
  it('records actual chunks, modules, source hashes and externals from Rollup', async () => {
    const plugin = rendererAudit();
    const directory = await fixture();
    const filename = path.join(directory, 'x.json');
    await writeFile(filename, '{"x":1}');
    plugin.transform('', filename);
    let emitted;
    plugin.generateBundle.call(
      {
        getModuleIds: () => ['node:fs'],
        getModuleInfo: () => ({ isExternal: true }),
        emitFile: (item) => {
          emitted = item;
        },
      },
      {},
      {
        'a.js': {
          type: 'chunk',
          imports: [],
          dynamicImports: [],
          modules: { [filename]: { renderedLength: 5, originalLength: 7 } },
        },
      }
    );
    const audit = JSON.parse(emitted.source);
    expect(audit.external).toEqual(['node:fs']);
    expect(audit.sourceAssets[0].sha256).toBe(sha256(Buffer.from('{"x":1}')));
    expect(audit.chunks[0].modules[0].sourceSha256).toBe(audit.sourceAssets[0].sha256);
    expect(audit.assets).toEqual([{ path: 'rmagent-modules.json', names: [], originalFileNames: [] }]);
  });
  it('rejects remote CSS/HTML resources, CDN runtimes and service workers', () => {
    for (const [text, name] of [
      ['<script src="https://example.com/x.js"></script>', 'index.html'],
      ['a{src:url(//example.com/x.woff)}', 'x.css'],
      ['const x="https://cdn.jsdelivr.net/npm/x"', 'x.js'],
      ['navigator.serviceWorker.register("x")', 'x.js'],
    ])
      expect(() => assertLocalReferences(text, name)).toThrow();
    expect(() => assertLocalReferences('<script src="./assets/x.js"></script>', 'index.html')).not.toThrow();
  });
  it('blocks known unadmitted sources and names the rule that caught them', () => {
    const policy = {
      deniedPaths: [
        { rule: 'formula-font-degradation', match: 'katex', reason: '19/12 §4' },
        { rule: 'unadmitted-icon-set', match: 'catppuccin', reason: '19/12 §3' },
      ],
    };
    expect(deniedPath('/x/node_modules/katex/index.js', policy)).toBe(true);
    expect(matchDeniedPath('/x/node_modules/katex/index.js', policy).rule).toBe('formula-font-degradation');
    expect(matchDeniedPath('/x/renderer/fileIcon/catppuccinLatte.json', policy).rule).toBe('unadmitted-icon-set');
    expect(deniedPath('/x/renderer/components/SendBox.tsx', policy)).toBe(false);
    expect(matchDeniedPath('/x/renderer/components/SendBox.tsx', policy)).toBe(null);
  });
  it('finds unadmitted icon and brand data that survives into emitted content', () => {
    const policy = {
      contentRules: [
        { rule: 'unadmitted-icon-set', pattern: 'catppuccin(?:Latte|Macchiato)', reason: '19/12 §3' },
        { rule: 'brand-or-channel-assets', pattern: 'channel-logos', reason: '19/12 §2' },
      ],
    };
    expect(matchContentRule('const a="catppuccinMacchiato";', 'assets/a.js', policy).rule).toBe('unadmitted-icon-set');
    expect(matchContentRule('a{background:url(channel-logos/x.svg)}', 'assets/a.css', policy).rule).toBe(
      'brand-or-channel-assets'
    );
    expect(matchContentRule('const a="catppuccin"', 'assets/a.js', policy)).toBe(null);
    expect(matchContentRule('<a href="../NOTICE">NOTICE</a>', 'licenses/index.html', policy)).toBe(null);
  });
  it('requires every rule the policy declares to be backed by a real entry', () => {
    const policy = {
      requiredRules: ['brand-or-channel-assets', 'unaccounted-font'],
      deniedPaths: [{ rule: 'brand-or-channel-assets', match: 'channel-logos', reason: '19/12 §2' }],
      contentRules: [],
      deniedOutputExtensions: {},
    };
    expect(enforcedRules(policy)).toEqual([
      { rule: 'brand-or-channel-assets', pathRules: 1, contentRules: 0, extensionRules: 0, builtin: false, ok: true },
      { rule: 'unaccounted-font', pathRules: 0, contentRules: 0, extensionRules: 0, builtin: true, ok: true },
    ]);
    const gutted = { ...policy, deniedPaths: [] };
    expect(enforcedRules(gutted)[0].ok).toBe(false);
  });
  it('keeps the repo policy honest about the exclusions the license research requires', async () => {
    const policy = JSON.parse(await readFile(new URL('../assets/policy.json', import.meta.url), 'utf8'));
    const rules = enforcedRules(policy);
    expect(rules.every((rule) => rule.ok)).toBe(true);
    expect(rules.map((rule) => rule.rule)).toEqual(expect.arrayContaining([
      'brand-or-channel-assets',
      'unadmitted-icon-set',
      'unadmitted-pet-assets',
      'formula-font-degradation',
      'unaccounted-font',
      'unaccounted-static-asset',
      'build-tool-only',
      'non-free-native',
      'unverified-cdn-loader',
    ]));
    for (const entry of [
      ...policy.deniedPaths,
      ...policy.contentRules,
      ...Object.values(policy.deniedOutputExtensions),
    ]) {
      expect(typeof entry.rule).toBe('string');
      expect(entry.reason).toMatch(/19\/12|19 §5|L2/);
    }
    for (const entry of policy.deniedPaths) expect(typeof entry.match).toBe('string');
    for (const entry of policy.contentRules) expect(typeof entry.pattern).toBe('string');
    expect(policy.fontDecision.decision).toBe('source-only-formulas');
    expect(policy.fontDecision.reason).toMatch(/19\/12 §4/);
  });
  it('restores full license text only from version/hash/integrity-bound archive evidence', async () => {
    const directory = await fixture();
    const library = path.join(directory, 'node_modules', 'number-precision');
    await mkdir(library, { recursive: true });
    await writeJson(path.join(library, 'package.json'), { name: 'number-precision', version: '1.6.0', license: 'MIT' });
    await writeFile(path.join(library, 'index.js'), 'export const x=1;');
    const evidence = JSON.parse(await readFile(new URL('../licenses/source-evidence.json', import.meta.url)));
    const integrity = evidence.packages.find((record) => record.name === 'number-precision').integrity;
    await writeFile(
      path.join(directory, 'bun.lock'),
      `    "number-precision": ["number-precision@1.6.0", "", {}, "${integrity}"],`
    );
    const output = path.join(directory, 'output');
    await mkdir(output);
    await writeFile(path.join(output, 'index.html'), '<script src="./a.js"></script>');
    await writeFile(path.join(output, 'a.js'), 'export const x=1;');
    await writeJson(path.join(output, 'rmagent-modules.json'), {
      schemaVersion: 1,
      chunks: [{ path: 'a.js', modules: [{ id: path.join(library, 'index.js') }] }],
      assets: [{ path: 'rmagent-modules.json', names: [], originalFileNames: [] }],
      sourceAssets: [],
      external: [],
    });
    const audit = await auditAssets({ dir: output, workdir: directory });
    expect(audit.components[0].texts.some((item) => item.text.includes('Permission is hereby granted'))).toBe(true);
    await writeFile(
      path.join(directory, 'bun.lock'),
      '    "number-precision": ["number-precision@1.6.0", "", {}, "sha512-wrong="],'
    );
    await expect(auditAssets({ dir: output, workdir: directory })).rejects.toThrow('evidence mismatch');
  });
  it('collects nested license notices only from actual locked bundle components', async () => {
    const directory = await fixture();
    const library = path.join(directory, 'node_modules', 'sample');
    await mkdir(path.join(library, 'vendor'), { recursive: true });
    await writeJson(path.join(library, 'package.json'), { name: 'sample', version: '1.0.0', license: 'MIT' });
    await writeFile(path.join(library, 'LICENSE'), 'MIT grant');
    await writeFile(path.join(library, 'vendor/ThirdPartyNotices.txt'), 'Nested copyright');
    await writeFile(path.join(library, 'vendor/index.js'), 'export const x=1;');
    await writeJson(path.join(library, 'vendor/package.json'), { name: 'sample-icons', version: '0.0.0' });
    await writeFile(path.join(directory, 'bun.lock'), '    "sample": ["sample@1.0.0", "", {}, "sha512-abc="],');
    const output = path.join(directory, 'output');
    await mkdir(output);
    await writeFile(path.join(output, 'index.html'), '<script src="./a.js"></script>');
    await writeFile(path.join(output, 'a.js'), 'export const x=1;');
    await writeJson(path.join(output, 'rmagent-modules.json'), {
      schemaVersion: 1,
      chunks: [{ path: 'a.js', modules: [{ id: path.join(library, 'vendor/index.js') }] }],
      assets: [{ path: 'rmagent-modules.json', names: [], originalFileNames: [] }],
      sourceAssets: [],
      external: [],
    });
    const audit = await auditAssets({ dir: output, workdir: directory });
    expect(audit.components).toHaveLength(1);
    expect(audit.components[0].texts.map((item) => item.path)).toEqual(['LICENSE', 'vendor/ThirdPartyNotices.txt']);
    expect(audit.components[0].integrity).toBe('sha512-abc=');
  });
  it('fails closed on a missing module audit and on copied fonts', async () => {
    const directory = await fixture();
    await writeFile(path.join(directory, 'index.html'), '<html/>');
    await expect(auditAssets({ dir: directory, workdir: directory })).rejects.toThrow();
    await writeFile(path.join(directory, 'a.js'), 'export const a=1;');
    await writeFile(path.join(directory, 'bad.woff'), 'font');
    await writeJson(path.join(directory, 'rmagent-modules.json'), {
      schemaVersion: 1,
      chunks: [{ path: 'a.js', modules: [] }],
      sourceAssets: [],
      assets: [],
      external: [],
    });
    await expect(auditAssets({ dir: directory, workdir: directory })).rejects.toThrow('Unadmitted output');
  });
});
describe('Android asset contract', () => {
  it('verifies the exact file set and detects tampering, absent licenses and empty outputs', async () => {
    const directory = await fixture();
    const names = ['index.html', 'assets/a.js', 'licenses/index.html', 'THIRD_PARTY_NOTICES.txt'];
    const records = [];
    for (const name of names) {
      await mkdir(path.dirname(path.join(directory, name)), { recursive: true });
      await writeFile(path.join(directory, name), 'probe');
      records.push({ path: name, size: 5, sha256: sha256(Buffer.from('probe')) });
    }
    await writeJson(path.join(directory, 'asset-manifest.json'), {
      schemaVersion: 1,
      algorithm: 'SHA-256',
      entry: 'index.html',
      files: records,
    });
    await writeJson(path.join(directory, 'renderer-build.json'), {
      schemaVersion: 1,
      entry: 'index.html',
      nodeEngineCompatible: true,
      tools: { node: 'v22.22.2', bun: '1.3.14' },
      assetManifestSha256: sha256(await readFile(path.join(directory, 'asset-manifest.json'))),
    });
    await expect(verifyOutput(directory)).resolves.toHaveProperty('entry', 'index.html');
    await writeFile(path.join(directory, 'assets/a.js'), 'wrong');
    await expect(verifyOutput(directory)).rejects.toThrow('Asset mismatch');
    await writeFile(path.join(directory, 'assets/a.js'), 'probe');
    await writeFile(path.join(directory, 'extra.js'), 'extra');
    await expect(verifyOutput(directory)).rejects.toThrow('file set');
  });
});

describe('published asset gate', () => {
  async function gateFixture(extra = {}) {
    const workdir = await fixture();
    const directory = path.join(workdir, 'out');
    await mkdir(directory);
    const files = { 'index.html': '<script src="./a.js"></script>', 'a.js': 'export const a=1;', ...extra };
    for (const [name, content] of Object.entries(files)) {
      await mkdir(path.dirname(path.join(directory, name)), { recursive: true });
      await writeFile(path.join(directory, name), content);
    }
    await writeFile(path.join(workdir, 'bun.lock'), '    "p": ["p@1.2.3", "", {}, "sha512-abc="],\n');
    await writeJson(path.join(directory, 'rmagent-modules.json'), {
      schemaVersion: 1,
      chunks: [{ path: 'a.js', modules: [] }],
      sourceAssets: [],
      assets: [{ path: 'rmagent-modules.json', names: [], originalFileNames: [] }],
      external: [],
    });
    return { directory, workdir };
  }
  it('fails on a font, names the file and the rule, and records the source-only formula decision', async () => {
    const { directory, workdir } = await gateFixture({ 'KaTeX_Main-Regular.woff2': 'font' });
    await expect(auditAssets({ dir: directory, workdir })).rejects.toThrow(
      'Unadmitted output file KaTeX_Main-Regular.woff2: rule unaccounted-font'
    );
    await rm(path.join(directory, 'KaTeX_Main-Regular.woff2'));
    const audit = await auditAssets({ dir: directory, workdir });
    expect(audit.fontDecision).toBe('source-only-formulas');
  });
  it('fails on unadmitted icon data that reached the emitted content', async () => {
    const { directory, workdir } = await gateFixture({ 'a.js': 'export const icons="catppuccinLatte";' });
    await expect(auditAssets({ dir: directory, workdir })).rejects.toThrow(
      'Unadmitted content in a.js: rule unadmitted-icon-set'
    );
  });
  it('fails on a copied static asset without provenance and admits an emitted one', async () => {
    const { directory, workdir } = await gateFixture({ 'theme.json': '{"name":"cat"}' });
    await expect(auditAssets({ dir: directory, workdir })).rejects.toThrow('rule unaccounted-static-asset');
    await rm(path.join(directory, 'theme.json'));
    await writeFile(path.join(directory, 'audit.json'), '{"emitted":true}');
    await writeJson(path.join(directory, 'rmagent-modules.json'), {
      schemaVersion: 1,
      chunks: [{ path: 'a.js', modules: [] }],
      sourceAssets: [],
      assets: [
        { path: 'rmagent-modules.json', names: [], originalFileNames: [] },
        { path: 'audit.json', names: [], originalFileNames: [] },
      ],
      external: [],
    });
    const audit = await auditAssets({ dir: directory, workdir });
    expect(audit.files).toContain('audit.json');
  });
});

describe('license closure', () => {
  async function closureFixture() {
    const directory = await fixture();
    const pin = JSON.parse(await readFile(new URL('../upstream.json', import.meta.url), 'utf8'));
    const policyFile = new URL('../assets/policy.json', import.meta.url);
    const files = {
      'index.html': '<script src="./assets/a.js"></script>',
      'assets/a.js': 'export const a=1;',
      NOTICE: `rmAgentMa renderer probe\n\nContains modified AionUi ${pin.commit}.\n`,
      'THIRD_PARTY_NOTICES.txt': '\nsample 1.0.0\n',
      'licenses/index.html': '<a href="../NOTICE">NOTICE</a>',
      'licenses/AionUi-Apache-2.0.txt': 'Apache License 2.0 text',
      'licenses/mit.txt': 'MIT grant text',
    };
    for (const [name, content] of Object.entries(files)) {
      await mkdir(path.dirname(path.join(directory, name)), { recursive: true });
      await writeFile(path.join(directory, name), content);
    }
    const digestOf = (content) => sha256(Buffer.from(content));
    const texts = {
      'licenses/AionUi-Apache-2.0.txt': digestOf(files['licenses/AionUi-Apache-2.0.txt']),
      'licenses/mit.txt': digestOf(files['licenses/mit.txt']),
    };
    await writeJson(path.join(directory, 'licenses/components.json'), {
      schemaVersion: 1,
      upstream: pin.commit,
      components: [
        { name: 'sample', version: '1.0.0', texts: [{ sourcePath: 'LICENSE', path: 'licenses/mit.txt', sha256: texts['licenses/mit.txt'], source: null }] },
      ],
    });
    const entry = (file, kind) => ({
      file,
      sha256: digestOf(files[file]),
      kind,
      components: ['sample@1.0.0'],
      licenses: ['licenses/AionUi-Apache-2.0.txt', 'licenses/mit.txt'],
      basis: 'fixture',
    });
    await writeJson(path.join(directory, 'licenses/coverage.json'), {
      schemaVersion: 1,
      upstream: { repository: pin.repository, commit: pin.commit },
      policySha256: sha256(await readFile(policyFile)),
      fontDecision: 'source-only-formulas',
      statements: {
        notice: 'NOTICE',
        thirdParty: 'THIRD_PARTY_NOTICES.txt',
        offlinePage: 'licenses/index.html',
        components: 'licenses/components.json',
      },
      texts,
      entries: [entry('index.html', 'renderer-content'), entry('assets/a.js', 'bundled-chunk')],
    });
    return directory;
  }
  it('maps every packaged file to a licence text and refuses each way that mapping can break', async () => {
    const directory = await closureFixture();
    await expect(verifyLicenseClosure(directory)).resolves.toHaveProperty('entries', 2);
    await writeFile(path.join(directory, 'assets/extra.js'), 'export const b=2;');
    await expect(verifyLicenseClosure(directory)).rejects.toThrow('closure-missing-entry');
    await rm(path.join(directory, 'assets/extra.js'));
    await writeFile(path.join(directory, 'assets/a.js'), 'export const a=2;');
    await expect(verifyLicenseClosure(directory)).rejects.toThrow('closure-file-changed');
    await writeFile(path.join(directory, 'assets/a.js'), 'export const a=1;');
    await writeFile(path.join(directory, 'licenses/orphan.txt'), 'unused grant');
    await expect(verifyLicenseClosure(directory)).rejects.toThrow('closure-orphan-license');
    await rm(path.join(directory, 'licenses/orphan.txt'));
    await writeFile(path.join(directory, 'assets/katex.woff2'), 'font');
    await expect(verifyLicenseClosure(directory)).rejects.toThrow('rule unaccounted-font');
    await rm(path.join(directory, 'assets/katex.woff2'));
    await rm(path.join(directory, 'NOTICE'));
    await expect(verifyLicenseClosure(directory)).rejects.toThrow();
    await writeFile(path.join(directory, 'NOTICE'), `Contains modified AionUi ${JSON.parse(await readFile(new URL('../upstream.json', import.meta.url), 'utf8')).commit}.\n`);
    await expect(verifyLicenseClosure(directory)).resolves.toHaveProperty('ok', true);
  });
  it('refuses a mapping from another policy, another upstream or another font decision', async () => {
    const directory = await closureFixture();
    const coveragePath = path.join(directory, 'licenses/coverage.json');
    const coverage = JSON.parse(await readFile(coveragePath, 'utf8'));
    await writeJson(coveragePath, { ...coverage, policySha256: 'a'.repeat(64) });
    await expect(verifyLicenseClosure(directory)).rejects.toThrow('closure-policy-mismatch');
    await writeJson(coveragePath, { ...coverage, upstream: { ...coverage.upstream, commit: 'b'.repeat(40) } });
    await expect(verifyLicenseClosure(directory)).rejects.toThrow('another upstream');
    await writeJson(coveragePath, { ...coverage, fontDecision: 'fonts-admitted' });
    await expect(verifyLicenseClosure(directory)).rejects.toThrow('closure-font-decision');
    await writeJson(coveragePath, coverage);
    await expect(verifyLicenseClosure(directory)).resolves.toHaveProperty('ok', true);
  });
  it('refuses a component inventory whose text or notice line is missing', async () => {
    const directory = await closureFixture();
    const componentsPath = path.join(directory, 'licenses/components.json');
    const components = JSON.parse(await readFile(componentsPath, 'utf8'));
    components.components[0].texts = [];
    await writeJson(componentsPath, components);
    await expect(verifyLicenseClosure(directory)).rejects.toThrow('closure-component-text-missing');
    components.components[0].texts = [{ sourcePath: 'LICENSE', path: 'licenses/mit.txt', sha256: null, source: null }];
    await writeJson(componentsPath, components);
    await writeFile(path.join(directory, 'THIRD_PARTY_NOTICES.txt'), '\nother 2.0.0\n');
    await expect(verifyLicenseClosure(directory)).rejects.toThrow('missing from THIRD_PARTY_NOTICES.txt');
  });
});
