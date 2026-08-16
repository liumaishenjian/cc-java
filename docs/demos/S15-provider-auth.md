# S15 Provider/Auth 中文 Demo

- Date: 2026-08-14
- Feature: `MODEL-13 L1`
- Stage: S15 `OPEN`
- 范围：本地 Provider、credential metadata 与模型目录控制面；不含真实 API key

## 安全前提

以下流程只使用示例环境变量名称 `CC_JAVA_S15_DEMO_API_KEY`，文档不提供、读取或记录任何真实值。`providers list`、`auth list/status` 和 `models list/add/remove/use` 都是本地操作，不应发起 DNS、HTTP 或模型请求。

`auth probe` 是显式在线操作：只有操作者明确执行时才会尝试连接所选 Provider。执行前应确认对应环境变量在当前终端中由操作者自行安全配置；不要把 key 写进命令、终端历史、Demo、日志或仓库。

## CLI 可复现流程

在已安装 `codej` 的终端中执行：

```powershell
codej providers list

codej auth login `
  --provider anthropic `
  --profile s15-demo `
  --from-env CC_JAVA_S15_DEMO_API_KEY `
  --set-default

codej auth list --provider anthropic
codej auth status --provider anthropic --profile s15-demo

codej models list --provider anthropic
codej models add --provider anthropic --model s15-demo-model
codej models use --provider anthropic --model claude-sonnet-4-6 --profile s15-demo
codej models remove --provider anthropic --model s15-demo-model
```

只有需要显式验证 credential 时才执行下列命令；它会联网，不属于离线复现步骤：

```powershell
codej auth probe `
  --provider anthropic `
  --profile s15-demo `
  --model claude-sonnet-4-6
```

完成后显式删除本地 profile：

```powershell
codej auth logout --provider anthropic --profile s15-demo --yes
```

`logout --yes` 只删除本地 credential，不会撤销 Provider 侧的 key；如需撤销，应由操作者在 Provider 官方控制台中完成。

## TUI 流程

启动 TUI 后，普通用户输入 `/connect`：

```text
连接模型服务
→ 选择 Anthropic 或 OpenRouter
→ 选择“粘贴 API Key（推荐）”或“使用环境变量（高级）”
→ 登录成功后选择模型
→ 看到“已连接 / 已选择 / 可以开始对话”
```

API Key 由一次性 Java masked Console 读取，Ink/Node 不接触 secret；ENV 页面只输入变量名称。已连接 Provider
再次进入会看到“选择模型 / 更新凭证 / 退出登录（高级）”，logout 必须二次确认。自定义 OpenAI-compatible
服务在“添加自定义服务（高级）”中依次输入名称、稳定 ID、HTTPS Base URL、模型并确认；保存中会显示“正在保存，请稍候”，Enter/Esc 不会离开或重复提交。保存成功后立即进入相同的 masked API Key/ENV、credential 刷新、模型选择与完成页。重新打开 `/connect` 时，已有 custom 会以“自定义 · <id>”稳定排序显示，选择它会直接进入管理或认证，不重复新增。普通向导登录与模型选择均持久设为默认，重启后仍可恢复。

以下带参数命令继续作为高级/脚本接口：

```text
/connect anthropic s15-demo env CC_JAVA_S15_DEMO_API_KEY
/auth list
/auth probe anthropic s15-demo claude-sonnet-4-6
/models anthropic
/models add anthropic s15-demo-model
/models use anthropic claude-sonnet-4-6 s15-demo
/models remove anthropic s15-demo-model
/auth logout anthropic s15-demo confirm
```

`/auth probe` 与 CLI `auth probe` 一样是显式联网操作。STORE 登录继续使用继承终端的 masked Console：

```text
/connect anthropic s15-store-demo
```

## 预期安全结果

| 操作 | 预期结果 |
| --- | --- |
| `providers list` | 只展示非秘密 Provider catalog，不展示 credential |
| `auth login --from-env` | 只持久化示例环境变量名称引用，不把环境变量值写入 profile metadata |
| `auth list/status` | 只展示 provider、profile、引用类型和本地状态；环境变量未设置时可安全报告 `MISSING_SECRET` |
| `models list/add/remove/use` | 只维护本地模型目录与选择；向导 use 持久设为默认并可在 store 重开后恢复；不做 remote discovery，不联网 |
| 显式 `auth probe` / `/auth probe` | 最多按受控 probe 契约访问所选 Provider；不回显 key、认证 Header、响应正文或 endpoint 细节 |
| STORE `/connect` | 使用继承终端的 masked Console |
| Windows `user.home` owner 为 `SYSTEM` | `expectedOwner` 由当前 `user.name` 经文件系统 `UserPrincipalLookupService` 解析，并以 `UserPrincipal.equals` / SID 身份验证；禁止使用 home owner 或字符串猜测 |
| 既有共享 `.cc-java` 根含额外只读 principal | `providers/auth/models` 继续 exit 0；根 ACL 不变，不自动收紧真实用户根 |
| `auth`、credential/file/temp/lock/txn 或实际 `providers.v1.json` 含多余 principal | fail closed；受保护对象只允许 owner |
| TUI transport failure | 保留隐私安全摘要，不被 `closed` 覆盖、不自动退出；等待 `Ctrl+C`，不展示 stderr 正文 |
| `logout --yes` / TUI confirm | 删除本地 profile；不宣称已远端 revoke |

任何错误输出都应保持结构化且隐私安全，不包含 API key、环境变量值、认证 Header、Provider 响应正文、完整 URI、秘密文件路径或堆栈。

## 已有验证与事实边界

2026-08-14 已通过最终本地/安装形态回归：真实安装版共享根上的 `providers`、`auth`、`models` 均 exit 0，根 ACL 前后不变，受保护 `auth` 对象仅 owner 可访问；production stdio `initialize`/`shutdown` exit 0 且 stderr 0。仓库 ignored 的临时 home 中，ENV 与 STORE 两条全生命周期全部 exit 0，metadata secret 命中 0，logout residue 0；所有 Provider 子命令 help 均 exit 0。该回归没有执行 probe 或模型网络请求，不构成真实 Provider 在线证据。

本轮验证为：聚焦 Java **53/53**；非 clean Maven verify **1028 tests / 13 skips / 0 failures / 0 errors**（171 个 Surefire XML 独立汇总）；strict aggregate Javadoc **0 warning**；完整 TUI check **11 files、194/194**。Maven `clean verify` 在删除 `cc-java-domain` JAR 时因用户现有 codej PID 17212 锁定失败，未终止该进程，因此不宣称 clean 全量通过。真正空 home/profiles 的 production stdio 在 **1 秒内**形成唯一 `configuration_required`，Print 给出 `/connect` 或 `codej auth login` 指引；`provider_error` 使用独立的服务故障提示。本机存在 ignored legacy Provider 配置，真实 `codej --print "只回复OK" --timeout 2s` 约 **9324ms** 后 exit 1，恰好一次 `cc-java: run timed out`，新增 Java/Node residue 0；它只证明 deadline/watchdog/关闭收敛，不是空配置证据。TUI transport failure 的保留行为仍通过。

至少两个 distinct Provider 的真实在线 BYOK E2E 尚未执行，因此没有双 Provider 的 text stream、Tool call、cancel 与 auth-negative 在线证据。`MODEL-13` 保持 **L1**，不得提升到 L2；S15 Stage Exit 保持 **OPEN**。
