import path from 'node:path';
import { fileURLToPath } from 'node:url';

export default {
  test: {
    name: 'renderer-build-tools',
    globals: true,
    environment: 'node',
    include: [path.join(path.dirname(fileURLToPath(import.meta.url)), '*.test.mjs')],
  },
};
