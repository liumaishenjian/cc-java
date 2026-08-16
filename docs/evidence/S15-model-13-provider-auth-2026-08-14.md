# S15 MODEL-13 Provider/Auth Commit-scoped G0-G6 证据

- Date: 2026-08-14
- Stage: S15 Independent Innovation（OPEN）
- Status: `MODEL-13` L1 实现 Commit 已绑定；其后未提交工作树回归修复完成 G4 最终验证，S15 G6 / Stage Exit 仍 OPEN
- Release / Commit: `f0e274f779143164e0859961437a53acd220e7bd`
- Feature: `MODEL-13 L0 → L1`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`

## 来源分类与独立重实现

ADR-069 综合了官方公开文档（`Documented`）、授权参考快照及固定公开源码快照的受控机制观察（`Observed / Inferred / Unknown`），并记录授权、许可证、未知项和停止条件。ADR-070 将采纳结论冻结为本项目独立 Java/TypeScript 契约。

本实现只提炼职责、状态转换、安全边界、失败恢复与验证方法；未复制或逐行翻译参考函数体、Prompt、注释、错误文案、私有类型名、文件布局或常量，未将参考字节、Fixture 或 Golden Output 放入仓库。

## 实现架构与安全边界

Provider Definition、Credential Profile metadata、secret material、Run selection 与 live Gateway 保持分离。CLI/TUI 共用 `ProviderAuthApplicationService`，所选 Provider route 经 Spring AI 边缘 factory 组成单一 `ModelProviderRoute`，真实 Agent 请求仍只通过既有 `ProviderRouter` / `ModelGateway` 主链。

安全边界包括：

- 每个 Run 确定绑定一个 provider/profile/model/auth generation，不 silent rotation、fallback 或中途切换；
- secret 仅由 `STORE` 或 `ENV` 引用解析，不进入 Domain、Session、Canonical transcript、普通事件、日志、argv 或证据；
- Windows `user.home` owner 可能是 `SYSTEM`；`expectedOwner` 必须由当前 `user.name` 经文件系统 `UserPrincipalLookupService` 解析，并以 `UserPrincipal.equals`（Windows 对应 SID 身份）验证，禁止使用 home owner 或字符串猜测；`.cc-java` 共享根不要求 owner-only，且绝不自动修改真实用户根 ACL；
- owner-only 从 `auth` 及其所有 credential/file/temp/lock/txn 开始，实际 `providers.v1.json` 文件也必须 owner-only；这些对象出现多余 principal 时 fail closed；普通文件存储不宣称 OS vault；
- local list/status/models 保持零网络；只有显式 probe 才允许一次、有界、可取消且隐私安全的 Provider 网络验证；
- generation lease、logout fence、active Run 取消与资源 drain 防止删除 credential 后继续获取新 lease；本地 logout 不冒充 Provider 侧 remote revoke；
- TUI transport failure 不再被一般 `closed` 状态覆盖或触发自动退出；界面保留隐私安全摘要并等待用户按 `Ctrl+C` 退出，不展示 Java stderr 正文；
- Provider/Auth 应用层控制不等同于 Tool Permission 或 OS Sandbox。

## G0-G5

| Gate | 证据 | 状态 |
| --- | --- | --- |
| G0 | ADR-069 已固定公开来源、授权快照、来源分类、许可证/未知项、停止条件和独立重实现边界 | PASSED |
| G1 | `MODEL-13` 的 Provider/Auth、ENV/STORE、多 profile、选择、probe、logout 范围及 L2 在线门槛已固定 | PASSED |
| G2 | ADR-070 已固定模块职责、数据格式、优先级、状态机、竞态、隐私、错误和验收契约 | PASSED |
| G3 | restricted store、application service、CLI/TUI、Provider factories、单 route composition、probe、lease/logout 已生产接入 | PASSED |
| G4 | 2026-08-15 correctness closeout 固定向导持久默认、正式 `models.add/remove/use` exact result schema、真实 StdioClient child 协议、custom picker 重开/稳定排序、saving-provider side-effect lock 与 active-run add fail-closed。聚焦 Java 53/53、TUI 11 files 194/194；非 clean Maven verify 1028 tests/13 skips/0 failures/errors；strict aggregate Javadoc 0 warning。clean verify 仅因用户现有 codej PID 17212 锁定 domain JAR 在 clean 阶段失败，未终止该进程 | PASSED_WITH_RECORDED_CLEAN_BLOCKER |
| G5 | 离线 Demo 与负例完成；至少两个 distinct Provider 的真实 BYOK text stream、Tool call、cancel、auth-negative 在线 E2E 尚缺 | PARTIAL |
| G6 | 实现 Commit `f0e274f779143164e0859961437a53acd220e7bd` 已绑定；其后回归修复仍在未提交工作树，S15 整体 G6 因双 Provider 在线证据与 L4 Eval 未完成而保持 OPEN | OPEN |

## 2026-08-15 Provider 向导 correctness closeout

本轮修复 Codex review 确认的六类真实 correctness 缺陷：

- 普通向导 login request 使用严格可选 `setDefault:true`，CLI bridge 只在 true 时追加 `--set-default`；旧带参数请求省略该字段时保持非默认兼容语义。向导 `models.use` 精确发送 `setDefault:true`，Java store 重开测试证明默认 profile/model 可恢复。
- TypeScript 正式白名单与 Java 对齐，严格接受 `models.add/remove/use` exact result，add/use 的 `setDefault` 必须为 boolean。`StdioClient` 对 fake stdio child 发出三个真实 control request 并消费 Java 形状结果，连接保持可用、ProtocolViolation 为 0。
- Provider picker 从安全 models/profiles 投影提取最多 32 个 custom ID，去重并稳定排序；移动与 Enter 共用纯 helper，已有 custom 直接进入 management/auth，最后一项才是添加入口。
- `saving-provider` 期间 Enter/Esc 均 no-op 且明确显示等待；重复键只发一次 add。成功后标记已保存，从 auth Esc 仅回 picker；失败才回确认页。
- `addCompatibleProvider` 在 active Run 时使用既有 `AUTH_TRANSACTION_CONFLICT` fail closed；测试证明 active 时 store 文件不存在且 catalog 无新增，Run 结束后同请求成功落盘。
- 所有向导异步等待页均显示等待并保持副作用状态；footer 继续固定“正在配置连接，请按上方提示操作”。

验证：聚焦 Java **53/53**；TUI `npm test` 与 `npm run check` 均为 **11 files、194/194**；非 clean `mvnw verify` 汇总 **171 reports、1028 tests、13 skips、0 failures/errors**；strict aggregate Javadoc **0 warning**。`clean verify` 在 `cc-java-domain` clean 删除 JAR 时因用户现有 codej 进程锁定失败，未终止该进程，故不宣称 clean 全量通过。`git diff --check` 无 whitespace error（仅 LF→CRLF 提示）；credential scan 的唯一初筛命中是既有测试假 sentinel，人工确认不是真实 secret。

## 2026-08-15 自定义 Provider TUI corrective slice

本切片移除已被真实 C 端验收否定的 CLI fallback：`provider.control` 新增严格白名单 `providers.add`，
TUI 完整实现自定义 compatible 服务配置、认证和模型选择。该切片当时初步聚焦回归为 Java 53/53、TUI 11 files、
186/186；随后本轮 correctness closeout 已以 TUI 194/194 和非 clean Maven verify 1028 tests 取代其最终测试数字。strict aggregate Javadoc 0 warning、Dashboard generate/check/self-test、
`git diff --check` 与变更文件 credential-pattern scan（64 files、0 hit）通过。完整 `clean verify` 第一次在 clean
阶段因用户已运行的真实 `codej` Java 进程占用 `cc-java-domain` JAR 而失败；为避免终止用户进程，本次未强制清理，
故该项仍待维护者关闭现有会话后复验。`MODEL-13` 仍为 L1，S15 仍为 OPEN；未运行或伪造双 Provider 在线证据。

## 2026-08-14 最终工作树回归

本轮验证针对实现 Commit `f0e274f779143164e0859961437a53acd220e7bd` 之后尚未提交的回归修复：配置入口可发现、模型 deadline/cancel、Print/TUI transport 收敛与非交互 watchdog、Windows ACL/current principal，以及严格 Javadoc。Capability Level 无变化；下列结果是工作树证据，不描述为 commit-scoped：

| 验证项 | 结果 |
| --- | --- |
| `.\mvnw.cmd clean verify` | PASS；1022 tests / 13 skips / 0 failures / 0 errors；171 个 Surefire XML 独立汇总 |
| `npm --prefix cc-java-tui run check` | PASS；11 files、184/184；从真实 AgentTui 键盘输入 `/connect`，证明首屏只含“连接模型服务”、Anthropic/OpenRouter/自定义高级项与简短状态，不含开发者 dump；覆盖 ↑/↓、Enter、Esc、API Key 自动 `default` profile 且 secret 不进入 TUI、ENV 只传名称、成功刷新并进入模型选择、models.use 完成页、已连接管理与 logout 二次确认、auth/models 任意顺序/失败/迟到/第二代隔离、短窗口 Composer，以及独立 `/auth` 与 `/models` 高级接口兼容 |
| launcher | PASS；60 assertions |
| strict aggregate Javadoc | PASS；0 warning |
| 空配置 production stdio | 空 home/profiles 在 1 秒内形成唯一 `configuration_required`；Print 给 `/connect` 或 `codej auth login` 指引；`provider_error` 保持服务故障提示 |
| 真实 legacy Print deadline | 本机存在 ignored legacy Provider 配置；`codej --print "只回复OK" --timeout 2s` 约 9324ms 后 exit 1；恰好一次 `cc-java: run timed out`；新增 Java/Node residue 0；仅为 deadline/Surface grace/shutdown 收敛证据 |
| 真实安装版共享根 | `providers` / `auth` / `models` 均 exit 0；root ACL 前后不变；`auth` protected 对象仅 owner |
| production stdio | `initialize` / `shutdown` exit 0；stderr 0 |
| 临时 home ENV/STORE 生命周期 | 全部 exit 0；metadata secret 命中 0；logout residue 0 |
| Provider 子命令 help | 全部 exit 0 |

Windows `user.home` 的 owner 可能是 `SYSTEM`，因此不能用 home owner 推断当前用户。修复后的安全契约要求从当前 `user.name` 经文件系统 `UserPrincipalLookupService` 解析 `expectedOwner`，再使用 `UserPrincipal.equals`（Windows 对应 SID 身份）比较；不得使用 principal 名称字符串猜测。共享 `.cc-java` 根不被自动改 ACL，`auth`、credential/file/temp/lock/txn 与实际 `providers.v1.json` 的 owner-only 约束保持不变。TUI transport failure 保持可见隐私安全摘要，不被 `closed` 覆盖或自动退出，用户以 `Ctrl+C` 退出，Java stderr 正文不进入界面。

短窗口真实 TTY/PTY 自动化未取得可用 harness：当前工具调用 stdin 不是 TTY，`winpty` 因此拒绝启动交互 codej；仓库现有脚本也没有可由本会话驱动并捕获固定 rows 的 PTY harness。本轮只把 Ink renderer 的确定性 `rows=8/9/12` 组件测试记录为布局证据，不把它伪报为真实 TTY。维护者仍需在原生短窗口人工启动 `codej`，确认 Composer 可见并输入无参数 `/connect` 观察本机状态输出；不得输入真实 key。

临时 home E2E 未执行 probe 或任何模型网络请求，不构成真实 Provider 在线证据。本轮未读取真实本地 secret 配置，也未使用、记录或输出真实 key、endpoint、用户名或旧会话内容。

## 等级与 Stage 边界

`MODEL-13` 仅提升至 **L1**。双 Provider 真实在线证据缺失，因此不得提升至 L2，也不得把离线 Demo、loopback 或 fake ENV E2E 表述为在线 BYOK 证据。

同一工作树中的 `TOOL-18` 保持 **L2**，本证据不改变其等级。S15 Stage Exit 继续 **OPEN**；G6 留待 implementation commit-scoped 收尾。
