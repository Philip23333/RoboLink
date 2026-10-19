<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { ControlConnection, phaseLabel, type View } from './connection';
import { deviceHealth } from './state';

const view = ref<View>({ phase: 'disconnected', message: '', devices: [], rate: 0, session: '' });
const connection = new ControlConnection(value => { view.value = value; });
const endpoint = ref('ws://127.0.0.1:9001/control');
const account = ref('operator');
const token = ref('');
const connected = computed(() => view.value.phase === 'ready');
const online = computed(() => connected.value ? view.value.devices.filter(device => device.online).length : 0);
const selectedId = ref('');
const selected = computed(() => view.value.devices.find(device => device.id === selectedId.value) ?? view.value.devices[0]);
function connect() { connection.connect(endpoint.value, account.value, token.value); token.value = ''; }
function health(device: typeof view.value.devices[number]) { return deviceHealth(device, connected.value); }
onBeforeUnmount(() => connection.dispose());
</script>

<template>
  <div class="shell">
    <aside class="sidebar"><a class="brand" href="/" aria-label="RoboLink 首页"><span class="brand-mark">R</span>RoboLink<span class="brand-dot">®</span></a>
      <div class="workspace-label">WORKSPACE</div><div class="nav-item active"><span>◈</span> 机器人总览</div>
      <div class="sidebar-bottom"><span class="live-dot"></span> 本地开发环境<br><small>软件模拟 · 非实体设备</small></div>
    </aside>
    <main>
      <header class="topbar"><span>工作空间 <span class="slash">/</span> 控制中心</span><span class="environment">LOCAL DEMO</span></header>
      <div class="content">
        <div class="page-heading"><div><div class="eyebrow">FLEET OPERATIONS</div><h1>机器人控制中心<span class="title-dot">.</span></h1><p>连接设备，观察状态。所有位置数据均来自模拟机器人回传。</p></div>
          <span class="connection-badge" :class="{ good: connected }" data-testid="connection-state" :data-session="view.session"><i></i>{{ phaseLabel[view.phase] }}</span></div>
        <section class="connection-panel" aria-label="连接配置"><div class="section-intro"><strong>控制通道</strong><small>访问凭证仅保留在当前页面内存中</small></div>
          <form class="connection-form" @submit.prevent="connect"><label>网关地址<input v-model="endpoint" aria-label="网关地址" spellcheck="false"></label>
            <label class="account-field">账号<input v-model="account" aria-label="账号" autocomplete="username"></label>
            <label>访问凭证<input v-model="token" type="password" aria-label="访问凭证" autocomplete="off" placeholder="输入本地控制凭证" required></label>
            <button type="submit" class="primary">连接网关 <span>↗</span></button><button v-if="view.phase !== 'disconnected'" type="button" class="quiet" @click="connection.disconnect()">断开</button></form>
          <p v-if="view.message" class="notice" role="status">{{ view.message }}</p>
        </section>
        <section class="metrics" aria-label="设备统计"><article><div class="metric-label">设备总数 <span>01</span></div><div class="metric-value" data-testid="device-total">{{ view.devices.length }}<small>台</small></div><p>当前账号订阅范围</p></article>
          <article><div class="metric-label">在线设备 <span>02</span></div><div class="metric-value" data-testid="online-count">{{ connected ? online : '—' }}<small>台</small></div><p>{{ connected ? '已连接到网关' : '等待恢复连接' }}</p></article>
          <article><div class="metric-label">收到的遥测 <span>03</span></div><div class="metric-value">{{ view.rate.toFixed(1) }}<small>条 / 秒</small></div><p>最近 5 秒收到条数 ÷ 5</p></article></section>
        <div class="monitor-layout"><section class="panel devices-panel"><div class="panel-heading"><div><h2>设备列表</h2><p>选择设备查看实时信息</p></div><span class="subtle-label">{{ view.devices.length }} DEVICES</span></div>
            <div v-if="!view.devices.length" class="empty"><div class="empty-icon">⌁</div><strong>等待设备连接</strong><p>启动网关与模拟器，连接后自动同步设备。</p></div>
            <button v-for="device in view.devices" :key="device.id" class="device-row" :class="{ selected: selected?.id === device.id }" :data-testid="`device-${device.id}`" @click="selectedId = device.id">
              <span class="robot-icon">▣</span><span class="device-name"><strong>{{ device.id }}</strong><small>{{ health(device) }}</small></span>
              <span class="battery"><span>{{ device.telemetry ? `${device.telemetry.batteryPercent}%` : '—' }}</span><span class="battery-track"><i :style="{ width: `${device.telemetry?.batteryPercent ?? 0}%` }"></i></span></span><span class="chevron">↗</span></button>
          </section>
          <section class="panel detail-panel"><div class="panel-heading"><div><h2>设备状态 <span class="mono">{{ selected?.id ?? '—' }}</span></h2><p>由 WebSocket 二进制消息实时同步</p></div><span class="status-tag">{{ selected ? health(selected) : '等待同步' }}</span></div>
            <div v-if="selected" class="device-details"><div><small>位置 X / 米</small><strong data-testid="position-x">{{ selected.telemetry?.pose?.xM.toFixed(3) ?? '—' }}</strong></div><div><small>位置 Y / 米</small><strong>{{ selected.telemetry?.pose?.yM.toFixed(3) ?? '—' }}</strong></div>
              <div><small>朝向 / 弧度</small><strong data-testid="yaw">{{ selected.telemetry?.pose?.yawRad.toFixed(3) ?? '—' }}</strong></div><div><small>遥测序号</small><strong>{{ selected.seq.toString() }}</strong></div></div>
            <div v-else class="empty"><div class="empty-icon">◎</div><p>连接后查看设备电量与位姿。</p></div>
            <div class="detail-note"><span class="live-dot"></span> {{ connected ? '实时状态同步已启用' : '当前状态不可用于控制' }}<small>超过 3 秒未更新会标记为遥测陈旧</small></div>
          </section></div>
        <footer>RoboLink Control Center <span>通信驱动的机器人软件模拟 / MVP</span></footer>
      </div>
    </main>
  </div>
</template>
