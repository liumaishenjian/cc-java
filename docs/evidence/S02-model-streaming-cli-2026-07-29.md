# S02 Model + Streaming CLI Stage 退出证据

> Status：Accepted（G0-G6 Passed）
>
> Tested implementation：`700251e`
>
> Evidence classification：`COMMIT_VERIFIED`

## 1. Stage 范围

- Stage：S02 Model + Streaming CLI
- Required Features：ADR-021/ADR-023 定义的 19 项 L2 与 5 项 L1
- Reference Behavior Baseline：`R2026.03`
- Authorized Snapshot：`AUTH-SRC-2026-07-29-A`
- 接受偏差：ADR-031 当前 Provider/模型不保证同回合生成多个 Tool Call

## 2. G0-G3

- G0：公开坐标、授权快照、Provider 文本/Tool/Usage/Finish/取消能力和未知项已登记；
- G1：24 项目标、Java Headless、stdio v0、React/Ink 与非目标由 ADR-021/023 固定；
- G2：Spring AI 2.0.0、Boot BOM 4.1.0、Picocli 4.7.7、React 19.2.8、
  Ink 7.1.1 和 OpenAI-compatible Provider 已由 Spike 选择；
- G3：Domain/Core 保持框架无关，Runtime 掌握循环，TUI 只消费事件。

## 3. Commit-scoped G4 验证

在 Clean 的 `700251e` 上执行：

```text
./mvnw.cmd verify
./mvnw.cmd "-DskipTests" javadoc:aggregate
cd cc-java-tui
npm.cmd run check
java scripts/ProgressDashboard.java --check
java scripts/ProgressDashboard.java --self-test
```

结果：

| 范围 | 结果 |
| --- | --- |
| Domain | 1/1 |
| Core | 39/39 |
| Provider 普通测试 | 21 项执行通过；2 项真实网络测试默认跳过 |
| OpenAI-compatible SSE Contract | 4/4 |
| CLI | 29/29 |
| React/Ink | 22/22 |
| 聚合 Javadoc | 通过 |
| Progress Dashboard | check/self-test 通过 |

## 4. G5 真实行为

- 真实 Provider 文本流、单 Tool、Usage、Finish Reason 和 Timeout 已通过；
- Windows TTY 活动 Run：`running → [cancelled] → ready`；
- 空闲退出：`ready → closing`，进程退出码 0，`jps` 无 `CcJavaCliMain`；
- 本机 HTTP Fixture 覆盖 429、断流、length 和跨 Chunk 双 Tool；
- Java 子进程崩溃、取消/关闭超时与无孤儿进程证据通过；
- 真实 Provider 双 Tool opt-in 负例稳定只返回第一个调用，Adapter/SSE 双 Tool
  正例通过，按 ADR-031 接受为 Provider/模型能力偏差。

## 5. G6 对账

- 24 项 Stage 目标均达到 ADR-021 声明等级；
- 功能矩阵、README、AGENTS、技术设计、ADR、Demo、证据和差距报告已经同步；
- 本地 Provider 配置、API Key、构建产物和真实端点未进入提交；
- S02 G0-G6 均为 Passed，Stage Exit 为 Accepted；
- 下一阶段是 S03 Read Tools，必须先建立 S03 Gate 和可证伪 WorkspaceGuard/Read Tool
  实验，不能直接提前实现 S04 写入或命令能力。
