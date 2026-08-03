# ADR-036：`codej` 源码开发启动器

- Status: Accepted
- Date: 2026-08-01
- Stage: S04 Accepted 后维护切片
- Capability IDs: `BOOT-01` L2 → L2、`BOOT-06` L0 → L0、`DIST-01` L0 → L0、`DIST-02` L0 → L0
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制为 `Observed / Inferred`；本项目契约为 `Documented`

## 背景

S04 已形成可运行 Coding Loop，但交互入口仍是阶段验证脚本。维护者每天需要记住仓库路径、
显式 Workspace、构建复用开关以及 TUI 启动方式，不能像成熟 CLI 一样从目标项目目录直接
输入一个命令。

本切片只改善源码开发体验。它不产生 Runnable Jar、原生二进制、版本下载、自动更新或
跨平台安装承诺，因此不提升 Distribution 能力等级，也不改变 S04 Accepted 和下一步
S05 Permission Gate。

## 受控研究结论

对授权快照的 CLI Bootstrap、初始 cwd 状态和原生安装机制进行只读研究后，只采用以下
可独立表达的机制：

1. 稳定命令入口位于用户级 bin 目录，安装准备与日常运行分离；
2. 启动时解析真实当前目录，并把它作为原始项目根；
3. 安装诊断分别检查命令入口、目标文件和 PATH，避免“文件已写入”被误报成“命令可用”；
4. 正式发行可以把稳定入口与版本文件分离，并用锁、原子切换和回退处理更新竞态。

第四项只记录为 S14 研究输入。本项目不复制参考函数体、类型名、文件布局、内部格式、
错误文案或实现常量。

## 决策

### 1. 三种路径不能混用

```text
installationRoot = %USERPROFILE%\.local\bin
repositoryRoot   = cc-java 源码仓库
workspaceRoot    = 用户执行 codej 时的真实 cwd
```

Provider 本地文件继续只从 `repositoryRoot/config/provider.local.properties` 加载；文件、
Git 和命令 Tool 只作用于 `workspaceRoot`。

### 2. Windows 开发入口

安装器把带固定所有权标记、schema 和规范化仓库路径的 `codej.cmd` 写入用户级 bin。
该 shim 先检查 `pwsh` 与目标脚本；仓库移动后仍能自己报告失效引用。所有权标记只用于防
误覆盖和误删，不是签名或信任证明。

`StartCodejDev.ps1` 接收原始参数数组并独立解析 GNU 风格参数，支持
`--workspace`、`--model`、`--timeout`、`--print`、`--rebuild`、`--doctor` 和
`--help`。`--print` 明确是一次性非交互 Run；本切片不实现 TUI 预填首条消息。

### 3. 开发构建缓存

启动器根据 POM、Wrapper、各模块生产源码/资源、JDK 版本和 runtime classpath 构建输入
计算内容摘要，并同时检查每个模块 `target/classes`、CLI 主类和 classpath 文件。缓存不
依赖 mtime。仓库级排他锁防止两个开发终端并发写相同 Maven `target`；它不是发行更新锁。

### 4. Doctor 边界

Doctor 不构建、不联网、不调用模型，只报告路径、运行时、产物、TUI 依赖、ripgrep 和
Provider 来源的存在性。它不解析 properties，不显示 Base URL、API Key 或模型值，也不
把“文件/环境变量存在”描述为“配置有效”；最终校验仍由 Java `ProviderSettingsLoader`
负责。

### 5. 安装和卸载

安装器支持 `ShouldProcess/-WhatIf`，用 `npm ci --ignore-scripts` 准备 TUI 依赖，检查
PATH 中全部同名命令并拒绝覆盖非本项目入口。只有显式参数才修改用户 PATH，并在用户级
元数据记录该条目是否由安装器加入。卸载只删除能够确认归属的 shim 和安装器自己加入的
PATH 条目，不删除源码、配置或缓存。

## 被否决方案

- **把源码仓库的 bin 直接加入 PATH**：稳定入口与可移动源码目录耦合，冲突和卸载边界差；
- **PowerShell 普通具名参数直接接 GNU 参数**：`--workspace` 会被当作值，不能可靠解析；
- **每次启动都 Maven package**：日常成本过高；
- **只用 mtime 判断缓存**：Git 切换、解压和时钟变化会误判；
- **PowerShell 复制 Provider properties 解析**：会与 Java 权威规则漂移；
- **把开发 shim 声明为正式安装器**：错误提升 `DIST-01/DIST-02`。

## 可证伪验证

1. 从含空格和中文的非仓库目录运行时，Workspace 等于调用目录而配置根仍是源码仓库；
2. 两种 Workspace 参数、重复/未知/缺值参数和 `--` 均有确定性自测；
3. 内容不变复用构建，任一输入或模块产物变化使缓存失效，并发启动只构建一次；
4. Doctor 快速路径不构建、不联网且输出不含 Provider 值；
5. 仓库路径失效时 shim 自身产生明确非零诊断；
6. 临时用户目录验证冲突、WhatIf、PATH 去重、所有权和安全卸载；
7. 原有 Spike 脚本未传 Workspace 时仍以 cc-java 仓库为目标。

## 延后内容

S08 再形成完整配置诊断；S14 再决定 Runnable Jar、jlink/jpackage/native binary、正式
跨平台安装、版本更新、回退和稳定外部命令兼容策略。
