import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './browser-tests', outputDir: `./build/browser-results/${process.env.YDE_WEB_SCENARIO ?? 'manual'}`,
  workers: 1, timeout: 90_000, expect: { timeout: 15_000 },
  reporter: 'line', use: { browserName: 'chromium', headless: true, baseURL: process.env.YDE_WEB_URL, actionTimeout: 15_000,
    trace: 'off', screenshot: 'off', video: 'off', locale: 'ja-JP', viewport: { width: 1280, height: 900 } },
});
