# 第一步验收记录

日期：2026-10-19（Asia/Shanghai）

状态：PASS。已完成两次清理生成目录后的完整验收，并验证环境准备入口可恢复锁定的 Node / Python 依赖。

## 本次环境

| 项目 | 实际值 |
| --- | --- |
| 系统 | Windows x64 |
| PowerShell | 7.6.5 |
| JDK | Eclipse Temurin 21.0.12.1+1，项目 `.tools/java/` 内安装 |
| Python | 3.12.0，项目 `.venv` |
| Node.js / npm | 24.19.0 / 11.17.0 |
| Maven / Wrapper | 3.9.9 / 3.3.4 |
| protoc / Java Protobuf / Python Protobuf | 33.5 / 4.33.5 / 6.33.5 |
| ts-proto / TypeScript | 2.18.0 / 5.9.3 |

本机原有 Java 17 / 22 未满足预定的 Java 21 运行验证目标，因此在项目目录内下载了官方 Temurin 21 发行包。安装前已核对官方下载信息中的 SHA-256，不修改全局 Java 设置。

## 验证方法

环境准备：`scripts/setup.ps1`。完整验收：`scripts/verify-protocol.ps1 -Clean`。

完整入口执行：清理生成目录 → 重新生成三端代码 → Java 编译与打包 → TypeScript 类型检查 → 三端编码 → 九组互解。Maven 本次没有 JUnit 测试用例，跨语言断言由三个独立验证程序执行，不把 Maven 构建成功计为业务功能测试通过。

每组使用遥测和运动指令两个样例，解码后与各语言独立构造的预期消息比较。序号 `18446744073709551615` 覆盖 uint64 最大值，时间戳 `9007199254740993` 覆盖 JavaScript Number 无法精确表示的整数。它们是边界测试数据，不是设备实际运行记录。

## 结果

| 编码端 → 解码端 | Java | Python | TypeScript |
| --- | --- | --- | --- |
| Java | PASS × 2 | PASS × 2 | PASS × 2 |
| Python | PASS × 2 | PASS × 2 | PASS × 2 |
| TypeScript | PASS × 2 | PASS × 2 | PASS × 2 |

- Java 21 编译与打包：通过。
- Java / Python / TypeScript 代码生成：通过。
- TypeScript 严格类型检查：通过。
- 9 组编码 / 解码组合、18 次消息比较：全部通过。
- uint64 精度、枚举、嵌套字段、两种 payload 分支及分支替换检查：通过。
- 清理 Java 构建输出、Python / TypeScript 生成目录和样例目录后重新验证：通过。
- 按 README 执行 setup，使用 npm ci 恢复依赖后完整复现：通过。

最新成功报告保存在 `artifacts/protocol/report.json`，本次结果已留存于 [evidence/step-01-protocol-report.json](evidence/step-01-protocol-report.json)。清理生成目录复验不等于清空全部依赖缓存后离线构建，首次安装仍需要网络。

## 未验证范围

本次不包含 TCP 分帧、网络收发、身份认证、心跳、断线重连、动作执行、WebSocket 或 Vue 页面，也未进行性能测试。Netty 依赖已配置但尚未调用。协议中的业务限制是后续实现要求，当前不会自动生效。
