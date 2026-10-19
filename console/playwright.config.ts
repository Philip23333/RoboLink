import { defineConfig } from '@playwright/test';
export default defineConfig({ testDir: './tests/browser', fullyParallel: false, workers: 1, timeout: 30000,
  expect: { timeout: 10000 }, use: { baseURL: 'http://127.0.0.1:5173', browserName: 'chromium',
    viewport: { width: 1440, height: 1000 }, screenshot: 'only-on-failure', trace: 'off' },
  reporter: [['list'], ['json', { outputFile: '../artifacts/browser/results.json' }]],
  webServer: [
    { command: 'npm run dev', url: 'http://127.0.0.1:5173', reuseExistingServer: false, timeout: 30000 },
    { command: '"../.venv/Scripts/python.exe" ../scripts/browser-stack.py', url: 'http://127.0.0.1:19005/ready',
      reuseExistingServer: false, timeout: 30000 },
  ],
});
