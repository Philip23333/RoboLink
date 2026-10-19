# RoboLink

多机器人云端实时控制与 AI 技能编排平台，当前推进通信控制 MVP。

当前完成前八个执行单元：自定义 TCP / Protobuf 网关、设备认证与心跳、控制端 WebSocket 桥接、三台 Python 模拟机器人、独立重连，以及 Vue 实时监控、二维位置、前进 / 转向 / STOP 和指令结果。网页位置来自设备回传，已通过实际浏览器控制验证。

## 直接体验本地网页

首次按下文准备环境后，在项目根目录运行：

```powershell
.\scripts\start-demo.ps1
```

打开 **http://127.0.0.1:5173**，点击「连接本地演示」，选择机器人，再点击「前进」「左转」「右转」或「停止」。观察坐标、地图和指令记录：ACK 表示设备接受，执行成功才表示收到完成结果。默认动作 1 秒，速度 0.2 m/s。

启动脚本在后台运行本机网关、三台设备和网页，首次缺少本地凭证时随机生成并保存在忽略文件中，不覆盖已有配置。只监听回环地址；凭证不会放进 URL、前端构建产物或浏览器存储。「连接本地演示」依赖显式启用的本地 Vite 开发接口，跨来源和跨站请求会被拒绝；生产构建没有该接口。

停止所有本次演示服务：

```powershell
.\scripts\stop-demo.ps1
```

使用端口 9000、9001、5173 和本地管理端口 19006。与手动启动方式二选一，不要同时占用相同端口。监督进程只清理自己启动的服务；日志在 `artifacts/demo/`。这是本地软件模拟演示，没有真实机器人、TLS、公网部署或持久化。

## 目录与职责

| 目录 | 职责 |
| --- | --- |
| `protocol/` | 三端共用的唯一 `.proto` 协议源与语义约定 |
| `gateway/` | Java 21 / Netty 网关，设备 TCP、控制端 WebSocket 与指令状态管理 |
| `simulator/` | Python asyncio 多机器人模拟器、运动与去重模型、协议样例和测试 |
| `console/` | Vue3 实时控制台、TypeScript 二进制解码、位姿与控制及浏览器测试 |
| `scripts/` | Windows PowerShell 环境准备、协议生成及完整验证入口 |
| `docs/changes/` | 每次执行后的修改说明，重点解释技术应用与验证结果 |

执行总路线见 [MVP-ROADMAP.md](MVP-ROADMAP.md)，本轮分步记录见[第六步](docs/STEP-06.md)、[第七步](docs/STEP-07.md)和[第八步](docs/STEP-08.md)。前五步的历史记录保留在 docs 中。

## 环境与固定依赖

本次开发入口面向 **Windows x64 / PowerShell 7**，需要 JDK 21、Python 3.12+、Node.js 22.12+，实际使用 Node 24.19。推荐使用验收记录中的实际环境版本；其他系统尚未验证统一脚本。

| 工具 / 依赖 | 固定版本或约束 |
| --- | --- |
| Java 编译目标 | 21 |
| Maven / Wrapper | 3.9.9 / 3.3.4 |
| protoc | 33.5（Maven artifact 版本 4.33.5） |
| Java Protobuf / Python Protobuf | 4.33.5 / 6.33.5 |
| Netty BOM | 4.1.135.Final |
| JUnit Jupiter | 5.13.4（测试依赖） |
| ts-proto / TypeScript | 2.18.0 / 5.9.3 |
| TypeScript Protobuf 运行时 | @bufbuild/protobuf 2.16.0 |
| tsx / Node 类型 | 4.23.15 / @types/node 24.10.1 |
| Vue / Vite / Vue 插件 | 3.5.44 / 8.3.4 / 6.0.10 |
| vue-tsc / Playwright | 3.3.13 / 1.64.1 |

Node 间接依赖由 `console/package-lock.json` 锁定，恢复依赖使用 `npm ci`。Python 依赖在 `simulator/requirements.txt` 固定。Maven 插件和直接依赖版本在 POM 中固定。

## 第一次准备环境

在项目根目录执行。已安装的 Java/Python 位置由使用者指定，以下路径为占位示例：

```powershell
.\scripts\setup.ps1 -JavaHome 'C:\path\to\jdk-21' -Python 'C:\path\to\python.exe'
```

若当前终端已有正确的 `JAVA_HOME` 和 `python`，可以直接运行：

```powershell
.\scripts\setup.ps1
```

脚本优先使用传入的 JDK，其次使用 `JAVA_HOME`；未配置时可发现项目 `.tools/java/` 中的本地 JDK。Python 使用项目 `.venv`，Java、Maven 和依赖缓存放在 `.tools/`。不会修改系统环境变量。首次准备和构建需要联网访问 npm、PyPI 与 Maven Central。

## 生成与验证

仅重新生成协议：

```powershell
.\scripts\generate-protocol.ps1
```

完整验收：

```powershell
.\scripts\verify-protocol.ps1 -Clean
```

该入口会清理生成目录，重新生成三端代码，构建 Java 工程，检查 TypeScript 类型，并执行三端消息互解。Java 构建中的 `verify` 阶段之后，脚本还会单独执行跨语言样例验证；单独运行 Maven 不能替代完整互通验收。

输出位于 `artifacts/protocol/`：

- `java/`、`python/`、`typescript/`：各端编码的四种二进制样例。
- `report.json`：成功运行的结果，包含 9 种编码端 / 解码端组合、36 次消息比较。

固定样例覆盖遥测、运动指令、控制认证请求与认证结果，包含枚举、嵌套位姿、`oneof` 分支、`uint64` 最大值与超过 JavaScript 安全整数范围的时间戳。认证样例仅使用公开测试字符串，时间戳用于精度验证，不代表现实时间。

下面保留分模块启动与验证方式；直接体验网页可使用上文的一键启动。

TCP 分帧与编解码验收：

```powershell
.\scripts\verify-transport.ps1 -Clean
```

这会执行 23 项 JUnit 测试：21 项内存中的分帧与边界测试，以及 2 项真实本机 TCP 测试。随机拆分用固定种子，保证失败场景可复现。成功结果输出到 `artifacts/transport/report.json`，原始 JUnit 报告位于 `gateway/target/surefire-reports/`。

第二步本机 TCP 测试仅在测试期间启动随机端口并原样回复消息。第三步另有正式设备接入服务及生命周期测试。

设备会话与服务生命周期验收：

```powershell
.\scripts\verify-session.ps1 -Clean
.\scripts\verify-protocol.ps1
.\.venv\Scripts\python.exe .\scripts\smoke-gateway.py
```

按顺序在同一终端执行。新增 31 项会话与生命周期测试，加上原有 23 项共 54 项 Java 测试。最后一条临时启动真正的 Java 主程序，用 Python 注册、发送遥测和心跳，验证完会清理测试进程和临时凭证；它不属于完整机器人模拟器。

## 启动设备 TCP 网关

先为本地演示生成三台设备各自的随机凭证。文件已在忽略规则中，生成操作不会把凭证输出到终端：

```powershell
@(
  "RB-001=$([guid]::NewGuid().ToString('N'))"
  "RB-002=$([guid]::NewGuid().ToString('N'))"
  "RB-003=$([guid]::NewGuid().ToString('N'))"
) | Set-Content -LiteralPath .\gateway\devices.local.properties -Encoding utf8

.\scripts\start-gateway.ps1 -CredentialFile .\gateway\devices.local.properties
```

第一次执行该生成命令创建配置；已有本地设备凭证时直接使用现有文件启动，重复生成会更换凭证。启动入口会构建 Java 工程、执行测试并准备运行依赖，然后在当前终端运行服务，按 Ctrl+C 结束。

默认地址是 `127.0.0.1:9000`，可用 `-Port 其他端口` 覆盖。配置文件每行采用 `设备ID=凭证`，UTF-8 编码，只在启动时读取；示例中 ID 为 RB-001 / RB-002 / RB-003，设备需要使用相应凭证注册。

| 环境变量 | 默认值 / 用途 |
| --- | --- |
| `ROBOLINK_CREDENTIALS_FILE` | 必填，本地凭证文件；启动脚本的 `-CredentialFile` 会设置该项 |
| `ROBOLINK_HOST` | `127.0.0.1` |
| `ROBOLINK_PORT` | `9000`，`-Port` 可覆盖 |
| `ROBOLINK_REGISTRATION_TIMEOUT_MS` | `5000`，连接建立后的注册期限 |
| `ROBOLINK_HEARTBEAT_INTERVAL_MS` | `3000`，返回给设备的建议心跳间隔 |
| `ROBOLINK_HEARTBEAT_TIMEOUT_MS` | `10000`，注册后合法消息的空闲期限，必须大于心跳间隔 |

当前设备完成注册后可发送心跳、遥测、指令 ACK 和结果，后续消息必须带回网关分配的 session_id，并递增 seq。常驻模拟器启动方式见下文；没有页面时可运行验收入口检查真实控制。停止服务会关闭设备及控制连接并清理内存状态。

## 启用 WebSocket 控制通道

为控制端另外生成一份本地配置，不复用设备凭证。下面给 operator 分配三台设备的查看与控制权限；同样只在首次创建配置时执行：

```powershell
@(
  "operator.token=$([guid]::NewGuid().ToString('N'))"
  "operator.robots=RB-001,RB-002,RB-003"
) | Set-Content -LiteralPath .\gateway\controllers.local.properties -Encoding utf8

.\scripts\start-gateway.ps1 -CredentialFile .\gateway\devices.local.properties `
  -ControlCredentialFile .\gateway\controllers.local.properties
```

设备 TCP 默认 `127.0.0.1:9000`，WebSocket 默认 `ws://127.0.0.1:9001/control`，可分别使用 `-Port` 和 `-ControlPort` 更改。不提供控制端配置或相应环境变量时，保留仅 TCP 的启动方式。

仅验证桥接接口时运行：

```powershell
.\scripts\verify-control.ps1 -Clean
.\scripts\verify-protocol.ps1
```

控制端交互顺序为：WebSocket 握手带允许的 Origin → 第一条二进制消息 ControlAuthenticate → 收到 ControlAuthResult → Subscribe 获取快照与实时事件 → CommandRequest 发指令。每个完整 WebSocket Binary Message 只放一个 Envelope，不加 TCP 长度头。

握手路径必须是 `/control` 且无查询参数，默认允许 `http://localhost:5173` 和 `http://127.0.0.1:5173`。原生测试客户端也需提供 Origin。认证凭证放在首条消息中，不放在 URL。控制权限按配置中的设备集合统一授予查看与控制，不包含完整账号或角色管理系统。

| 环境变量 | 默认值 / 用途 |
| --- | --- |
| `ROBOLINK_CONTROL_CREDENTIALS_FILE` | 提供后启用控制端口，`-ControlCredentialFile` 设置该项 |
| `ROBOLINK_CONTROL_HOST` / `ROBOLINK_CONTROL_PORT` | `127.0.0.1` / `9001` |
| `ROBOLINK_CONTROL_ORIGINS` | 上述两个开发来源，英文逗号分隔、精确匹配 |
| `ROBOLINK_ACK_TIMEOUT_MS` | `2000`，从指令准入开始等待 ACK，包含本地调度时间 |
| `ROBOLINK_RESULT_TIMEOUT_MS` | `5000`，从接受 ACK 开始等待结果 |

默认最多 32 个控制连接、256 条未完成指令，未完成加短期记录合计 1024 条。终态保留 60 秒；满额拒绝新请求，不提前丢弃未过期的去重记录。每个控制端可靠队列最多 64 条，遥测按最多 64 台有权限设备合并最新值；持续输出阻塞 3 秒或可靠队列溢出会断开。

ACK 与结果只发给原 WebSocket 会话。已发送但超时或断连的指令结果不确定，网关不自动重发；新运动会被暂时阻止，仍允许 STOP。成功 STOP 或设备新会话可解除对应不确定状态；模拟器也会按本地截止时间停止并做短期去重。当前的短期记录不能提供永久或跨进程的“恰好一次执行”。

## 启动三台模拟机器人

先按上文启动带 WebSocket 的网关，在第二个终端运行：

```powershell
.\scripts\start-simulator.ps1 -CredentialFile .\gateway\devices.local.properties
```

该入口从项目虚拟环境启动 Python asyncio 模拟器。默认连接 `127.0.0.1:9000`，启动 RB-001、RB-002、RB-003，各自以 10 Hz 为目标发送遥测；显示三台设备已连接后保持运行，按 Ctrl+C 结束。

| 参数 | 默认值 / 用途 |
| --- | --- |
| `-Count` | `3`，凭证文件必须包含选中的每台设备 |
| `-IdPrefix` | `RB-`，编号从 001 开始 |
| `-TelemetryHz` | `10`，大于 0 且不超过 100；不是精确实时周期 |
| `-GatewayHost` / `-Port` | `127.0.0.1` / `9000` |

本地凭证使用简单的 UTF-8 `ID=token` 行，不支持 Properties 转义和续行；上文随机凭证生成方式可直接复用。模拟器不会输出凭证，也不需要控制端凭证。

每台设备接收有限前进 / 转向 / STOP 指令，先 ACK 再执行，结束后回传 Result。位置由模拟器根据实际经过时间计算，不能由未来的页面按钮直接改坐标。STOP 可中断运动；断连、心跳无回复或发送失败会本地停止。

每台设备独立自动重连，退避间隔带随机抖动、最大 5 秒；断线先停止动作，重新注册后保留当前位置，不恢复旧运动。其他设备继续运行。每台设备保留最多 256 条短期指令记录，结束后保留 60 秒；满额拒绝新指令，不承诺永久去重。断线、ACK 延迟及回包缺失的复现方式见[第六步](docs/STEP-06.md)。

验证模拟器和真实控制链路：

```powershell
.\scripts\verify-simulator.ps1
```

它回归原有 83 项 Java 测试，再运行 28 项 Python 测试，并临时启动真正的 Java 网关、三台 Python 设备和自动化 WebSocket 控制客户端，验证运动、隔离、重连、故障和网关重启恢复。无需手动启动服务，测试使用临时凭证，结束后清理。结果输出到 `artifacts/simulator/report.json`，不是性能压测。

## 单独启动与验证 Vue 网页

手动启动网关与模拟器后，第三个终端执行：

```powershell
cd console
npm run dev
```

输入本地控制凭证完成认证；凭证只保留当前页面内存，整页刷新后重新连接。设备和网页连接各自重试，快照恢复不重发运动。ACK 或结果超时后先 STOP，再决定是否发新动作。网页断线不能证明设备停止，设备仍依靠本地动作时限和设备链路检测。

网页位置与朝向均来自遥测。陈旧、离线和网页断连状态分别显示；运动按钮会受状态和参数范围限制，STOP 可中断运动。指令最多保留 100 条、通信消息最多 120 条，同一设备的遥测只保留最新一条。消息详情展示 Protobuf 解码后的字段，排除认证内容。

浏览器验收前先停止常驻演示，避免 5173 端口冲突：

```powershell
# 首次下载测试浏览器
cd console
npx playwright install chromium
cd ..
.\scripts\verify-console.ps1
```

包含 8 项状态测试、类型检查与构建、10 项真实 Chromium 测试，自动运行隔离的 Java / Python / Vue 服务。覆盖运动、STOP、超时、未知状态、消息解码、重连、认证和移动布局。常驻演示运行后还可执行 `node scripts/verify-demo.mjs` 检查实际一键启动链路。

## 协议代码生成策略

`.proto` 是协议源，生成代码不入库，也不手工修改：

- Java → `gateway/target/generated-sources/protobuf/java/`
- Python → `simulator/generated/`
- TypeScript → `console/src/generated/`

Maven 使用固定版本 protoc 生成 Java；统一脚本复用同一个可执行文件生成 Python 与 TypeScript。TypeScript 使用 `forceLong=bigint` 和 `oneof=unions-value`，得到精确整数与可区分的消息分支。

生成原理与选项参考 [Protobuf 官方版本说明](https://protobuf.dev/support/version-support/)、[ts-proto 官方说明](https://github.com/stephenh/ts-proto)；构建脚本使用 [Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/index.html)。

## 验证记录与边界

实际结果见[第一步验收](docs/acceptance.md)、[第二步验收](docs/acceptance-step-02.md)、[第三步验收](docs/acceptance-step-03.md)、[第四步验收](docs/acceptance-step-04.md)、[第五步验收](docs/acceptance-step-05.md)，以及[第六步](docs/STEP-06.md)、[第七步](docs/STEP-07.md)、[第八步](docs/STEP-08.md)。已验证真实本机通信与网页控制、三台软件设备运动和重连。没有实体硬件、持久化、真实网络丢包或并发性能成绩，网关重启会丢失指令历史。
