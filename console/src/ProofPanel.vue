<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import type { Device } from './state';
import { actionNames, type CommandEntry } from './commands';
import { TraceBook, traceStages, type TraceEvent } from './traces';

const props = defineProps<{ showProcess: boolean; showTrace: boolean; devices: Device[]; connected: boolean; available: boolean;
  commands: CommandEntry[]; traces: TraceEvent[]; selectedCommand: string }>();
const emit = defineEmits<{ 'update:selectedCommand': [id: string] }>();
type Worker = { id: string; running: boolean; pid: number | null; last_pid: number | null; started_at_ms: number;
  updated_at_ms: number; last_session: string; error: string; state: { session_id?: string; state?: string; pid?: number;
    sent_messages?: number; received_messages?: number } };
const runtime = ref<{ observed_at_ms: number; robots: Worker[]; events: TraceEvent[] }>();
const polling = ref(false);
const error = ref('');
const busy = ref('');
let disposed = false;
let timer: ReturnType<typeof setTimeout>;
const deviceEvents = new TraceBook();
const eventRevision = ref(0);
const sourceNames = { browser: '当前网页', gateway: 'Java 网关', device: '机器人进程' };
async function poll() {
  try {
    if (props.available) {
      const response = await fetch('/__demo/evidence', { cache: 'no-store', signal: AbortSignal.timeout(2500) });
      if (!response.ok) throw new Error();
      const value = await response.json();
      if (!Array.isArray(value.robots) || value.robots.length !== 3 || !Array.isArray(value.events)) throw new Error();
      runtime.value = value; polling.value = true;
      for (const event of value.events) deviceEvents.add(event);
      eventRevision.value++;
    }
  } catch { polling.value = false; }
  if (!disposed) timer = setTimeout(poll, 500);
}
async function operate(id: string, operation: 'start' | 'stop') {
  if (busy.value || !props.available || !polling.value) return;
  busy.value = id; error.value = '';
  try {
    const response = await fetch(`/__demo/robots/${id}/${operation}`, { method: 'POST' });
    if (!response.ok) throw new Error();
    runtime.value = await response.json();
  } catch { error.value = '设备进程操作未完成，请检查本地演示服务'; }
  finally { busy.value = ''; }
}
const count = computed(() => runtime.value?.robots.filter(worker => worker.running).length ?? 0);
const current = computed(() => props.commands.find(command => command.id === props.selectedCommand) ?? props.commands[0]);
const rows = computed(() => {
  eventRevision.value;
  const events = [...props.traces, ...deviceEvents.entries].filter(event => event.command_id === current.value?.id
    && event.robot === current.value?.robot);
  return traceStages.map(([stage, source, label]) => ({ stage, source, label, event: events.find(event => event.stage === stage && event.source === source) }));
});
function gateway(id: string) { return props.devices.find(device => device.id === id); }
function time(value: number | bigint | undefined) { return value ? new Date(Number(value)).toLocaleTimeString('zh-CN', { hour12: false }) : '尚无记录'; }
function matching(worker: Worker) { return props.connected && gateway(worker.id)?.online
  && worker.running && worker.updated_at_ms >= worker.started_at_ms && worker.state.pid === worker.pid
  && worker.state.session_id === gateway(worker.id)?.session; }
onMounted(poll);
onBeforeUnmount(() => { disposed = true; clearTimeout(timer); });
</script>

<template>
  <div v-show="showProcess" id="panel-processes" role="tabpanel" aria-labelledby="tab-processes" tabindex="0">
  <p v-if="!available" class="panel empty">设备进程信息仅在本地演示启动后可用。</p>
  <section v-if="available" class="panel proof-panel" aria-label="设备进程与连接证据">
    <div class="panel-heading"><div><h2>设备进程与 TCP 连接</h2><p>进程来源：本地监督程序，并核对设备自报 PID · 连接来源：Java 网关</p></div>
      <span class="status-tag" data-testid="process-count">{{ polling ? `${count} 个独立进程` : '监督信息不可用' }}</span></div>
    <p v-if="!polling" class="proof-warning">暂时无法获取进程观测，保留的信息不能当作当前存活证据。</p>
    <div class="process-grid"><article v-for="worker in runtime?.robots ?? []" :key="worker.id" class="process-card" :data-testid="`process-${worker.id}`">
      <div class="process-title"><strong>{{ worker.id }}</strong><span>{{ polling ? (worker.running ? '进程运行中' : '进程已停止') : '观测不可用' }}</span></div>
      <dl><dt>实际 PID</dt><dd :data-testid="`pid-${worker.id}`">{{ polling && worker.running ? worker.pid : '—' }}</dd>
        <dt>进程收 / 发</dt><dd>{{ worker.state.received_messages ?? 0 }} / {{ worker.state.sent_messages ?? 0 }}</dd>
        <dt>TCP 对端</dt><dd :data-testid="`tcp-${worker.id}`">{{ connected ? (gateway(worker.id)?.online ? gateway(worker.id)?.remoteAddress : '未连接') : '网页未连接，尚未核验' }}</dd>
        <dt>网关收 / 发</dt><dd>{{ connected && gateway(worker.id)?.online ? `${gateway(worker.id)?.receivedMessages ?? 0} / ${gateway(worker.id)?.sentMessages ?? 0}` : '—' }}</dd>
        <dt>最近心跳</dt><dd>{{ connected && gateway(worker.id)?.online ? time(gateway(worker.id)?.lastHeartbeatAt) : '—' }}</dd>
        <dt>当前会话</dt><dd class="session-proof" :data-testid="`proof-session-${worker.id}`">{{ connected && gateway(worker.id)?.online ? gateway(worker.id)?.session : '—' }}</dd>
        <dt>两端会话</dt><dd :data-testid="`match-${worker.id}`">{{ polling && matching(worker) ? '已匹配' : '尚未核验 / 不匹配' }}</dd>
        <dt>进程观测于</dt><dd>{{ time(worker.updated_at_ms) }}{{ runtime && runtime.observed_at_ms - worker.updated_at_ms > 2000 ? '（设备自报已陈旧）' : '' }}</dd></dl>
      <div class="process-actions"><button class="quiet" :disabled="!polling || !!busy || !worker.running" @click="operate(worker.id, 'stop')">停止 {{ worker.id }} 进程</button><button class="quiet" :disabled="!polling || !!busy || worker.running" @click="operate(worker.id, 'start')">启动 {{ worker.id }} 进程</button></div>
      <p v-if="worker.error" class="proof-warning">{{ worker.error }}</p>
    </article></div>
    <p class="proof-footnote">停止会真正退出该设备进程，其他设备继续运行。重新启动使用新 PID / 新会话，保留停止时的模拟位置，不恢复旧运动。网关计数按当前 TCP 会话统计，本地写入完成不代表对端收到。</p>
    <p v-if="error" class="notice" role="status">{{ error }}</p>
  </section>
  </div>
  <div v-show="showTrace" id="panel-history" role="tabpanel" aria-labelledby="tab-history" tabindex="0">
  <slot name="history" />
  <section v-show="showTrace" class="panel trace-panel" aria-label="指令通信闭环证据"><div class="panel-heading"><div><h2>指令通信闭环证据</h2><p>同一 command_id，分别查看网页、网关与机器人实际记录的事件</p></div>
    <label class="trace-select">链路指令<select aria-label="链路指令" :value="current?.id ?? ''" @change="emit('update:selectedCommand', ($event.target as HTMLSelectElement).value)"><option v-for="command in commands" :key="command.id" :value="command.id">{{ command.robot }} · {{ actionNames[command.action] }} · {{ command.id.slice(0, 8) }}</option></select></label></div>
    <div v-if="!current" class="empty compact">发送一条指令后，逐段查看真实观测。未收到的事件不会自动补齐。</div>
    <template v-else><div class="trace-context">{{ current.robot }}<span>{{ current.id }}</span></div><ol class="trace-list">
      <li v-for="row in rows" :key="row.stage" :class="{ observed: !!row.event }" :data-testid="`trace-${row.stage}`"><i>{{ row.event ? '✓' : '·' }}</i><div><strong>{{ row.label }}</strong><small>{{ sourceNames[row.source] }}{{ row.event?.pid ? ` · PID ${row.event.pid}` : '' }}</small></div><div class="trace-observation"><span>{{ row.event ? time(row.event.at) + '.' + String(row.event.at % 1000).padStart(3, '0') : '尚无观测' }}</span><small v-if="row.event">序号 {{ row.event.seq }} · 会话 {{ row.event.session || '发送时未绑定设备会话' }}</small></div></li>
    </ol></template><p class="proof-footnote">只展示实际采集的节点，缺失不等于执行失败。不同进程时钟可能有偏差，列表按业务环节排列，不据此宣称端到端延迟；本地写出与对端接收分开记录。网页/网关、设备侧各最多保留 600 条事件，历史可能过期。机器人进程观测仅在本地演示中可用。</p>
  </section>
  </div>
</template>
