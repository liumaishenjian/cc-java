# ADR-079：S15 自适应交互预算与类型化 Tool 失败治理

- Status: Accepted
- Date: 2026-08-21
- Stage: S15 Independent Innovation（Batch 4）
- Feature IDs: `LOOP-07`、`TOOL-10`、`TOOL-13`、`TOOL-18`、`PERM-05`、`OBS-04`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 授权快照机制为 `Observed / Inferred / Unknown`；本项目契约为 `Documented`；能力等级不变

## 背景

历史默认预算把普通交互 Run 固定在 16 个模型回合和 32 个 Tool Call。该上限适合作为早期
离线骨架，却会在仍持续产生成功 Tool Result 的真实调查或执行任务中提前终止。与此同时，失败
目前主要以细粒度错误码表达，Runtime 无法稳定识别“同一 Tool、同一规范参数、同一失败类别”
的重复请求；Web 403、命令非零退出和传输故障也没有统一的跨 Adapter 失败语义。

本轮继续保留取消、墙钟、Token/Context、单结果输出和 Surface 显式上限。目标不是取消安全边界，
而是区分用户显式硬上限与普通交互的进展感知软预算，并在 Runtime 层阻止没有策略变化的重复失败。

## 受控研究结论

本轮在仓库外只读复核了授权快照中的交互主循环、可选最大回合、模型与网络重试、Web Fetch/Search
HTTP 失败、Shell 退出状态及 Tool 失败反馈。只提炼职责、状态、不变量和验证方法，未复制函数体、
Prompt、注释、错误文案、私有名称、布局、常量、Fixture 或字节。

| 结论 | 分类 | 本项目采纳 |
| --- | --- | --- |
| 交互主循环没有必要默认携带一个很小的强制最大回合；显式调用方上限仍会被检查并形成可见终止 | Observed / Inferred | Surface 区分 `EXPLICIT_HARD` 与 `INTERACTIVE_ADAPTIVE`，显式 API 保持硬上限 |
| 模型重试、Tool 重试和模型再次提出同一 Tool 是三种不同机制，不能混为一次 Adapter retry | Observed | Adapter 只重试瞬态传输/HTTP；Runtime 对跨模型回合重复 Tool 失败做 fingerprint 治理 |
| Web Fetch 能观察 HTTP 状态与部分受信响应头，但认证、UA/ACL 和普通 forbidden 并非总能可靠区分 | Observed / Unknown | 403 统一为非重试 `HTTP_FORBIDDEN`，仅在受信 Adapter 信号存在时附加安全 reason code |
| 429 与服务端错误可以在网络 Adapter 层有界退避；授权、权限和普通 4xx 不应盲重试 | Observed / Inferred | Web Adapter 使用可注入等待器和固定尝试上限；Runtime 不重复实现 HTTP backoff |
| Shell 非零退出是进程级失败证据，不能靠 stdout/stderr 文本猜测；HTTP CLI 是否失败取决于命令本身退出语义 | Observed / Inferred | `run_command` 非零退出映射 `PROCESS_EXIT`；指导 HTTP shell 使用显式 fail-with-body，Runtime 不抓 HTML |
| 参考快照主要通过模型反馈要求改变策略；未观察到可直接采纳的通用失败 fingerprint 状态机 | Unknown | fingerprint coordinator 是本项目独立、可证伪治理，不声明为参考内部机制 |

## 决策

### 1. 预算来源与软治理

`AgentLimits` 增加预算策略：

- `EXPLICIT_HARD`：CLI/API/SDK/Daemon 明确提供的回合和 Tool 上限，达到即终止；
- `INTERACTIVE_ADAPTIVE`：普通 Interactive/Default/Auto/Plan/approved-plan Run 使用。16/32 是软检查点，
  每个成功且参数不同或结果进展的 Tool 批次续租预算；仍保留更高的绝对回合/Tool ceiling、墙钟和
  其他既有硬预算。连续无进展或连续失败不能续租；终止或续租产生类型化 governance 事件和原因。

兼容构造器继续创建显式硬限制，避免测试、Sub-Agent、稳定协议和 API 的既有调用被悄悄放宽。
只有交互 Composition Root 显式选择 adaptive policy。

### 2. Tool 失败分类

`ToolError` 增加独立 `ToolFailureCategory` 与 `retryable`。分类至少覆盖
`AUTHORIZATION`、`PERMISSION`、`HTTP_FORBIDDEN`、`HTTP_CLIENT`、`HTTP_RATE_LIMIT`、
`HTTP_SERVER`、`TRANSPORT`、`PROCESS_EXIT`、`VALIDATION`、`EXECUTION`、`CANCELLATION`、
`TIMEOUT`、`OUTPUT_LIMIT`、`PROTOCOL`、`INTERNAL`。retryable 只由 Domain 能独立证明的分类和
Adapter 明确信号设置；403、授权、权限、校验和进程退出默认不可重试。

既有细粒度 `ToolErrorCode` 继续用于纠正具体参数；taxonomy 是正交治理维度，不删除兼容错误码。

### 3. 重复失败 fingerprint

每个 Run 拥有独立 coordinator。fingerprint 由规范 Tool 名、递归排序且类型保真的 JSON 参数摘要、
以及 typed failure category 组成，不读取错误 prose、stdout/stderr、网页正文或 Secret。第一次失败
正常反馈；同 fingerprint 再次出现时，Runtime 不执行 Adapter，返回 `REPEATED_FAILURE` 与结构化
strategy feedback，要求改变 query/provider/source/arguments 或向用户解释阻塞。改变参数、成功结果、
不同失败类别和不同 Tool 不受惩罚。fingerprint 只存在于当前 Run，不写入 Session Permission Grant。

该 Gate 位于 Pipeline 参数校验之后、Pre Hook/Permission/执行之前；因此重复调用不会再次出站、启动
进程或请求审批，同时仍生成唯一 Tool Result、Call ID 和生命周期终态。

### 4. Web HTTP 治理

Web Adapter 对 403 映射 `HTTP_FORBIDDEN` 且不重试；只有受信响应头或本地配置能明确证明时，details
可使用 `AUTHORIZATION_REQUIRED`、`USER_AGENT_OR_ACL` 或 `FORBIDDEN`，不得记录 Header 值、credential、
query 或正文。429 和 5xx 只在 Adapter 内以共享 deadline/cancel、固定最大尝试、封顶退避和可注入 sleeper
重试；普通 4xx、重定向、协议/媒体类型/大小错误不重试。模型再次提出相同 403 是第二次 Tool Call，
不是 HTTP retry；Runtime fingerprint Gate 将其转为策略反馈。改变 query 允许执行。

### 5. Process 与 AutoReview

`run_command` 退出码非零返回 Pipeline `FAILURE / PROCESS_EXIT`，details 仅含数值 exit code；正文仍保留
已裁剪 stdout/stderr、timeout/cancel 元数据。执行 curl 等 HTTP CLI 时 Tool 描述明确建议使用能让 HTTP
错误产生非零退出且保留响应正文的选项；Runtime 不解析 HTML 或 stderr 自由文本来猜 HTTP 状态。

AutoReview fast path 仍只作用于 Permission 已经收敛为 `ASK/EFFECT_DEFAULT` 的可信 builtin 调用；Hard
Denial、显式 deny、source/trust 不匹配和 Hook deny 在它之前终止。重复失败 Gate 也在新的调用进入
Permission 前运行，因此 fast path 不能覆盖 typed failure governance。

## 可证伪验证

- adaptive 交互 Run 完成超过 32 次持续成功的不同 Tool Call，且达到绝对 ceiling/无进展时有明确原因；
- 兼容/显式 `AgentLimits` 在原配置上仍精确停止；
- 相同 Tool+规范参数+403 第一次执行、第二次阻断，改变 query 后允许；
- 429/5xx 的 Adapter 尝试数、退避和取消由 Fake clock/sleeper 确定性验证；403 尝试数恒为 1；
- `run_command` 非零退出是 `FAILURE/PROCESS_EXIT`，输出裁剪和取消回归不变；
- AutoReview 不能覆盖 Hard Denial、显式 deny、trust/source 或 repeated-failure Gate；
- Session permission、Plan、Hook/MCP/Plugin 唯一 Pipeline、Context compaction、唯一 final/lifecycle 全部回归。

## 明确差距

Web Fetch 尚未作为独立生产 Tool 接入；真实站点对 403 的原因经常不可观察，不能凭正文或品牌文案猜测。
本轮不建设全局跨 Session failure cache、Provider 自动切换、无限预算或 OS 网络 Sandbox。能力等级保持不变，
待完整 G4/G5 与真实 Provider/网络证据后再评估。
