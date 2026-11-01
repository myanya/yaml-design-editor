import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './browser-tests', outputDir: './build/browser-results', workers: 1, timeout: 30_000,
  reporter: 'line', use: { browserName: 'chromium', headless: true, baseURL: process.env.YDE_WEB_URL,
    trace: 'off', screenshot: 'off', video: 'off', locale: 'ja-JP', viewport: { width: 1280, height: 900 } },
});
