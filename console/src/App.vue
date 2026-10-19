<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { ControlConnection, phaseLabel, type View } from './connection';
import { deviceHealth } from './state';
import { Action, Envelope } from './generated/robolink';
import { CommandBook, MessageBook, actionNames, phaseNames, reasonText } from './commands';
import { TraceBook } from './traces';
import ProofPanel from './ProofPanel.vue';

const view = ref<View>({ phase: 'disconnected', message: '', devices: [], rate: 0, session: '' });
const commands = new CommandBook();
const messages = new MessageBook();
const traceBook = new TraceBook();
const traceCommand = ref('');
const activeTab = ref<'overview' | 'processes' | 'history' | 'messages'>('overview');
const tabs = [{ id: 'overview', label: '机器人总览' }, { id: 'processes', label: '设备进程与 TCP 连接' }, { id: 'history', label: '指令执行记录' }, { id: 'messages', label: '通信消息' }] as const;
function moveTab(event: KeyboardEvent, index: number) {
  const offset = event.key === 'ArrowDown' ? 1 : event.key === 'ArrowUp' ? -1 : 0;
  if (!offset && event.key !== 'Home' && event.key !== 'End') return;
  event.preventDefault();
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + offset + tabs.length) % tabs.length;
  activeTab.value = tabs[next]!.id;
  document.getElementById(`tab-${activeTab.value}`)?.focus();
}
const currentPage = computed(() => tabs.find(tab => tab.id === activeTab.value)!);
const pageDescriptions = { overview: '连接设备，观察状态。所有位置数据均来自模拟机器人回传。', processes: '查看独立机器人进程与网关 TCP 会话，核对设备连接。', history: '查看指令执行状态，以及网页、网关与设备之间的通信闭环。', messages: '查看收发消息及解码内容，认证内容不记录。' };
const revision = ref(0);
const sessions = new Map<string, string>();
const connection = new ControlConnection(value => {
  if (value.phase !== 'ready') commands.disconnected();
  for (const device of value.devices) {
    if (sessions.get(device.id) && sessions.get(device.id) !== device.session) commands.uncertain.delete(device.id);
    sessions.set(device.id, device.session);
  }
  view.value = value; revision.value++;
}, (message, bytes, direction) => {
  if (direction === 'in') commands.apply(message);
  messages.add(message, bytes, direction);
  traceBook.observe(message, direction);
});
const endpoint = ref('ws://127.0.0.1:9001/control');
const account = ref('operator');
const token = ref('');
const speed = ref(0.2);
const angular = ref(0.5);
const duration = ref(1000);
const zoom = ref(1);
const controlNotice = ref('');
const demoAvailable = ref(false);
fetch('/__demo/available').then(async response => { if (response.ok) demoAvailable.value = (await response.json()).available === true; }).catch(() => {});
async function connectDemo() {
  try {
    const response = await fetch('/__demo/session', { cache: 'no-store' });
    if (!response.ok) throw new Error();
    const config = await response.json();
    endpoint.value = config.url; account.value = config.id;
    connection.connect(config.url, config.id, config.token);
  } catch { controlNotice.value = '本地演示尚未启动，请先运行启动脚本'; }
}
const connected = computed(() => view.value.phase === 'ready');
const online = computed(() => connected.value ? view.value.devices.filter(device => device.online).length : 0);
const selectedId = ref('');
const selected = computed(() => view.value.devices.find(device => device.id === selectedId.value) ?? view.value.devices[0]);
const commandRows = computed(() => { revision.value; return [...commands.entries]; });
const messageRows = computed(() => { revision.value; return [...messages.entries]; });
const traceRows = computed(() => { revision.value; return [...traceBook.entries]; });
const canMove = computed(() => { revision.value; return !!selected.value && connected.value
  && deviceHealth(selected.value, true) === '空闲' && !commands.pending(selected.value.id) && !commands.uncertain.has(selected.value.id); });
const canStop = computed(() => { revision.value; return !!selected.value?.online && connected.value && !commands.pending(selected.value.id, true); });
const validParameters = computed(() => Number.isFinite(speed.value) && speed.value > 0 && speed.value <= .5
  && Number.isFinite(angular.value) && angular.value > 0 && angular.value <= 1
  && Number.isInteger(duration.value) && duration.value > 0 && duration.value <= 3000);
function submit(action: Action) {
  const device = selected.value;
  if (!device || (action === Action.ACTION_STOP ? !canStop.value : (!canMove.value || !validParameters.value))) return;
  const id = crypto.randomUUID();
  if (!commands.add(id, device.id, action)) { controlNotice.value = '指令记录已满，请等待已有指令结束'; return; }
  const message = Envelope.fromPartial({ protocolVersion: 1, robotId: device.id, payload: { $case: 'commandRequest', value: {
    commandId: id, action, motion: action === Action.ACTION_STOP ? undefined : { durationMs: duration.value,
      speedMps: action === Action.ACTION_MOVE_FORWARD ? speed.value : 0,
      angularSpeedRadS: action === Action.ACTION_MOVE_FORWARD ? 0 : angular.value } } } });
  if (!connection.send(message)) { commands.disconnected(); controlNotice.value = '发送未完成，请重新同步；不会自动重发运动'; }
  else controlNotice.value = '';
  revision.value++;
}
function coordinate(value: number) { return 300 + value * 32 * zoom.value; }
function poseTransform(device: typeof view.value.devices[number]) {
  const pose = device.telemetry?.pose;
  return `translate(${coordinate(pose?.xM ?? 0)} ${300 - (pose?.yM ?? 0) * 32 * zoom.value}) rotate(${-(pose?.yawRad ?? 0) * 180 / Math.PI})`;
}
const mapDevices = computed(() => view.value.devices.filter(device => device.telemetry?.pose));
function connect() { connection.connect(endpoint.value, account.value, token.value); token.value = ''; }
function health(device: typeof view.value.devices[number]) { return deviceHealth(device, connected.value); }
onBeforeUnmount(() => connection.dispose());
</script>

<template>
  <div class="shell">
    <aside class="sidebar"><a class="brand" href="/" aria-label="RoboLink 首页"><span class="brand-mark">R</span>RoboLink<span class="brand-dot">®</span></a>
      <div class="workspace-label">WORKSPACE</div>
      <nav class="sidebar-nav" role="tablist" aria-label="控制中心导航" aria-orientation="vertical">
        <button v-for="(tab, index) in tabs" :key="tab.id" :id="`tab-${tab.id}`" class="nav-item" :class="{ active: activeTab === tab.id }" role="tab" :aria-selected="activeTab === tab.id" :aria-controls="`panel-${tab.id}`" :tabindex="activeTab === tab.id ? 0 : -1" @click="activeTab = tab.id" @keydown="moveTab($event, index)"><span aria-hidden="true">{{ ['◈', '⌁', '☷', '⇄'][index] }}</span>{{ tab.label }}</button>
      </nav>
      <div class="sidebar-bottom"><span class="live-dot"></span> 本地开发环境<br><small>软件模拟 · 非实体设备</small></div>
    </aside>
    <main>
      <header class="topbar"><span>工作空间 <span class="slash">/</span> {{ currentPage.label }}</span><span class="environment">LOCAL DEMO</span></header>
      <div class="content">
        <div class="page-heading"><div><div class="eyebrow">FLEET OPERATIONS</div><h1>{{ activeTab === 'overview' ? '机器人控制中心' : currentPage.label }}<span class="title-dot">.</span></h1><p>{{ pageDescriptions[activeTab] }}</p></div>
          <span class="connection-badge" :class="{ good: connected }" data-testid="connection-state" :data-session="view.session"><i></i>{{ phaseLabel[view.phase] }}</span></div>
        <div v-show="activeTab === 'overview'" id="panel-overview" role="tabpanel" aria-labelledby="tab-overview" tabindex="0">
        <section class="connection-panel" aria-label="连接配置"><div class="section-intro"><strong>控制通道</strong><small>访问凭证仅保留在当前页面内存中</small></div>
          <form class="connection-form" @submit.prevent="connect"><label>网关地址<input v-model="endpoint" aria-label="网关地址" spellcheck="false"></label>
            <label class="account-field">账号<input v-model="account" aria-label="账号" autocomplete="username"></label>
            <label>访问凭证<input v-model="token" type="password" aria-label="访问凭证" autocomplete="off" placeholder="输入本地控制凭证" required></label>
            <button type="submit" class="primary">连接网关 <span>↗</span></button><button v-if="demoAvailable" type="button" class="quiet" @click="connectDemo">连接本地演示</button><button v-if="view.phase !== 'disconnected'" type="button" class="quiet" @click="connection.disconnect()">断开</button></form>
          <p v-if="view.message" class="notice" role="status">{{ view.message }}</p>
        </section>
        <section class="metrics" aria-label="设备统计"><article><div class="metric-label">设备总数 <span>01</span></div><div class="metric-value" data-testid="device-total">{{ view.devices.length }}<small>台</small></div><p>当前账号订阅范围</p></article>
          <article><div class="metric-label">在线设备 <span>02</span></div><div class="metric-value" data-testid="online-count">{{ connected ? online : '—' }}<small>台</small></div><p>{{ connected ? '已连接到网关' : '等待恢复连接' }}</p></article>
          <article><div class="metric-label">收到的遥测 <span>03</span></div><div class="metric-value">{{ view.rate.toFixed(1) }}<small>条 / 秒</small></div><p>最近 5 秒收到条数 ÷ 5</p></article></section>
        <div class="operations-layout">
        <section class="panel map-panel"><div class="panel-heading"><div><h2>位置与朝向</h2><p>平面坐标 / 米 · 箭头指向设备朝向 · 未知位置不绘制</p></div><div class="map-tools"><button class="quiet" aria-label="缩小地图" @click="zoom = Math.max(.25, zoom / 1.5)">−</button><button class="quiet" @click="zoom = 1">重置视图</button><button class="quiet" aria-label="放大地图" @click="zoom = Math.min(3, zoom * 1.5)">＋</button></div></div>
          <svg class="robot-map" viewBox="0 0 600 450" role="img" aria-label="机器人位置地图"><defs><pattern id="grid" :width="32 * zoom" :height="32 * zoom" patternUnits="userSpaceOnUse" x="300" y="300"><path :d="`M ${32 * zoom} 0 L 0 0 0 ${32 * zoom}`" fill="none" stroke="#dde6da" stroke-width=".6" /></pattern></defs><rect width="600" height="450" fill="url(#grid)"/><line x1="0" y1="300" x2="600" y2="300" stroke="#b3c6ae"/><line x1="300" y1="0" x2="300" y2="450" stroke="#b3c6ae"/><text x="575" y="291" class="axis-label">X →</text><text x="310" y="20" class="axis-label">Y ↑</text><text x="307" y="317" class="axis-label">0</text>
            <g v-for="device in mapDevices" :key="device.id" :transform="poseTransform(device)" :opacity="health(device) === '离线' || !connected ? .35 : 1" class="map-device" :class="{ chosen: selected?.id === device.id }" :data-testid="`map-${device.id}`" tabindex="0" role="button" :aria-label="`选择 ${device.id}`" @click="selectedId = device.id" @keydown.enter="selectedId = device.id" @keydown.space.prevent="selectedId = device.id"><circle r="19"/><path d="M 13 0 L -7 -7 L -4 0 L -7 7 Z"/><text :transform="`rotate(${(device.telemetry?.pose?.yawRad ?? 0) * 180 / Math.PI})`" x="0" y="35" text-anchor="middle">{{ device.id }}</text></g>
          </svg><div class="map-caption">{{ mapDevices.length }} 台设备具有位姿数据<span>1 格 = 1 米 · 缩放 {{ zoom.toFixed(2) }}×</span></div></section>
        <div class="fleet-controls"><section class="panel devices-panel"><div class="panel-heading"><div><h2>设备列表</h2><p>选择设备查看实时信息</p></div><span class="subtle-label">{{ view.devices.length }} DEVICES</span></div>
            <div v-if="!view.devices.length" class="empty"><div class="empty-icon">⌁</div><strong>等待设备连接</strong><p>启动网关与模拟器，连接后自动同步设备。</p></div>
            <button v-for="device in view.devices" :key="device.id" class="device-row" :class="{ selected: selected?.id === device.id }" :data-testid="`device-${device.id}`" @click="selectedId = device.id">
              <span class="robot-icon">▣</span><span class="device-name"><strong>{{ device.id }}</strong><small>{{ health(device) }}</small></span>
              <span class="battery"><span>{{ device.telemetry ? `${device.telemetry.batteryPercent}%` : '—' }}</span><span class="battery-track"><i :style="{ width: `${device.telemetry?.batteryPercent ?? 0}%` }"></i></span></span><span class="chevron">↗</span></button>
          </section>
          <section class="panel detail-panel"><div class="panel-heading"><div><h2>设备状态 <span class="mono">{{ selected?.id ?? '—' }}</span></h2><p>查看设备回传状态，发送有限运动指令</p></div><span class="status-tag">{{ selected ? health(selected) : '等待同步' }}</span></div>
            <div v-if="selected" class="device-details"><div><small>位置 X / 米</small><strong data-testid="position-x">{{ selected.telemetry?.pose?.xM.toFixed(3) ?? '—' }}</strong></div><div><small>位置 Y / 米</small><strong>{{ selected.telemetry?.pose?.yM.toFixed(3) ?? '—' }}</strong></div>
              <div><small>朝向 / 弧度</small><strong data-testid="yaw">{{ selected.telemetry?.pose?.yawRad.toFixed(3) ?? '—' }}</strong></div><div><small>最近遥测</small><strong class="last-update">{{ selected.receivedAt ? new Date(selected.receivedAt).toLocaleTimeString() : '等待上报' }}</strong></div></div>
            <div v-else class="empty"><div class="empty-icon">◎</div><p>连接后查看设备电量与位姿。</p></div>
            <div class="motion-controls"><div class="parameter-grid"><label>速度 / m/s<input v-model.number="speed" aria-label="运动速度" type="number" min="0.01" max="0.5" step="0.05"></label><label>角速度 / rad/s<input v-model.number="angular" aria-label="转向角速度" type="number" min="0.01" max="1" step="0.1"></label><label>时长 / ms<input v-model.number="duration" aria-label="动作时长" type="number" min="1" max="3000" step="100"></label></div>
              <div class="motion-buttons"><button class="quiet" :disabled="!canMove || !validParameters" @click="submit(Action.ACTION_TURN_LEFT)">↶ 左转</button><button class="primary" :disabled="!canMove || !validParameters" @click="submit(Action.ACTION_MOVE_FORWARD)">↑ 前进</button><button class="quiet" :disabled="!canMove || !validParameters" @click="submit(Action.ACTION_TURN_RIGHT)">右转 ↷</button><button class="stop-button" :disabled="!canStop" @click="submit(Action.ACTION_STOP)">■ 停止</button></div>
              <p v-if="!validParameters" class="notice">速度需在 0–0.5 m/s、角速度在 0–1 rad/s（均不含 0），时长为 1–3000 ms 整数。</p>
              <p v-if="selected && commands.uncertain.has(selected.id)" class="notice">上次运动结果不确定，请先停止或等待设备重新建立会话。</p>
              <p v-if="controlNotice" class="notice" role="status">{{ controlNotice }}</p>
            </div>
            <div class="detail-note"><span class="live-dot"></span> {{ connected ? '实时状态同步已启用' : '当前状态不可用于控制' }}<small>超过 3 秒未更新会标记为遥测陈旧 · 运动由设备本地限时结束</small></div>
          </section></div>
        </div>
        </div>
        <ProofPanel :show-process="activeTab === 'processes'" :show-trace="activeTab === 'history'" :devices="view.devices" :connected="connected" :available="demoAvailable" :commands="commandRows" :traces="traceRows" v-model:selected-command="traceCommand">
          <template #history>
        <section class="panel history-panel"><div class="panel-heading"><div><h2>指令执行记录</h2><p>发送、设备确认与执行完成分别记录 · 最多保留 100 条</p></div><span class="subtle-label">{{ commandRows.length }} COMMANDS</span></div>
          <div v-if="!commandRows.length" class="empty compact"><p>选择设备并发送第一条指令，查看真实执行进度。</p></div>
          <div v-else class="table-scroll"><table><thead><tr><th>时间 / 设备</th><th>动作</th><th>执行状态</th><th>ACK / 结果</th><th>说明 / 指令编号</th></tr></thead><tbody><tr v-for="entry in commandRows" :key="entry.id" :data-testid="`command-${entry.id}`"><td>{{ new Date(entry.createdAt).toLocaleTimeString() }}<small>{{ entry.robot }}</small></td><td>{{ actionNames[entry.action] }}</td><td><span class="command-phase" :data-phase="entry.phase">{{ phaseNames[entry.phase] }}</span></td><td>{{ entry.ackAt ? (entry.ackAccepted ? '已接受' : '已拒绝') : '等待确认' }}<small>{{ entry.resultAt ? '已收到结果' : '未收到结果' }}</small></td><td class="reason-cell">{{ reasonText(entry.reason) || '等待网关处理' }}<small>{{ entry.id }}</small></td></tr></tbody></table></div>
        </section>
          </template>
        </ProofPanel>
        <section v-show="activeTab === 'messages'" id="panel-messages" role="tabpanel" aria-labelledby="tab-messages" tabindex="0" class="panel messages-panel"><div class="panel-heading"><div><h2>通信消息</h2><p>保留最近 120 条 · 同设备遥测只保留最新一条 · 不记录认证内容</p></div><button class="quiet" @click="messages.entries = []; revision++">清空消息</button></div>
          <div class="message-list" data-testid="message-list"><p v-if="!messageRows.length" class="empty compact">等待消息…</p><details v-for="entry in messageRows" :key="entry.id"><summary><span class="message-direction">{{ entry.direction === 'in' ? '↓ 收到' : '↑ 发出' }}</span><strong>{{ entry.kind }}</strong><span>{{ entry.robot || '控制通道' }}</span><small>{{ entry.bytes }} B</small><time>{{ new Date(entry.at).toLocaleTimeString() }}</time></summary><pre>{{ entry.details }}</pre></details></div>
        </section>
        <footer>RoboLink Control Center <span>通信驱动的机器人软件模拟 / MVP</span></footer>
      </div>
    </main>
  </div>
</template>
