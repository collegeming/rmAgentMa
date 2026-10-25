/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const roots = ['common/host', 'renderer/host'].map((dir) =>
  fileURLToPath(new URL(`../../packages/desktop/src/${dir}`, import.meta.url))
);

async function sources(dir: string): Promise<string[]> {
  const entries = await readdir(dir, { withFileTypes: true });
  const nested = await Promise.all(
    entries.map((entry) => {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) return sources(full);
      return Promise.resolve(entry.isFile() ? [full] : []);
    })
  );
  return nested.flat();
}

/**
 * A source that is not plain UTF-8 text is read as a binary by git and refused by review tools, which is how
 * one raw separator byte in a group key went unnoticed. The seam sources stay text.
 */
describe('host seam sources', () => {
  it('stay plain UTF-8 text without a raw NUL byte', async () => {
    const files = (await Promise.all(roots.map(sources))).flat();
    expect(files.length).toBeGreaterThan(0);
    const contents = await Promise.all(files.map((file) => readFile(file)));
    contents.forEach((bytes, index) => {
      expect(bytes.includes(0), `${files[index]} holds a NUL byte; write the escape instead`).toBe(false);
      expect(() => new TextDecoder('utf-8', { fatal: true }).decode(bytes)).not.toThrow();
    });
  });
});
