import {defineConfig} from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts', 'test/**/*.test.tsx'],
    exclude: ['dist/**', 'node_modules/**'],
    testTimeout: 15_000,
  },
});
