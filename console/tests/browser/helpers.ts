import { expect, type Page } from '@playwright/test';

export async function connect(page: Page) {
  await page.goto('/');
  await page.getByLabel('网关地址').fill('ws://127.0.0.1:19001/control');
  await page.getByLabel('访问凭证').fill('public-browser-fixture-only');
  await page.getByRole('button', { name: '连接网关' }).click();
  await expect(page.getByTestId('connection-state')).toHaveText('实时连接');
  await expect(page.getByTestId('online-count')).toHaveText('3台');
}
