import { Envelope } from './generated/robolink';
import { DeviceState, type Device } from './state';

export type Phase = 'disconnected' | 'connecting' | 'authenticating' | 'synchronizing' | 'ready' | 'retrying' | 'error';
export type View = { phase: Phase; message: string; devices: Device[]; rate: number; session: string };
export const phaseLabel: Record<Phase, string> = { disconnected: '未连接', connecting: '正在连接', authenticating: '正在认证',
  synchronizing: '正在同步', ready: '实时连接', retrying: '正在重连', error: '连接失败' };

export class ControlConnection {
  state = new DeviceState();
  phase: Phase = 'disconnected';
  message = '';
  session = '';
  private socket?: WebSocket;
  private retry?: ReturnType<typeof setTimeout>;
  private deadline?: ReturnType<typeof setTimeout>;
  private flush?: ReturnType<typeof setTimeout>;
  private maintenance: ReturnType<typeof setInterval>;
  private attempt = 0;
  private credentials?: { url: string; id: string; token: string };
  private stopped = true;
  private rateBuckets = new Map<number, number>();
  constructor(private change: (value: View) => void) {
    this.maintenance = setInterval(() => this.publish(), 500);
  }
  private publish() {
    const second = Math.floor(Date.now() / 1000);
    for (const key of this.rateBuckets.keys()) if (key < second - 4) this.rateBuckets.delete(key);
    this.change({ phase: this.phase, message: this.message, devices: [...this.state.devices.values()],
      rate: [...this.rateBuckets.values()].reduce((a, b) => a + b, 0) / 5, session: this.session });
  }
  private changed() {
    if (!this.flush) this.flush = setTimeout(() => { this.flush = undefined; this.publish(); }, 100);
  }
  connect(url: string, id: string, token: string) {
    this.disconnect();
    try {
      const target = new URL(url);
      if (!['ws:', 'wss:'].includes(target.protocol) || target.search || target.hash || target.username || target.password
          || !id.trim() || !token.trim()) throw new Error();
    } catch { this.phase = 'error'; this.message = '请检查地址、账号和访问凭证'; this.publish(); return; }
    this.credentials = { url, id: id.trim(), token };
    this.stopped = false;
    this.attempt = 0;
    this.open();
  }
  private open() {
    const config = this.credentials;
    if (!config || this.stopped) return;
    this.phase = this.attempt ? 'retrying' : 'connecting';
    this.message = '';
    this.publish();
    const socket = new WebSocket(config.url);
    this.socket = socket;
    socket.binaryType = 'arraybuffer';
    this.deadline = setTimeout(() => { if (this.socket === socket) socket.close(); }, 7000);
    socket.onopen = () => {
      if (this.socket !== socket) return;
      this.phase = 'authenticating';
      this.send(Envelope.fromPartial({ protocolVersion: 1, payload: { $case: 'controlAuthenticate',
        value: { controllerId: config.id, credential: config.token } } }));
      this.publish();
    };
    socket.onmessage = (event: MessageEvent) => {
      if (this.socket !== socket) return;
      try {
        if (!(event.data instanceof ArrayBuffer) || event.data.byteLength > 65536) throw new Error();
        const message = Envelope.decode(new Uint8Array(event.data));
        if (message.protocolVersion !== 1 || !message.payload) throw new Error();
        if (message.payload.$case === 'controlAuthResult') {
          if (!message.payload.value.accepted) {
            this.stopped = true;
            this.credentials = undefined;
            this.phase = 'error'; this.message = '认证失败，请检查账号与凭证'; socket.close(); this.publish(); return;
          }
          this.session = message.payload.value.controlSessionId;
          this.phase = 'synchronizing';
          this.send(Envelope.fromPartial({ protocolVersion: 1, payload: { $case: 'subscribe', value: { robotIds: [] } } }));
        } else {
          if (!['synchronizing', 'ready'].includes(this.phase)) throw new Error();
          this.state.apply(message);
          if (message.payload.$case === 'snapshot') {
            this.phase = 'ready'; this.attempt = 0; clearTimeout(this.deadline);
          }
          if (message.payload.$case === 'telemetry') {
            const second = Math.floor(Date.now() / 1000);
            this.rateBuckets.set(second, (this.rateBuckets.get(second) ?? 0) + 1);
          }
        }
        this.changed();
      } catch { this.stopped = true; this.phase = 'error'; this.message = '收到无效数据，请重新连接'; socket.close(); this.publish(); }
    };
    socket.onerror = () => { /* close event owns retry; browser errors carry no protocol details. */ };
    socket.onclose = () => {
      if (this.socket !== socket) return;
      clearTimeout(this.deadline);
      this.socket = undefined;
      if (!this.stopped) {
        this.phase = 'retrying'; this.message = '连接中断，正在重新认证并同步设备';
        const delay = Math.min(5000, 250 * 2 ** Math.min(this.attempt++, 5)) * (0.5 + Math.random() / 2);
        this.retry = setTimeout(() => this.open(), delay);
      }
      this.publish();
    };
  }
  send(message: Envelope): boolean {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN || this.socket.bufferedAmount > 65536) return false;
    this.socket.send(Envelope.encode(message).finish());
    return true;
  }
  disconnect() {
    this.stopped = true;
    clearTimeout(this.retry); clearTimeout(this.deadline); clearTimeout(this.flush); this.flush = undefined;
    const socket = this.socket; this.socket = undefined; socket?.close();
    this.credentials = undefined; this.session = ''; this.phase = 'disconnected'; this.message = '';
    this.publish();
  }
  dispose() { this.disconnect(); clearInterval(this.maintenance); }
}
