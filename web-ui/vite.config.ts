import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  base: '/app/',
  plugins: [react()],
  build: { outDir: 'build/dist', emptyOutDir: true, sourcemap: false },
  test: { include: ['src/**/*.test.ts'] },
});
