# ADR-054：S09 Hook Settings/Trust 指纹 Gate 第一切片

- Status: Accepted
- Date: 2026-08-08
- Stage: S09 Hooks
- Features: `HOOK-12`、`HOOK-13`
- Depends on: ADR-051、ADR-052、ADR-053、S08 Settings 契约

## Context

S09 已经有独立的 Hook 协议、Coordinator 和受控 Command Adapter，但 `HookBinding.trusted`
仍需要一个可复验的来源决策。若把项目配置默认当作可信，工作区文本就可能在模型运行前启动
外部进程；若把信任逻辑埋在 Command Adapter，又会绕过 Core 的统一失败策略。

## Decision

1. `HookSourceKind` 使用项目自有的 DEFAULTS、USER、PROJECT_SHARED、PROJECT_LOCAL、SESSION、CLI
   六层来源，避免让 Core 依赖 Settings 文件格式。
2. Composition Root 对规范化的 Hook 声明组件使用长度前缀编码计算 SHA-256 指纹。指纹只用于
   比较，不能替代 Workspace、Permission、进程生命周期或 OS Sandbox。
3. DEFAULTS、USER、SESSION、CLI 在指纹格式合法时可以进入 trusted；PROJECT_SHARED 和
   PROJECT_LOCAL 必须同时满足 Workspace 已显式信任、Trust Store 存在精确批准指纹、当前指纹
   与批准值一致。
4. Gate 只返回带安全 `trusted` 标记的 `HookBinding` 和无正文的 TrustStatus，不启动 Handler，
   不读取文件，也不修改 Settings。未通过时交给已有 `HookCoordinator`，由绑定的 FAIL_OPEN、
   FAIL_CLOSED 或 OBSERVE_ONLY 决定是否阻断。

## Verification

- `HookTrustGateTest` 覆盖项目 Workspace/批准/指纹变化、非法指纹、用户/Session 来源；
- 长度前缀指纹对等价输入稳定，并能区分边界歧义；
- 未信任项目绑定经过 Coordinator 时不启动 Handler，FAIL_CLOSED 产生结构化阻断；
- 本切片不声明 Settings schema、user/project/local 文件加载、Trust UI 或生产 Composition
  Root 已可用，这些仍属于后续 S09 差距。
