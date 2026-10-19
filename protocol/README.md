# RoboLink v1 协议约定

唯一协议源：`robolink.proto`。包名 `robolink.v1`，应用协议版本字段为 `1`。已实现 TCP 设备接入、WebSocket 控制入口、基础校验与指令状态机、模拟设备动作与短期去重，并追加可选的实际链路观测。

## 帧边界

TCP：4 字节无符号大端长度 + 一个 Envelope。长度只计算 Protobuf 消息体，合法范围 1–65536 字节，帧总长度上限 65540 字节。读取 TCP 字节流后必须先分帧，再解析 Protobuf。

WebSocket：一个完整 Binary Message 对应一个 Envelope，不附加 TCP 长度头。后续入口需要处理 WebSocket 分片聚合并限制消息体大小为 65536 字节。

三端互通样例 `.bin` 文件仅包含 Envelope 消息体，没有 TCP 头，可用于三端直接互解。第二步的 TCP 测试另行构造或生成长度头，不改变这些样例的含义。

TCP 入口当前拒绝零长度、超大长度、非法 Protobuf、不支持的版本和缺少 payload 的消息，并关闭连接。收到超大长度头后立即拒绝，不等待整个消息体；连接关闭后不继续交付同批数据中的后续消息。断线时未完成的帧由 Netty 清理。

发送方向先验证 Envelope，再使用 Protobuf 编码并加 4 字节大端长度头。Netty 的接收处理按添加顺序前进，发送处理按相反顺序经过相应组件；实现入口为 `gateway/src/main/java/dev/robolink/gateway/transport/TcpEnvelopeCodec.java`。

实现参考 [Netty 长度分帧组件](https://netty.io/4.1/api/io/netty/handler/codec/LengthFieldBasedFrameDecoder.html)和 [ProtobufDecoder](https://netty.io/4.1/api/io/netty/handler/codec/protobuf/ProtobufDecoder.html)。项目使用固定的 Netty 4.1.135.Final，实际行为以本项目测试结果为准。

## Envelope

| 字段 | 语义 |
| --- | --- |
| `protocol_version` | 必须为 1；不支持的版本由业务层拒绝 |
| `robot_id` | 设备消息的所属设备；设备级控制或事件的目标设备 |
| `session_id` | 网关认证成功后分配的设备连接代次，用于识别重连前后的数据 |
| `seq` | uint64，设备在当前会话内递增的发送序号；重连可重新开始 |
| `timestamp_ms` | uint64，发送端 Unix 毫秒时间；仅用于观察，不用于跨机器精确计时 |
| `payload` | oneof，一条消息只承载一种内容；空 payload 需由业务层拒绝 |

注册请求中 `robot_id` 非空、`session_id` 为空；注册成功后由 RegisterResult 的 `assigned_session_id` 返回会话标识，后续设备消息必须匹配绑定身份与会话。HeartbeatReply 的 `acknowledged_seq` 关联设备心跳序号。

当前 TCP 服务要求设备 ID 为 1–64 位 ASCII 字母、数字、下划线或连字符，首位为字母或数字。每个 ID 在本地凭证文件中单独配置凭证；认证失败返回 accepted=false 的 RegisterResult 并关闭连接，未注册连接发送其他消息则直接关闭。

注册成功后，设备允许发送 Heartbeat、Telemetry、CommandAck 和 CommandResult。同一连接不能重复注册，不能发送其他设备或其他会话的消息。seq 必须大于当前会话中上一条接受的设备序号，包含注册请求的序号；使用 uint64 无符号比较，不能在同一会话内回绕。重复和过期序号丢弃，不更新遥测或存活期限。

同 ID 的新连接认证成功后取得新会话编号并关闭旧连接；旧连接迟到消息不更新状态，旧清理不能移除新会话。注册等待默认 5 秒，设备建议每 3 秒心跳，注册后 10 秒没有合法消息关闭连接；合法遥测也可刷新存活时间。实际超时由网关本地计时，不取决于设备 timestamp_ms。

已实现的遥测校验包括电量 0–100、pose 存在、坐标与朝向为有限数值、运行状态为已知非 UNSPECIFIED 值。失败关闭连接。ACK / Result 的 command_id 必须符合下述编号约定，结果 outcome 必须为成功、失败或中断。坐标区域限制和运动执行留给后续实现。

订阅和整体快照不属于单台设备，Envelope 的 robot_id / session_id 可为空。Subscribe.robot_ids 为空表示订阅当前浏览器身份允许访问的全部设备。控制请求用 robot_id 指定设备，session_id 由网关按当前连接绑定，浏览器不能通过任意 session_id 冒充设备。

设备 seq 只在当前设备会话内比较，不把浏览器请求序号、网关通知序号和设备序号混为一个序列。消息转发时保留设备原始遥测 Envelope，快照则携带每台设备的 session_id 与 telemetry_seq。控制台应在设备会话变化时重置比较基线。

## 消息职责

- Register / RegisterResult：设备凭证校验、会话分配与心跳参数协商。
- Heartbeat / HeartbeatReply：应用层存活和响应确认。
- Telemetry：电量、位姿和运行状态。
- Subscribe / Snapshot：订阅范围与初始或重连后的设备快照。
- DeviceStatusChanged：设备上下线事件及网关观测时间。
- CommandRequest：受约束动作、参数和唯一 command_id。
- CommandAck：设备接受或拒绝指令，不表示执行完成。
- CommandResult：设备完成、失败或被中断的结果。
- CommandStatus：网关记录的指令阶段，包括发送、ACK、超时和结果未知。
- Error：请求关联 ID、机器可读错误码和可读说明。
- ControlAuthenticate / ControlAuthResult：独立控制身份认证、控制会话编号与可访问设备集合。

Register 和 ControlAuthenticate 的凭证不允许广播或写入日志；示例验证不使用任何真实凭证。控制身份使用单独配置，不使用设备注册凭证。

合法消息的空闲监测使用 [Netty IdleStateHandler](https://netty.io/4.1/api/io/netty/handler/timeout/IdleStateHandler.html)，安装在会话校验之后；注册期限另使用连接事件循环上的定时任务，不能被零散字节刷新。

## 数值与动作

- 平面 x/y：米；yaw：弧度，逆时针为正；前进按设备朝向运动。
- 电量：0–100，枚举的 UNSPECIFIED 不能当作合法业务状态或动作。
- MotionParameters 的速度与角速度是非负幅值；转向方向由 TURN_LEFT / TURN_RIGHT 决定。
- 前进要求 speed_mps 与 duration_ms，角速度为 0；转向要求 angular_speed_rad_s 与 duration_ms，线速度为 0；STOP 不携带 motion。
- 网关和模拟器均限制线速度 0.5 m/s、角速度 1 rad/s、持续时间 1–3000 ms，速度必须为正；非有限浮点数、越界或不匹配参数拒绝。Protobuf 本身不会执行这些业务校验。
- 成功写入连接、设备 ACK、设备执行完成是不同阶段。超时和断线不能证明动作没有发生。UNKNOWN / TIMEOUT 应向页面显示结果不确定。
- command_id 为 1–64 位 ASCII 字母、数字、下划线或连字符，首位字母或数字，推荐每次新操作使用 UUID。同 ID 不同参数拒绝；网关提供短期请求去重和忙碌限制，模拟器提供执行去重与 STOP 中断。

## WebSocket 控制约定

入口为 `/control`，拒绝其他路径或查询参数，Origin 必须在配置集合中。HTTP 升级前有绝对期限，升级后 5 秒内需认证。使用 Netty 的 [WebSocketServerProtocolHandler](https://netty.io/4.1/api/io/netty/handler/codec/http/websocketx/WebSocketServerProtocolHandler.html)处理握手及控制帧，[WebSocketFrameAggregator](https://netty.io/4.1/api/io/netty/handler/codec/http/websocketx/WebSocketFrameAggregator.html)聚合分片；聚合消息体仍不得超过 65536 字节。文本消息不支持。

第一条完整二进制消息必须为 ControlAuthenticate，Envelope 的 robot_id / session_id 为空；凭证在 payload 内。认证成功后 ControlAuthResult 包含 control_session_id 与 allowed_robot_ids。该控制会话编号不放进后续请求的 Envelope.session_id，后者由网关为设备路由确定。

认证后的客户端只发送 Subscribe 或 CommandRequest，session_id 必须为空。Subscribe 的 robot_id 为空，robot_ids 为空表示全部获准设备，包含离线设备快照；指定集合越权则 Error FORBIDDEN。CommandRequest 的 robot_id 指明目标，网关另行绑定当前设备会话。控制端不能发送 Register、遥测、ACK、结果或伪造其他服务端消息。

遥测保留设备原 Envelope 和 seq；设备上线 / 下线事件包含对应设备 session_id。网关创建的控制回复不使用设备的序号递增规则。客户端应按设备会话区分遥测，并按 command_id 跟踪状态，不能把各种消息的 seq 混作一个全局序列。

## 指令状态与保留规则

准入后 CREATED，成功完成本地发送操作后 SENT，设备接受后 ACKED，最终由设备 Result 决定 SUCCEEDED / FAILED / INTERRUPTED；网关校验或设备拒绝为 REJECTED。结果早于 ACK 结束为 UNKNOWN，不提前报告成功。

ACK 默认从准入开始等 2 秒，包含本地排队；接受 ACK 后另等结果 5 秒。超时分别为 ACK_TIMEOUT / RESULT_TIMEOUT，断线或换会话为 UNKNOWN，均不自动重试或根据迟到回包重新开启终态。ACK / Result 必须来自派发时绑定的设备会话，结果仅发给原控制连接，不广播给同身份的其他连接。

一台设备最多一条运动和一条 STOP 等待处理。运动等待中可发送 STOP，其余运动拒绝；已知遥测不是 IDLE 时也拒绝新运动。已派发但结果不确定时保留该设备的不确定标记，允许 STOP，成功 STOP 或新设备会话可解除标记。标记和超时都不等于已经确认设备停止。

同控制会话、同设备、同 command_id、同参数的重复请求只返回状态；不同参数或其他控制会话复用在途 / 已记录 ID 返回 Error COMMAND_ID_IN_USE。终态默认保留 60 秒，未完成加记录最多 1024 条；不为新请求提前逐出未过期记录。期限届满或进程重启后不承诺去重，模拟器另有独立的有界短期执行记录。

## 连接与指令观测

RobotSnapshot 的 remote_address、connected_at_ms、received_messages、sent_messages、last_heartbeat_at_ms 均由网关从真实设备 Channel 与事件采集。接收计数包含成功注册和通过会话/序号校验的设备消息；发送计数只在本地 TCP 写操作成功回调时增加，不代表对端读取或设备执行。每次新设备会话重新计数。telemetry_observed_at_ms 单独记录最后遥测时间，心跳不能把旧遥测变成新数据。

Subscribe.include_diagnostics 默认为 false，显式开启后，指令所有者可收到 CommandTrace；沿用设备权限，不广播给其他控制会话。四个网关 stage 为 GATEWAY_WS_RECEIVED（处理获准控制请求）、GATEWAY_TCP_WRITE_COMPLETED（成功本地写操作）、GATEWAY_ACK_RECEIVED、GATEWAY_RESULT_RECEIVED。Envelope 保留设备 ID / 会话，wire_seq 为相应实际线路消息的 seq；WebSocket 请求尚无设备序号时为 0。observed_at_ms 在对应处理点采集，不能由 CREATED / SENT 等状态拼造。

Telemetry.related_command_id 由模拟器附上最后接受的指令编号，便于关联返回遥测；它不是身份、去重或动作完成的授权依据，IDLE 也不能代替 CommandResult。拒绝或中断的指令不保证具有后续关联遥测。

机器人进程 PID 不通过设备协议作为可信身份。独立进程的 PID 与设备日志由本地监督程序采集，并与实际启动句柄核对；网页自身记录浏览器事件，三种来源分开展示。进程时钟与异步回调顺序不用于推算严格跨进程延迟。

每个控制端输出最多一条写操作在途，可靠队列 64 条、遥测按设备保留最新值且最多 64 项。队列满或输出持续阻塞 3 秒时断开，重新连接后需重新认证、订阅与同步快照，当前不支持跨控制连接恢复旧指令订阅。参考 [Netty 写缓冲水位](https://netty.io/4.1/api/io/netty/channel/WriteBufferWaterMark.html)，当前使用 32 / 64 KiB 水位，并有上述应用层队列限制。

## 跨语言类型

Java 的 uint64 使用有符号 long 保存原始位；最大值显示为十进制字符串时用无符号转换。Python 的 int 可精确表示该范围。TypeScript 使用 bigint；需要转成 JSON 展示时应按十进制字符串处理，不能直接 JSON.stringify(bigint) 或强制转换为 Number。

使用 oneof 表达互斥 payload，而不是同时维护独立 type 和 payload。Java / Python 在设置新分支时清除旧分支；TypeScript 生成 `$case` + `value` 的可区分联合。解析器遇到不识别的分支可能得到空 payload，业务层应拒绝或提示协议不支持。

## 演进规则

字段编号是二进制契约。已有字段不能随意改类型或复用编号；删除时将名称和编号标记为 reserved。新增可选字段时保留旧字段语义，变更业务约定需更新文档与互通验证。所有枚举均保留值为 0 的 UNSPECIFIED。

具体版本由 Maven POM、Python requirements 和 npm 锁文件固定。改变 protoc 或运行时版本后必须重新执行三端互通验收，不把跨版本兼容当作默认前提。
