import { chromium, expect } from '../console/node_modules/@playwright/test/index.mjs';
import { mkdir, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const directory = new URL('../artifacts/proof/', import.meta.url);
await mkdir(directory, { recursive: true });
const filename = name => new URL(name, directory).pathname.replace(/^\/(\w:)/, '$1');
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto('http://127.0.0.1:5173');
  await page.getByRole('button', { name: '连接本地演示', exact: true }).click();
  await expect(page.getByTestId('process-count')).toHaveText('3 个独立进程');
  await expect(page.getByTestId('online-count')).toHaveText('3台');
  const evidence = async () => await (await page.request.get('http://127.0.0.1:5173/__demo/evidence')).json();
  let before = await evidence();
  const pids = before.robots.map(robot => robot.pid);
  assert.equal(new Set(pids).size, 3);
  for (const robot of before.robots) {
    assert.equal(robot.pid, robot.state.pid);
    process.kill(robot.pid, 0); // OS existence probe, signal 0 does not terminate.
    await expect(page.getByTestId(`pid-${robot.id}`)).toHaveText(String(robot.pid));
    await expect(page.getByTestId(`match-${robot.id}`)).toHaveText('已匹配');
  }
  const peers = await Promise.all(before.robots.map(robot => page.getByTestId(`tcp-${robot.id}`).textContent()));
  assert.equal(new Set(peers).size, 3); assert.ok(peers.every(peer => peer.includes('127.0.0.1:')));
  await page.getByLabel('动作时长').fill('500');
  await page.getByRole('button', { name: '↑ 前进', exact: true }).click();
  await expect(page.getByText('执行成功', { exact: true })).toBeVisible();
  const stages = ['BROWSER_WS_SEND_QUEUED', 'GATEWAY_WS_RECEIVED', 'GATEWAY_TCP_WRITE_COMPLETED',
    'DEVICE_COMMAND_RECEIVED', 'DEVICE_ACK_WRITE_COMPLETED', 'GATEWAY_ACK_RECEIVED',
    'DEVICE_RESULT_WRITE_COMPLETED', 'GATEWAY_RESULT_RECEIVED', 'BROWSER_RESULT_RECEIVED',
    'DEVICE_FINAL_TELEMETRY_WRITE_COMPLETED', 'BROWSER_FINAL_TELEMETRY_RECEIVED'];
  for (const stage of stages) await expect(page.getByTestId(`trace-${stage}`)).toHaveClass(/observed/);
  const firstCommand = await page.getByLabel('链路指令').inputValue();
  await page.getByRole('region', { name: '指令通信闭环证据' }).screenshot({ path: filename('trace.png') });
  before = await evidence();
  const original = before.robots.find(robot => robot.id === 'RB-002');
  await page.getByTestId('device-RB-002').click();
  await page.getByLabel('动作时长').fill('3000');
  await page.getByRole('button', { name: '↑ 前进', exact: true }).click();
  await expect(page.getByText('设备已确认', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '停止 RB-002 进程', exact: true }).click();
  await expect(page.getByTestId('process-count')).toHaveText('2 个独立进程');
  await expect(page.getByTestId('online-count')).toHaveText('2台');
  await expect(page.getByTestId('device-RB-002')).toContainText('离线');
  await expect(page.getByText('结果未知', { exact: true })).toBeVisible();
  assert.throws(() => process.kill(original.pid, 0));
  const stopped = await evidence();
  const stoppedWorker = stopped.robots.find(robot => robot.id === 'RB-002');
  await page.waitForTimeout(600);
  const during = await evidence();
  for (const id of ['RB-001', 'RB-003']) {
    const old = before.robots.find(robot => robot.id === id), current = during.robots.find(robot => robot.id === id);
    assert.equal(current.pid, old.pid); assert.equal(current.state.session_id, old.state.session_id);
    assert.ok(current.state.sent_messages > old.state.sent_messages);
  }
  await page.getByRole('button', { name: '启动 RB-002 进程', exact: true }).click();
  await expect(page.getByTestId('process-count')).toHaveText('3 个独立进程');
  await expect(page.getByTestId('online-count')).toHaveText('3台');
  await expect(page.getByTestId('match-RB-002')).toHaveText('已匹配');
  const after = await evidence(), restarted = after.robots.find(robot => robot.id === 'RB-002');
  assert.notEqual(restarted.pid, original.pid); assert.notEqual(restarted.state.session_id, original.state.session_id);
  process.kill(restarted.pid, 0);
  await expect(page.getByTestId('position-x')).toHaveText(stoppedWorker.state.pose.x.toFixed(3));
  await page.waitForTimeout(250);
  await expect(page.getByTestId('position-x')).toHaveText(stoppedWorker.state.pose.x.toFixed(3));
  await expect(page.locator('tbody tr')).toHaveCount(2);
  await expect(page.getByTestId('trace-DEVICE_RESULT_WRITE_COMPLETED')).not.toHaveClass(/observed/);
  await expect(page.getByTestId('trace-BROWSER_RESULT_RECEIVED')).not.toHaveClass(/observed/);
  assert.equal((await page.request.post('http://127.0.0.1:5173/__demo/robots/RB-001/stop',
    { headers: { Origin: 'https://outside.invalid' } })).status(), 403);
  assert.equal((await page.request.post('http://127.0.0.1:5173/__demo/robots/RB-001/stop')).status(), 403);
  assert.equal((await page.request.get('http://127.0.0.1:19006/evidence')).status(), 404);
  await page.getByLabel('链路指令').selectOption(firstCommand);
  await page.getByRole('region', { name: '设备进程与连接证据' }).screenshot({ path: filename('processes.png') });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await page.getByRole('region', { name: '设备进程与连接证据' }).screenshot({ path: filename('mobile.png') });
  assert.deepEqual(errors, []);
  await writeFile(filename('report.json'), JSON.stringify({ status: 'PASS', recorded_at_utc: new Date().toISOString(),
    checks: ['3 distinct supervisor PIDs equal worker self-reports and pass OS existence probes',
      '3 distinct gateway-observed TCP peers and sessions match each worker', '11 independently observed command stages',
      'stopping RB-002 exits its OS process and makes only that robot offline',
      'other 2 PIDs/sessions remain unchanged and telemetry continues', 'restart changes PID and session',
      'stopped pose preserved, old motion never replayed', 'interrupted command trace never fabricates missing result',
      'cross-origin and uncredentialed control calls rejected', 'desktop/mobile views and no page runtime errors'],
    process_evidence: { initial_pids: pids, stopped_pid: original.pid, restarted_pid: restarted.pid },
    scope: 'Local OS-process and real WebSocket/TCP observations; not physical hardware or latency/load benchmark' }, null, 2));
  console.log('PASS: 3 actual robot processes, 11 observed stages, isolated stop/restart and browser proof panel');
} finally { await browser.close(); }
