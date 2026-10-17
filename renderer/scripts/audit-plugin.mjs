import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import path from 'node:path';

/** @returns {import('vite').Plugin} */
export function rendererAudit() {
  const sources = new Map();
  return {
    name: 'rmagent-renderer-audit',
    enforce: 'pre',
    transform(_code, id) {
      const file = id.split('?')[0];
      if (path.isAbsolute(file) && /\.(?:svg|png|jpe?g|webp|gif|ico|woff2?|ttf|otf|wasm|json)$/i.test(file)) {
        const bytes = readFileSync(file);
        sources.set(file, {
          path: file,
          sha256: createHash('sha256').update(bytes).digest('hex'),
          size: bytes.length,
        });
      }
      return null;
    },
    generateBundle(_options, bundle) {
      const chunks = [];
      const assets = [];
      for (const [fileName, output] of Object.entries(bundle)) {
        if (output.type === 'chunk') {
          chunks.push({
            path: fileName,
            imports: output.imports,
            dynamicImports: output.dynamicImports,
            modules: Object.entries(output.modules).map(([id, info]) => {
              const file = id.split('?')[0];
              let sourceSha256 = null;
              if (path.isAbsolute(file) && !file.includes('\0')) {
                try {
                  sourceSha256 = createHash('sha256').update(readFileSync(file)).digest('hex');
                } catch (error) {
                  if (error.code !== 'ENOENT' && error.code !== 'EISDIR') throw error;
                }
              }
              return {
                id,
                renderedLength: info.renderedLength,
                originalLength: info.originalLength,
                sourceSha256,
                importers: this.getModuleInfo(id)?.importers ?? [],
                dynamicImporters: this.getModuleInfo(id)?.dynamicImporters ?? [],
              };
            }),
          });
        } else {
          assets.push({
            path: fileName,
            names: output.names ?? [output.name].filter(Boolean),
            originalFileNames: output.originalFileNames ?? [],
          });
        }
      }
      const external = [...this.getModuleIds()].filter((id) => this.getModuleInfo(id)?.isExternal);
      this.emitFile({
        type: 'asset',
        fileName: 'rmagent-modules.json',
        source: JSON.stringify(
          { schemaVersion: 1, chunks, assets, sourceAssets: [...sources.values()], external },
          null,
          2
        ),
      });
    },
  };
}
