import { Envelope, RobotState } from './generated/robolink';

export type TraceEvent = { command_id: string; robot: string; source: 'browser' | 'gateway' | 'device';
  stage: string; at: number; session: string; seq: string; pid?: number };
export const traceStages = [
  ['BROWSER_WS_SEND_QUEUED', 'browser', '网页提交 WebSocket 发送'],
  ['GATEWAY_WS_RECEIVED', 'gateway', '网关处理控制请求'],
  ['GATEWAY_TCP_WRITE_COMPLETED', 'gateway', '网关完成 TCP 本地写入'],
  ['DEVICE_COMMAND_RECEIVED', 'device', '机器人收到 TCP 指令'],
  ['DEVICE_ACK_WRITE_COMPLETED', 'device', '机器人完成 ACK 本地写入'],
  ['GATEWAY_ACK_RECEIVED', 'gateway', '网关收到设备 ACK'],
  ['DEVICE_RESULT_WRITE_COMPLETED', 'device', '机器人完成执行结果本地写入'],
  ['GATEWAY_RESULT_RECEIVED', 'gateway', '网关收到设备执行结果'],
  ['BROWSER_RESULT_RECEIVED', 'browser', '网页收到执行结果'],
  ['DEVICE_FINAL_TELEMETRY_WRITE_COMPLETED', 'device', '机器人写出关联的空闲遥测'],
  ['BROWSER_FINAL_TELEMETRY_RECEIVED', 'browser', '网页收到关联的空闲遥测'],
] as const;

export class TraceBook {
  entries: TraceEvent[] = [];
  add(event: TraceEvent) {
    if (!event.command_id || !traceStages.some(([stage, source]) => stage === event.stage && source === event.source)) return;
    if (this.entries.some(value => value.command_id === event.command_id && value.source === event.source
        && value.stage === event.stage && value.session === event.session && value.robot === event.robot)) return;
    this.entries.push(event);
    if (this.entries.length > 600) this.entries.shift();
  }
  observe(message: Envelope, direction: 'in' | 'out') {
    const payload = message.payload;
    if (!payload) return;
    const base = { robot: message.robotId, at: Date.now(), session: message.sessionId, seq: message.seq.toString() };
    if (direction === 'out' && payload.$case === 'commandRequest') this.add({ ...base,
      command_id: payload.value.commandId, source: 'browser', stage: 'BROWSER_WS_SEND_QUEUED' });
    if (direction === 'in' && payload.$case === 'commandResult') this.add({ ...base,
      command_id: payload.value.commandId, source: 'browser', stage: 'BROWSER_RESULT_RECEIVED' });
    if (direction === 'in' && payload.$case === 'telemetry' && payload.value.state === RobotState.ROBOT_STATE_IDLE
        && payload.value.relatedCommandId) this.add({ ...base, command_id: payload.value.relatedCommandId,
      source: 'browser', stage: 'BROWSER_FINAL_TELEMETRY_RECEIVED' });
    if (direction === 'in' && payload.$case === 'commandTrace') this.add({ ...base, at: Number(payload.value.observedAtMs),
      seq: payload.value.wireSeq.toString(), command_id: payload.value.commandId, source: 'gateway', stage: payload.value.stage });
  }
}
