import { test, expect } from '@playwright/test';
import { connect } from './helpers';

test.beforeEach(async ({ request }) => { expect((await request.post('http://127.0.0.1:19005/reset')).ok()).toBeTruthy(); });

test('forward ACK/result and telemetry move only selected robot; turns and map selection', async ({ page }) => {
  await connect(page);
  const original = await page.getByTestId('map-RB-001').getAttribute('transform');
  await page.getByLabel('动作时长').fill('500');
  await page.getByRole('button', { name: '↑ 前进', exact: true }).click();
  await expect(page.getByText('设备已确认', { exact: true })).toBeVisible();
  await expect(page.getByText('执行成功', { exact: true })).toBeVisible();
  await expect(page.getByTestId('position-x')).toHaveText('0.100');
  await expect(page.getByTestId('map-RB-001')).not.toHaveAttribute('transform', original!);
  await page.getByTestId('map-RB-002').click();
  await expect(page.getByTestId('position-x')).toHaveText('2.000');
  await page.getByRole('button', { name: '↶ 左转', exact: true }).click();
  await expect(page.getByTestId('yaw')).toHaveText('0.250');
  await expect(page.getByRole('button', { name: '右转 ↷', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: '右转 ↷', exact: true }).click();
  await expect(page.getByTestId('yaw')).toHaveText('0.000');
  await page.getByRole('button', { name: '放大地图' }).click();
  await page.getByRole('button', { name: '缩小地图' }).click();
  await page.getByRole('button', { name: '重置视图', exact: true }).click();
  await page.screenshot({ path: '../artifacts/browser/step-08-control.png', fullPage: true });
});

test('STOP interrupts a running motion and duplicate clicks cannot start a second motion', async ({ page }) => {
  await connect(page);
  await page.getByLabel('动作时长').fill('3000');
  await page.getByRole('button', { name: '↑ 前进', exact: true }).click();
  await expect(page.getByRole('button', { name: '↑ 前进', exact: true })).toBeDisabled();
  await expect(page.getByText('设备已确认', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '■ 停止', exact: true }).click();
  await expect(page.getByText('已中断', { exact: true })).toBeVisible();
  await expect(page.getByText('执行成功', { exact: true })).toBeVisible();
  const stopped = await page.getByTestId('position-x').textContent();
  await page.waitForTimeout(250);
  await expect(page.getByTestId('position-x')).toHaveText(stopped!);
});

test('invalid parameters and offline targets cannot send motion', async ({ page, request }) => {
  await connect(page);
  await page.getByLabel('运动速度').fill('0.6');
  await expect(page.getByRole('button', { name: '↑ 前进', exact: true })).toBeDisabled();
  await expect(page.getByText('速度需在', { exact: false })).toBeVisible();
  await page.getByLabel('运动速度').fill('0.2');
  await request.post('http://127.0.0.1:19005/device/RB-001/pause');
  await expect(page.getByTestId('device-RB-001')).toContainText('离线');
  await expect(page.getByRole('button', { name: '↑ 前进', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '■ 停止', exact: true })).toBeDisabled();
});

test('missing ACK and missing result are explicit timeouts; STOP recovers', async ({ page, request }) => {
  await connect(page);
  await request.post('http://127.0.0.1:19005/device/RB-001/drop-ack');
  await page.getByLabel('动作时长').fill('3000');
  await page.getByRole('button', { name: '↑ 前进', exact: true }).click();
  await expect(page.getByText('ACK 超时', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '↑ 前进', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '■ 停止', exact: true }).click();
  await expect(page.getByText('执行成功', { exact: true })).toBeVisible();
  await request.post('http://127.0.0.1:19005/device/RB-001/drop-result');
  await page.getByLabel('动作时长').fill('200');
  await expect(page.getByRole('button', { name: '↑ 前进', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: '↑ 前进', exact: true }).click();
  await expect(page.getByText('结果超时', { exact: true })).toBeVisible();
});

test('browser disconnect marks pending UNKNOWN and never replays motion on reconnect', async ({ page }) => {
  await connect(page);
  await page.getByLabel('动作时长').fill('3000');
  await page.getByRole('button', { name: '↑ 前进', exact: true }).click();
  await expect(page.getByText('设备已确认', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '断开', exact: true }).click();
  await expect(page.getByText('结果未知', { exact: true })).toBeVisible();
  await page.getByLabel('访问凭证').fill('public-browser-fixture-only');
  await page.getByRole('button', { name: '连接网关' }).click();
  await expect(page.getByTestId('connection-state')).toHaveText('实时连接');
  await page.getByRole('button', { name: '■ 停止', exact: true }).click();
  await expect(page.getByText('执行成功', { exact: true })).toBeVisible();
  await expect(page.locator('tbody tr')).toHaveCount(2);
  await expect(page.getByText('结果未知', { exact: true })).toBeVisible();
});

test('message fields decode without credentials; mobile control layout fits', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await connect(page);
  await page.getByRole('button', { name: '↑ 前进', exact: true }).click();
  await expect(page.getByText('执行成功', { exact: true })).toBeVisible();
  const panel = page.getByTestId('message-list');
  expect(await panel.textContent()).not.toContain('public-browser-fixture-only');
  expect(await panel.textContent()).not.toContain('controlAuthenticate');
  const telemetry = panel.locator('details').filter({ hasText: 'telemetry' }).first();
  await telemetry.locator('summary').click();
  await expect(telemetry.locator('pre')).toBeVisible();
  await expect(telemetry.locator('pre')).toContainText('batteryPercent');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.screenshot({ path: '../artifacts/browser/step-08-mobile.png', fullPage: true });
});
