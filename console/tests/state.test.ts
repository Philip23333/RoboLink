import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Envelope, RobotState } from '../src/generated/robolink';
import { DeviceState, deviceHealth } from '../src/state';

const telemetry = { batteryPercent: 87, state: RobotState.ROBOT_STATE_IDLE, pose: { xM: 0, yM: 0, yawRad: 0 } };
function snapshot() { return Envelope.fromPartial({ protocolVersion: 1, payload: { $case: 'snapshot', value: {
  robots: [{ robotId: 'RB-001', online: true, sessionId: 'new', telemetrySeq: 9007199254740993n,
    latestTelemetry: telemetry, lastSeenAtMs: 1000n }] } } }); }
test('session and bigint sequence reject old data and reset with new session', () => {
  const state = new DeviceState(); state.apply(snapshot(), 1000);
  const message = Envelope.fromPartial({ protocolVersion: 1, robotId: 'RB-001', sessionId: 'old', seq: 9999999999999999n,
    payload: { $case: 'telemetry', value: { ...telemetry, batteryPercent: 20 } } });
  state.apply(message, 1001); assert.equal(state.devices.get('RB-001')?.telemetry?.batteryPercent, 87);
  message.sessionId = 'new'; message.seq = 9007199254740992n; state.apply(message, 1001);
  assert.equal(state.devices.get('RB-001')?.seq, 9007199254740993n);
  state.apply(Envelope.fromPartial({ protocolVersion: 1, robotId: 'RB-001', sessionId: 'next',
    payload: { $case: 'deviceStatusChanged', value: { online: true } } }));
  message.sessionId = 'next'; message.seq = 1n; state.apply(message, 1002);
  assert.equal(state.devices.get('RB-001')?.seq, 1n);
});
test('old offline event cannot remove new session', () => {
  const state = new DeviceState(); state.apply(snapshot());
  state.apply(Envelope.fromPartial({ protocolVersion: 1, robotId: 'RB-001', sessionId: 'old',
    payload: { $case: 'deviceStatusChanged', value: { online: false } } }));
  assert.equal(state.devices.get('RB-001')?.online, true);
});
test('connection loss, offline and stale telemetry are distinct', () => {
  const state = new DeviceState(); state.apply(snapshot(), 1000); const device = state.devices.get('RB-001')!;
  assert.equal(deviceHealth(device, false, 5000), '连接中断');
  assert.equal(deviceHealth(device, true, 5000), '遥测陈旧');
  device.online = false; assert.equal(deviceHealth(device, true, 5000), '离线');
});
test('invalid data and unsupported protocol cannot replace state', () => {
  const state = new DeviceState(); state.apply(snapshot());
  const message = Envelope.fromPartial({ protocolVersion: 1, robotId: 'RB-001', sessionId: 'new', seq: 9007199254740994n,
    payload: { $case: 'telemetry', value: { ...telemetry, pose: { xM: NaN, yM: 0, yawRad: 0 } } } });
  assert.throws(() => state.apply(message)); message.protocolVersion = 2; assert.throws(() => state.apply(message));
  assert.equal(state.devices.get('RB-001')?.telemetry?.pose?.xM, 0);
});
