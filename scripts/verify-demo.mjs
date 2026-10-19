import { chromium } from '../console/node_modules/playwright/index.mjs';
import { mkdir, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const directory = new URL('../artifacts/demo/', import.meta.url);
await mkdir(directory, { recursive: true });
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1080 } });
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto('http://127.0.0.1:5173');
  await page.getByRole('button', { name: '连接本地演示', exact: true }).click();
  await page.getByTestId('online-count').filter({ hasText: '3台' }).waitFor();
  await page.getByLabel('动作时长').fill('500');
  const before = await page.getByTestId('position-x').textContent();
  await page.getByRole('button', { name: '↑ 前进', exact: true }).click();
  await page.getByRole('tab', { name: '指令执行记录', exact: true }).click();
  await page.getByText('执行成功', { exact: true }).waitFor();
  await page.waitForFunction(previous => document.querySelector('[data-testid="position-x"]')?.textContent !== previous, before);
  const config = await (await page.request.get('http://127.0.0.1:5173/__demo/session')).json();
  assert.ok(config.token && !JSON.stringify(await page.evaluate(() => ({ ...localStorage, ...sessionStorage }))).includes(config.token));
  assert.equal((await page.request.get('http://127.0.0.1:5173/__demo/session', { headers: { Origin: 'https://outside.invalid' } })).status(), 403);
  assert.equal((await page.request.get('http://127.0.0.1:5173/__demo/session', { headers: { 'Sec-Fetch-Site': 'cross-site' } })).status(), 403);
  assert.deepEqual(errors, []);
  await page.screenshot({ path: new URL('live-demo.png', directory).pathname.replace(/^\/(\w:)/, '$1'), fullPage: true });
  await writeFile(new URL('verification.json', directory), JSON.stringify({ status: 'PASS', recorded_at_utc: new Date().toISOString(),
    checks: ['real start-demo supervisor', 'local demo authentication without copying token', 'three robots online',
      'browser motion succeeds and telemetry position changes', 'no credential in browser storage',
      'cross-origin and cross-site demo credential requests rejected', 'no browser runtime errors'],
    scope: 'Local startup and browser smoke, not performance or hardware test' }, null, 2));
  console.log('PASS: live local demo, browser control and credential handling');
} finally { await browser.close(); }
