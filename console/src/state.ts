import { Envelope, RobotState, type Telemetry } from './generated/robolink';

export type Device = { id: string; session: string; online: boolean; seq: bigint;
  telemetry?: Telemetry; receivedAt: number; remoteAddress?: string; receivedMessages?: bigint;
  sentMessages?: bigint; connectedAt?: bigint; lastHeartbeatAt?: bigint };

export function validTelemetry(value: Telemetry): boolean {
  return Number.isInteger(value.batteryPercent) && value.batteryPercent >= 0 && value.batteryPercent <= 100
    && !!value.pose && [value.pose.xM, value.pose.yM, value.pose.yawRad].every(Number.isFinite)
    && [RobotState.ROBOT_STATE_IDLE, RobotState.ROBOT_STATE_MOVING, RobotState.ROBOT_STATE_ERROR].includes(value.state);
}

export class DeviceState {
  devices = new Map<string, Device>();
  apply(message: Envelope, now = Date.now()): void {
    if (message.protocolVersion !== 1 || !message.payload) throw new Error('不支持的消息格式');
    const payload = message.payload;
    if (payload.$case === 'snapshot') {
      if (payload.value.robots.length > 64) throw new Error('设备数量超出范围');
      const next = new Map<string, Device>();
      for (const robot of payload.value.robots) {
        if (robot.latestTelemetry && !validTelemetry(robot.latestTelemetry)) throw new Error('设备状态格式无效');
        next.set(robot.robotId, { id: robot.robotId, session: robot.sessionId, online: robot.online,
          seq: robot.telemetrySeq, telemetry: robot.latestTelemetry,
          receivedAt: robot.latestTelemetry ? Math.min(now, Number(robot.telemetryObservedAtMs || robot.lastSeenAtMs)) : 0,
          remoteAddress: robot.remoteAddress, receivedMessages: robot.receivedMessages, sentMessages: robot.sentMessages,
          connectedAt: robot.connectedAtMs, lastHeartbeatAt: robot.lastHeartbeatAtMs });
      }
      this.devices = next;
    } else if (payload.$case === 'deviceStatusChanged') {
      const previous = this.devices.get(message.robotId);
      if (!previous) return;
      if (payload.value.online) {
        this.devices.set(message.robotId, { id: message.robotId, session: message.sessionId,
          online: true, seq: 0n, receivedAt: 0 });
      } else if (previous.session === message.sessionId) previous.online = false;
    } else if (payload.$case === 'telemetry') {
      const previous = this.devices.get(message.robotId);
      if (!previous?.online || previous.session !== message.sessionId || message.seq <= previous.seq) return;
      if (!validTelemetry(payload.value)) throw new Error('遥测字段无效');
      this.devices.set(message.robotId, { ...previous, seq: message.seq, telemetry: payload.value, receivedAt: now });
    }
  }
}

export function deviceHealth(device: Device, connected: boolean, now = Date.now()): string {
  if (!connected) return '连接中断';
  if (!device.online) return '离线';
  if (!device.telemetry || now - device.receivedAt > 3000) return '遥测陈旧';
  return device.telemetry.state === RobotState.ROBOT_STATE_MOVING ? '运动中'
    : device.telemetry.state === RobotState.ROBOT_STATE_ERROR ? '设备异常' : '空闲';
}
