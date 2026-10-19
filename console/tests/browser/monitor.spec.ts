import { test, expect } from '@playwright/test';
import { connect } from './helpers';
test.beforeEach(async ({ request }) => { expect((await request.post('http://127.0.0.1:19005/reset')).ok()).toBeTruthy(); });

test('real binary subscription, device selection and reload snapshot', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  await connect(page);
  await expect(page.getByTestId('device-total')).toHaveText('3台');
  await expect(page.getByTestId('device-RB-001')).toContainText('87%');
  await page.getByTestId('device-RB-002').click();
  await expect(page.getByTestId('position-x')).toHaveText('2.000');
  await page.reload();
  await expect(page.getByTestId('connection-state')).toHaveText('未连接');
  await connect(page);
  expect(errors).toEqual([]);
  await page.screenshot({ path: '../artifacts/browser/step-07-monitor.png', fullPage: true });
});

test('bad credentials rejected and never written to browser storage', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('网关地址').fill('ws://127.0.0.1:19001/control');
  await page.getByLabel('访问凭证').fill('invalid-fixture-token');
  await page.getByRole('button', { name: '连接网关' }).click();
  await expect(page.getByRole('status')).toContainText('认证失败');
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain('fixture-token');
  await expect(page.getByLabel('访问凭证')).toHaveValue('');
});

test('offline device differs from browser loss; gateway restart resynchronizes', async ({ page, request }) => {
  await connect(page);
  expect((await request.post('http://127.0.0.1:19005/device/RB-003/pause')).ok()).toBeTruthy();
  await expect(page.getByTestId('device-RB-003')).toContainText('离线');
  await expect(page.getByTestId('connection-state')).toHaveText('实时连接');
  await request.post('http://127.0.0.1:19005/device/RB-003/resume');
  await expect(page.getByTestId('online-count')).toHaveText('3台');
  const previous = await page.getByTestId('connection-state').getAttribute('data-session');
  await request.post('http://127.0.0.1:19005/restart');
  await expect(page.getByTestId('connection-state')).toHaveText('实时连接');
  await expect(page.getByTestId('connection-state')).not.toHaveAttribute('data-session', previous!);
  await expect(page.getByTestId('online-count')).toHaveText('3台');
  await expect(page.getByTestId('device-RB-003')).toContainText('空闲');
});

test('mobile layout remains within viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await connect(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.screenshot({ path: '../artifacts/browser/step-07-mobile.png', fullPage: true });
});
