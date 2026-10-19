import { Action, CommandPhase, Envelope } from './generated/robolink';

export const phaseNames: Record<number, string> = {
  [CommandPhase.COMMAND_PHASE_CREATED]: '等待发送', [CommandPhase.COMMAND_PHASE_SENT]: '已发送',
  [CommandPhase.COMMAND_PHASE_ACKED]: '设备已确认', [CommandPhase.COMMAND_PHASE_SUCCEEDED]: '执行成功',
  [CommandPhase.COMMAND_PHASE_FAILED]: '执行失败', [CommandPhase.COMMAND_PHASE_REJECTED]: '已拒绝',
  [CommandPhase.COMMAND_PHASE_ACK_TIMEOUT]: 'ACK 超时', [CommandPhase.COMMAND_PHASE_RESULT_TIMEOUT]: '结果超时',
  [CommandPhase.COMMAND_PHASE_UNKNOWN]: '结果未知', [CommandPhase.COMMAND_PHASE_INTERRUPTED]: '已中断',
};
export const actionNames: Record<number, string> = { [Action.ACTION_MOVE_FORWARD]: '前进',
  [Action.ACTION_TURN_LEFT]: '左转', [Action.ACTION_TURN_RIGHT]: '右转', [Action.ACTION_STOP]: '停止' };
const reasonNames: Record<string, string> = { CREATED: '网关已接收请求', SENT: '已交给设备连接发送', ACKED: '设备已接受指令',
  DEVICE_RESULT: '设备已返回执行结果', INVALID_PARAMETERS: '动作参数超出允许范围', OFFLINE: '设备当前离线',
  BUSY_OR_UNCERTAIN: '设备忙碌或上次结果不确定', DEVICE_NOT_IDLE: '设备尚未空闲', FORBIDDEN: '当前账号无权控制此设备',
  CAPACITY: '服务暂时繁忙，请稍后再试', ACK_TIMEOUT: '未及时收到设备确认，执行情况不确定',
  RESULT_TIMEOUT: '未及时收到最终结果，执行情况不确定', DEVICE_REJECTED: '设备拒绝了这条指令',
  SESSION_CHANGED_OR_DISCONNECTED: '设备连接发生变化，执行情况不确定', CONTROLLER_DISCONNECTED: '控制连接已断开',
  SEND_FAILED: '发送失败，无法确认设备是否收到', DEVICE_UNAVAILABLE: '设备连接暂不可用', SESSION_CHANGED: '设备会话已变化' };
export const reasonText = (reason: string) => reasonNames[reason] ?? reason;
export const terminal = (phase: CommandPhase) => phase >= CommandPhase.COMMAND_PHASE_SUCCEEDED;
export type CommandEntry = { id: string; robot: string; action: Action; phase: CommandPhase; reason: string;
  createdAt: number; ackAt?: number; resultAt?: number; ackAccepted?: boolean; outcome?: number };

export class CommandBook {
  entries: CommandEntry[] = [];
  uncertain = new Set<string>();
  add(id: string, robot: string, action: Action): boolean {
    if (this.entries.length >= 100) {
      let last = -1;
      for (let index = this.entries.length - 1; index >= 0; index--) {
        if (terminal(this.entries[index].phase)) { last = index; break; }
      }
      if (last === -1) return false;
      this.entries.splice(last, 1);
    }
    this.entries.unshift({ id, robot, action, phase: CommandPhase.COMMAND_PHASE_CREATED, reason: '', createdAt: Date.now() });
    return true;
  }
  pending(robot: string, stopOnly = false): boolean {
    return this.entries.some(entry => entry.robot === robot && !terminal(entry.phase) && (!stopOnly || entry.action === Action.ACTION_STOP));
  }
  disconnected() {
    for (const entry of this.entries) if (!terminal(entry.phase)) {
      entry.phase = CommandPhase.COMMAND_PHASE_UNKNOWN; entry.reason = '连接中断，无法确认最终执行结果';
      this.uncertain.add(entry.robot);
    }
  }
  apply(message: Envelope) {
    const payload = message.payload;
    if (!payload || !['commandStatus', 'commandAck', 'commandResult'].includes(payload.$case)) return;
    if (payload.$case !== 'commandStatus' && payload.$case !== 'commandAck' && payload.$case !== 'commandResult') return;
    const entry = this.entries.find(value => value.id === payload.value.commandId && value.robot === message.robotId);
    if (!entry || terminal(entry.phase)) return;
    if (payload.$case === 'commandAck') { entry.ackAt = Date.now(); entry.ackAccepted = payload.value.accepted; entry.reason = payload.value.reason; }
    else if (payload.$case === 'commandResult') { entry.resultAt = Date.now(); entry.outcome = payload.value.outcome; entry.reason = payload.value.reason; }
    else {
      const next = payload.value.phase;
      if (!(next in phaseNames) || (!terminal(next) && next < entry.phase)) return;
      entry.phase = next; entry.reason = payload.value.reason;
      if (next === CommandPhase.COMMAND_PHASE_REJECTED && payload.value.reason === 'BUSY_OR_UNCERTAIN') this.uncertain.add(entry.robot);
      if ([CommandPhase.COMMAND_PHASE_ACK_TIMEOUT, CommandPhase.COMMAND_PHASE_RESULT_TIMEOUT, CommandPhase.COMMAND_PHASE_UNKNOWN].includes(next)) this.uncertain.add(entry.robot);
      if (next === CommandPhase.COMMAND_PHASE_SUCCEEDED && entry.action === Action.ACTION_STOP) this.uncertain.delete(entry.robot);
    }
  }
}

export type MessageEntry = { id: number; at: number; direction: 'in' | 'out'; kind: string; robot: string;
  reference: string; bytes: number; details: string };
export class MessageBook {
  entries: MessageEntry[] = [];
  private id = 0;
  add(message: Envelope, bytes: number, direction: 'in' | 'out') {
    const payload = message.payload;
    if (!payload || ['register', 'controlAuthenticate', 'controlAuthResult'].includes(payload.$case)) return;
    const previous = payload.$case === 'telemetry' ? this.entries.find(entry => entry.kind === 'telemetry' && entry.robot === message.robotId) : undefined;
    if (payload.$case === 'telemetry') this.entries = this.entries.filter(entry => !(entry.kind === 'telemetry' && entry.robot === message.robotId));
    const value = payload.value;
    this.entries.unshift({ id: previous?.id ?? ++this.id, at: Date.now(), direction, kind: payload.$case, robot: message.robotId,
      reference: 'commandId' in value ? value.commandId : message.seq.toString(), bytes,
      details: JSON.stringify({ protocolVersion: message.protocolVersion, robotId: message.robotId,
        sessionId: message.sessionId, seq: message.seq, timestampMs: message.timestampMs, [payload.$case]: value },
        (_, item) => typeof item === 'bigint' ? item.toString() : item, 2) });
    this.entries.length = Math.min(120, this.entries.length);
  }
}
