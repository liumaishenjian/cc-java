# S08 显式文件引用补充证据

> Stage：S08 Supplementary / Reopened
>
> Feature：`CLI-13`、`CTX-19`
>
> 工作树基线：`b8a2b562293f7df83ddacfe8214aecd8bd545092`
>
> Baseline：`R2026.03`；授权快照：`AUTH-SRC-2026-07-29-A`
>
> 当前结论：G0-G5 Passed；G6 等待独立实现 Commit，对应能力暂保持 L1

## G0-G2：研究、产品与架构契约

- G0：按 ADR-022/ADR-049 受控研究异步候选、提交时重解析、结构化附件、路径安全、行范围、预算和 Session 快照职责；未复制参考源码表达、常量、Prompt 或布局。
- G1：冻结 `@path`、`@"path with spaces"`、`#Lstart[-end]`，以及数量、行、字节、查询、候选与事件上限。
- G2：冻结 Domain 不访问文件系统、CLI/WorkspaceGuard 权威解析、Canonical/JSONL 保存不可变附件、Adapter 只做确定性不可信信封、TUI 不直接读文件的边界。

## G3：实现

- `UserFileAttachment` 与 `UserMessage.attachments` 保存协议相对路径、UTF-8 快照、SHA-256、行范围与截断标记；文本构造器保持兼容。
- `FileMentionService` 在创建 Run、写 Session、Canonical append 或模型请求前完成解析；拒绝绝对路径、traversal、Symlink/Junction 逃逸、敏感/非普通/二进制/坏 UTF-8/超限文件，并执行读前读后 realpath、属性和 identity 检查。
- `run.started` JSONL 使用严格有界附件 Schema；旧记录缺少 `attachments` 时按空列表读取，Resume/Fork 保持提交时快照。
- Spring AI Adapter 使用确定性 `cc-java-user-file-context-v1` Base64 不可信信封；Context Estimator 保守计入 Base64 展开与结构开销。
- stdio `file.suggest`/`file.suggestions` 使用 256 code-point 查询、32 项候选和完整 NDJSON 事件 8,192-byte 上限；候选不启动 Run、不改 Session。
- React/Ink 对活动 token 去抖请求并按 request/session/query/token identity 丢弃 stale 响应；Java 传原始安全相对路径，TUI 转换为可提交的引号/非引号 mention，并只替换光标所属区间。

## G4：自动验证

```text
.\mvnw.cmd -pl cc-java-domain,cc-java-core,cc-java-model-spring-ai,cc-java-cli -am test
npm --prefix cc-java-tui run check
```

- Java：完整 Reactor Domain 52、Core 173、Spring 45（2 skipped）、Tools 158、CLI 261，0 failures/errors。
- TUI：10 files，128/128；开发启动器 59/59。
- 新增或扩展用例覆盖路径/预算/UTF-8/二进制/引号/行范围/旧 JSONL/Resume/Fork/模型信封/零 Run mutation/协议负例/Unicode 光标/过期响应/steering。

## G5：审查与行为对照

- 两路实现 worker 分别完成 Java 与 TUI，第三路只读 review 找到并证伪了两个真实集成缺陷：无界 `readAllBytes` 与 Java 裸路径/TUI `@` 前缀协议错配；协调审查已修复并重跑测试。
- 后续审查又统一 256 查询上限、完整事件预算、严格附件字段、Base64 Token 估算、提示长度先验校验、候选协议路径格式化及既有 assembly 条件的运算符可读性。
- 最终协调审查按 ADR-050 修复了整文件快照的有界读取、范围扫描硬 ceiling、真实 LRU、BOM 与规范路径去重、提交阶段二次内容一致性比较、stdio 失败请求清理，以及既有 Fake Patch 流程缺少先读证据的问题；相关回归和完整 Reactor 已重跑。
- [可复现 Demo](../demos/S08-explicit-file-mentions.md)覆盖补全、引号路径、行范围、拒绝无副作用与 Session 快照。

## G6 与能力等级

本文件记录的是未提交工作树证据，不冒充 Commit-scoped Stage Exit。`CLI-13`、`CTX-19` 在独立实现 Commit、看板摘要复验和最终对账前保持 L1；既有 S08 Accepted 基线不被撤销，但 Supplementary 状态保持 Reopened。

本工作树同时包含维护者先前加入的 `OpenAiCompatibleModelFactory` timeout 修改；该文件不属于 ADR-049 实现范围，也未被本证据宣称为文件引用变更。
