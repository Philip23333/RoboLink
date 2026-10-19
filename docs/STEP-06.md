# 第六步：断线重连与故障验证

日期：2026-10-20。状态：实现与验收通过。

每台设备独立循环连接：断线先停止本地动作、丢弃旧发送队列，再以带随机抖动的指数退避重新注册。重试间隔从 0.125–0.25 秒逐步增加，最大 5 秒；已连接后断线重新从短间隔开始。取消任务会停止，不会进入重试。

重新注册获取新 session_id，序号重新从 1 发送；位置与设备进程内的短期指令去重记录保留，正在执行的动作不恢复。一个设备恢复时不会取消其他设备。

## 可复现场景

```powershell
# RB-001 首次连接 8 秒后主动断开一次，随后自动重连。
.\scripts\start-simulator.ps1 -CredentialFile .\gateway\devices.local.properties -FaultRobot RB-001 -DisconnectAfter 8

# 第一条接受的指令，其 ACK 和结果至少延迟到 2.5 秒后再交付。
.\scripts\start-simulator.ps1 -CredentialFile .\gateway\devices.local.properties -FaultRobot RB-001 -AckDelayMs 2500

# 第一条接受的指令不回 ACK；使用 3 秒动作观察网关默认 2 秒 ACK 超时。
.\scripts\start-simulator.ps1 -CredentialFile .\gateway\devices.local.properties -FaultRobot RB-001 -DropNextAck

# 第一条接受的指令不回结果，但动作仍按本地截止时间结束。
.\scripts\start-simulator.ps1 -CredentialFile .\gateway\devices.local.properties -FaultRobot RB-001 -DropNextResult
```

故障仅作用于指定设备、第一次接受的指令或第一次连接断开，不会每次重连反复注入。ACK 延迟不阻塞心跳和运动更新，延迟回复队列仍有 64 条上限。故障注入是应用层模拟，不代表真实网络丢包。

## 验收

运行 `.\scripts\verify-simulator.ps1`。本轮 28 项 Python 测试全部通过，原有 83 项 Java 测试通过，三设备控制联调回归通过。

新增真实网关恢复验证：主动断开运动中的设备，原指令 UNKNOWN、设备停止并换会话，另一设备会话不变；缺失 ACK 与结果分别进入 ACK_TIMEOUT 和 RESULT_TIMEOUT；真正终止 Java 网关进程后重启，三台设备重新注册，旧运动不恢复，新指令可以完成。测试使用更短超时以控制运行时间，不修改开发环境默认值。

证据：[本轮完整报告](evidence/step-06-recovery-report.json)。尚无 Vue 页面，不包含公网丢包、长时稳定性或并发上限测试。无效凭证也会退避重试，需要修正凭证并重启模拟器；不会记录凭证明文。

下一步为 Vue 连接、订阅和设备状态页面。
