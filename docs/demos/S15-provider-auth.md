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

启动 TUI 后可使用：

```text
/connect
/connect anthropic s15-demo env CC_JAVA_S15_DEMO_API_KEY
/auth list
/auth probe anthropic s15-demo claude-sonnet-4-6
/models anthropic
/models add anthropic s15-demo-model
/models use anthropic claude-sonnet-4-6 s15-demo
/models remove anthropic s15-demo-model
/auth logout anthropic s15-demo confirm
```

`/auth probe` 与 CLI `auth probe` 一样是显式联网操作。STORE 登录只说明为继承终端的 masked Console：

```text
/connect anthropic s15-store-demo
```

## 预期安全结果

| 操作 | 预期结果 |
| --- | --- |
| `providers list` | 只展示非秘密 Provider catalog，不展示 credential |
| `auth login --from-env` | 只持久化示例环境变量名称引用，不把环境变量值写入 profile metadata |
| `auth list/status` | 只展示 provider、profile、引用类型和本地状态；环境变量未设置时可安全报告 `MISSING_SECRET` |
| `models list/add/remove/use` | 只维护本地模型目录与选择；不做 remote discovery，不联网 |
| 显式 `auth probe` / `/auth probe` | 最多按受控 probe 契约访问所选 Provider；不回显 key、认证 Header、响应正文或 endpoint 细节 |
| STORE `/connect` | 使用继承终端的 masked Console |
| `logout --yes` / TUI confirm | 删除本地 profile；不宣称已远端 revoke |

任何错误输出都应保持结构化且隐私安全，不包含 API key、环境变量值、认证 Header、Provider 响应正文、完整 URI、秘密文件路径或堆栈。

## 已有验证与事实边界

2026-08-14 已通过离线临时安装 E2E：在仓库 ignored 的临时 UserHome 中安装并运行 `codej`，只创建指向明显虚构环境变量名称的 ENV metadata，完成 `providers list`、`auth login --from-env`、`auth list/status`、`models list` 与 `logout --yes`；全部 exit 0，缺失环境变量值稳定报告 `MISSING_SECRET`，logout 后 profile 数为 0。该 E2E 没有执行 probe 或模型网络请求。

至少两个 distinct Provider 的真实在线 BYOK E2E 尚未执行，因此没有双 Provider 的 text stream、Tool call、cancel 与 auth-negative 在线证据。`MODEL-13` 保持 **L1**，不得提升到 L2；S15 Stage Exit 保持 **OPEN**。
