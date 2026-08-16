# S15 MODEL-13 implementation handoff

- Date: 2026-08-13
- Branch/root: `codex/s09-hooks`, root checkout `G:\AI Cloud\cc-java`
- Constraint: no subagent/worktree, no commit/push; TOOL-18 changes preserved
- Status: **IMPLEMENTED — verification recorded; do not raise MODEL-13 above L0 in this documentation-only update**

## Decisions retained

- ADR-070 is the implementation authority; ADR-069 conclusions were read only at sections 4–7.
- Provider definition, credential metadata, secret material, run selection, and live gateway remain separate lifetimes.
- Existing `ModelGateway` / one-route `ProviderRouter` remains the only intended model path.
- Resolver order implemented so far is explicit stored profile → provider default stored profile → cc-java environment ephemeral. An existing configured layer fails closed; no rotation/fallback.
- Secret value is accepted only as mutable `char[]`, represented by `SecretMaterial`, redacted by `toString`, and cleared on close.

## Files added in this execution session

Domain:

- `cc-java-domain/src/main/java/io/github/liumaishenjian/ccjava/domain/model/ProviderAuthStatusCode.java`
- `cc-java-domain/src/main/java/io/github/liumaishenjian/ccjava/domain/model/ProviderSelectionSnapshot.java`

CLI auth/provider edge:

- `cc-java-cli/src/main/java/io/github/liumaishenjian/ccjava/cli/auth/ProviderAuthException.java`
- `cc-java-cli/src/main/java/io/github/liumaishenjian/ccjava/cli/auth/SecretRef.java`
- `cc-java-cli/src/main/java/io/github/liumaishenjian/ccjava/cli/auth/SecretMaterial.java`
- `cc-java-cli/src/main/java/io/github/liumaishenjian/ccjava/cli/auth/CredentialProfile.java`
- `cc-java-cli/src/main/java/io/github/liumaishenjian/ccjava/cli/auth/CredentialStore.java`
- `cc-java-cli/src/main/java/io/github/liumaishenjian/ccjava/cli/auth/CredentialResolver.java`
- `cc-java-cli/src/main/java/io/github/liumaishenjian/ccjava/cli/auth/RestrictedFileCredentialStore.java`
- `cc-java-cli/src/main/java/io/github/liumaishenjian/ccjava/cli/provider/ProviderDefinition.java`
- `cc-java-cli/src/main/java/io/github/liumaishenjian/ccjava/cli/provider/ProviderCatalog.java`

All prior tracked/untracked TOOL-18 files remain present and were not reverted.

## Implemented behavior

- Domain non-secret selection/status contracts with Chinese Javadoc.
- Provider/profile/model identifier checks.
- Strict Provider definition checks for HTTPS, official Anthropic/OpenRouter origins, models/default, header denylist/limits, and timeouts.
- Built-in Anthropic/OpenRouter catalog plus non-overriding OpenAI-compatible custom definitions.
- STORE/ENV secret references, clearable secret material, non-secret credential profiles, structured privacy-safe errors.
- Credential store interface and deterministic profile resolver without fallback from an invalid explicit/default layer.
- Restricted file store baseline: fixed user root, NOFOLLOW checks, Unix permissions, bounded strict JSON with duplicate/unknown rejection, process/file lock, random same-directory temp, force/reread/atomic move, create/replace and logout index generation.

## Verification run

Passed:

```text
./mvnw -q -pl cc-java-domain,cc-java-cli -am -DskipTests package
./mvnw -q -pl cc-java-cli -am -Dtest='RestrictedFileCredentialStoreTest,RestrictedFileSecurityTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

2026-08-14 续作会话先用 `git diff --stat/status` 复核工作树，确认 TOOL-18 修改仍保留且未
commit/push；随后复跑上述 credential store/security 定向测试通过。另曾运行 `git diff --check`；
只有既有换行符警告。

Failed during development, then fixed:

1. Jackson 3 uses `properties()` rather than Jackson 2 `fields()/fieldNames()`.
2. Jackson 3 mapper calls used here throw unchecked mapping exceptions; catches were aligned.

## 2026-08-14 continuation facts

The continuation first reviewed the diff and reran credential store/security tests instead of replacing or shrinking
prior work. The following previously-open Batch A findings are now implemented and covered by focused tests:

- `RestrictedFileSecurity` separates restricted creation from fail-closed validation of existing objects. It verifies
  Unix owner/mode, Windows owner-only DACL, symlink/reparse/Junction, platform-visible hard-link count, parent
  realpath/identity, opened file identity/size/mtime, and creation-time fallback because JDK Windows `fileKey` is null.
- `RestrictedFileCredentialStore` has `.txn.v1.json` phases and recovery for create/replace/logout's five crash seams;
  orphan cleanup, missing referenced secret truth, lock cancellation, non-atomic move failure and caller/parsed secret
  clearing tests pass.
- `ProviderDefinitionStore` provides strict bounded UTF-8/JSON `providers.v1.json`, generation CAS, custom count,
  exact model/default references, stable serialization, atomic publication, and built-in override rejection.
- `LegacyProviderConfigurationReader` only reads the fixed 16 KiB guarded repository path. Explicit
  `LegacyCredentialMigrationService` copies a complete tuple and tests exact legacy byte equality before/after success,
  partial input and conflict.
- Shared `ProviderAuthApplicationService`/`ProviderAuthRuntimeResources`, local list/status/models, login/logout,
  migration and model LKG/active-run refusal contracts are present. Picocli `providers`, `auth`, `models` implement
  stable exit classes, JSON projections and bounded UTF-8 stdin secret ingestion.
- React/Ink parses `/connect`, `/auth list`, confirmed `/auth logout`, and `/models` as bounded provider-control intents.
  Raw stdio ingress suppression is not proven, so `/connect` deliberately instructs the ADR-070 fallback
  `codej auth login --api-key-stdin`; it does not put secret payloads on stdio v0.

Remaining caveats: Jackson serialization still necessarily creates short-lived Java `String` copies; Batch C probe,
gateway factories, lease/drain logout and real BYOK evidence remain absent. The new next-run selection is application
service LKG state; production runtime composition still uses the existing one-route `ProviderRouter`, but the selected
MODEL-13 definition/profile has not yet been composed into a new gateway factory. Therefore MODEL-13 remains L0.

## Exact remaining work

### Batch A

Completed in the working tree: strict provider store/CAS/ceilings and built-in rejection; security inspector; auth txn
and recovery seams; fixed-path non-destructive migration; focused contract/security/fault/secret tests. Full cross-platform
selector execution and an exhaustive seeded property corpus should still be repeated in Batch C's clean G4 run.

### Batch B

Completed locally: shared service/resources; Picocli provider/auth/model commands with bounded stdin, stable JSON and
exit classes; local selection LKG/default/active-run refusal; TUI bounded parsing and the required stdin-subprocess
secret fallback. Still required before Batch C acceptance: compose MODEL-13 selected route into
`DefaultCliModeRunner`/`HeadlessRuntimeSession` via provider gateway factories while retaining `ProviderRouter` as the
only model path; add a structured stdio result schema (current TUI intentionally shows a fixed safe bridge notice),
full surface parity/integration scans, and active lease-aware logout.
### Batch C

1. Add Spring AI provider gateway factory interface and OpenAI-compatible, Anthropic, OpenRouter composition using one `ModelProviderRoute`.
2. Add `NetworkPurpose.PROVIDER_AUTH_PROBE`; implement exactly-one-attempt bounded probe with redirect NEVER, strict JSON/body/deadline/cancel and privacy-safe status.
3. Implement generation lease registry and integrate run acquisition/terminal callback.
4. Implement logout fence → cancel active runs → close gateway/resources → 10s drain → cache/secret clear → store delete, with blocked/delete-failed retry states.
5. Add loopback protocol tests for all providers, race/fault tests, and connect → process restart list → select → real gateway `--print` → logout fail-closed E2E.
6. Run full Maven/TUI/launcher tests and sentinel scans. Real BYOK evidence must be explicit opt-in for at least two distinct providers before L2.
7. Only after evidence: update ADR-070 implementation status, README, MODEL-13 demo/gap/evidence, feature matrix, product/technical docs, progress properties/HTML, and run `java scripts/ProgressDashboard.java --check`. Keep S15 OPEN.

## Continuation verification

Passed:

```text
./mvnw -q -pl cc-java-cli -am -Dtest=ProviderDefinitionStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -q -pl cc-java-cli -am -Dtest='LegacyCredentialMigrationServiceTest,ProviderDefinitionStoreTest' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -q -pl cc-java-domain,cc-java-cli -am -Dtest='RestrictedFileCredentialStoreTest,SecretMaterialTest,ProviderDefinitionStoreTest,LegacyCredentialMigrationServiceTest,ProviderAuthApplicationServiceTest,ProviderControlCommandsTest,CcJavaCommandTest' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -q -pl cc-java-cli -am test
npm --prefix cc-java-tui test -- --run test/slash-command.test.ts
```

`npm --prefix cc-java-tui run check` compiled successfully and ran 133 tests; 132 passed. One unrelated existing
`assistant-markdown.test.tsx` ANSI assertion failed because Ink rendered color escape bytes between the bullet and text.
The focused MODEL-13 slash test passed. `git diff --check` reported only line-ending conversion warnings.
## 2026-08-14 implementation closeout

本轮严格从 Git diff、现有 Surefire reports 与本文件恢复状态，没有读取旧会话日志、真实 Provider
配置、环境变量值或 credential。未使用 subagent/worktree，未 commit/push，也未启动 Docker。

### 完成的实现与测试

- `HeadlessRuntimeSession` 在真正的 Agent Run 边界打开一次 `RunScopedModelGateway.RunScope`，先建立
  `ActiveRun` 再绑定取消，所有 completed/failed/cancelled/exception 终态都在 `finally` 关闭 scope；
  `openRun` 失败和 route close 抛错也会清理 `activeRun` 与应用服务 fence。
- `ProviderAuthRuntimeResources.FencedRunGateway` 把 `ProviderAuthApplicationService.beginRun()` 与真实 route
  scope 组合；active Run 中 `models.use` 稳定返回 `AUTH_TRANSACTION_CONFLICT`，底层 route 打开失败或关闭
  失败不会泄漏 fence。
- `CredentialLeaseRegistryTest` 覆盖 generation 不可跨 active lease、最后一个 lease 关闭后连续 100 代可
  重新获取、fence cancel/resource close/terminal 恰好一次；新增应用服务 logout 测试证明最后一个 lease
  被取消并发布 terminal 后才删除 profile，fence 保留且后续 acquire 被拒。
- probe 生产边界测试覆盖专用 `NetworkPurpose.PROVIDER_AUTH_PROBE`、生产构造只能从 definition 派生官方
  HTTPS origin、redirect=false、预取消零授权/零发送、loopback 单 attempt 的 2xx/401/429/redirect/
  media type/strict JSON/64 KiB ceiling/timeout，以及所有 outcome 的安全 metadata 持久化。
- stdio 跨真实 Java 子进程验证 `provider.control` 的完整 `auth.list`、`models.list` 成功 payload 与
  `MODEL_UNKNOWN` 结构化错误；TUI 完整渲染 list/probe/use/logout/error，并通过真实 client intent 通道，
  不把命令作为模型 prompt。
- 安装形态验证暴露并修复两个 Windows 边界：launcher 现在把 `CODEJ_INSTALLATION_HOME` 固定传入 JVM
  `-Duser.home`；安装器新建 `.cc-java` 时先移除 ACL 继承并只授予 owner。`RestrictedFileSecurity` 使用
  create-time POSIX/ACL 属性，避免在“创建后再收紧”窗口中暴露文件。2026-08-14 返修进一步移除用户名
  后缀/大小写猜测：Windows ACL principal 只接受 `UserPrincipal.equals`（JDK Provider 以 SID 证明身份）或
  fail closed；`DOMAIN_A\\user` 与 `DOMAIN_B\\user` 同叶伪造测试及 Windows 真实 ACL round-trip 均通过。

### 精确验证命令与结果

```text
./mvnw -q -pl cc-java-cli -am -DskipTests package
PASS

./mvnw -q -pl cc-java-cli -am -Dtest='HeadlessRuntimeSessionTest,ProviderAuthApplicationServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test
PASS

./mvnw -q -pl cc-java-cli -am -Dtest='CredentialLeaseRegistryTest,JdkProviderProbeTransportTest,JdkProviderProbeLoopbackTest,ProviderAuthProbeApplicationServiceTest,ProviderAuthApplicationServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test
PASS

./mvnw -q -pl cc-java-cli -am -Dtest='StdioProtocolCodecTest,RuntimeStdioCommandHandlerTest,StdioProtocolProcessTest' -Dsurefire.failIfNoSpecifiedTests=false test
PASS

npm --prefix cc-java-tui test -- --run test/app.test.tsx test/slash-command.test.ts
PASS: 2 files, 35 tests

./mvnw -q -pl cc-java-cli -am test
PASS (final rerun): cc-java-cli Surefire reports = 67 suites/files, 407 tests, 0 failures, 0 errors, 12 skips

npm --prefix cc-java-tui run check
PASS (final rerun): TypeScript build + 10 files, 135 tests, 0 failures

pwsh ./scripts/TestCodejDevLauncher.ps1
PASS (final rerun): 60 assertions
```

安装形态 E2E 使用仓库 `target/s15-install-e2e-<random>/home` 作为临时 `UserHome`，安装 owned shim，设置
`CODEJ_INSTALLATION_HOME` 指向该临时目录，仅创建名为 `CC_JAVA_S15_OBVIOUSLY_FAKE` 的 ENV metadata
引用（没有读取或设置其值），依次执行：

```text
codej providers list --json
codej auth login --provider anthropic --profile s15-fake --from-env CC_JAVA_S15_OBVIOUSLY_FAKE --set-default
codej auth list --provider anthropic --json
codej auth status --provider anthropic --profile s15-fake --json
codej models list --provider anthropic --json
codej auth logout --provider anthropic --profile s15-fake --yes
```

结果：全部 exit 0；list/status 稳定报告 `MISSING_SECRET`；logout 后临时
`profiles.v1.json` 中 profile 数为 0。没有发起 probe 或模型网络请求。

`git diff --check -- . ':(exclude).claude/ignored/**'` 通过，仅有既有 LF→CRLF 提示；对 Git diff 中变更文件
进行有界敏感模式扫描，没有命中疑似已赋值 key/token/secret。扫描未读取
`config/provider.local.properties`，也未遍历 `.claude/ignored`。

### 剩余项与等级纪律

- 本轮按明确约束不提升矩阵、不生成最终 dashboard、不写 G6 evidence；现有工作树中较早会话留下的矩阵/
  progress HTML 修改未在本轮重新生成或宣称通过。
- 未执行真实 Anthropic/OpenRouter BYOK；因此没有两个 distinct Provider 的在线 L2 证据。
- 未运行全仓库 clean verify、跨平台 selector、WSL/macOS 或 seeded property corpus；本轮只完成要求的 CLI
  reactor、TUI、launcher 与 Windows 安装形态离线 E2E。
- Jackson/Spring AI 构造认证 header 时仍会产生短生命周期不可清零 `String` 副本，这是 ADR-070 已记录 gap。
- 安装 E2E 生成的随机目录位于 ignored `target/` 下，未加入 Git；为保留精确复验证据，本轮没有做删除型清理。

## 2026-08-14 `/connect` repair continuation

本次只从 Git diff 与本 handoff 恢复，没有读取旧会话日志、真实配置、环境值或 credential；未使用
subagent/worktree，未 commit/push、未运行 Docker。

完成项：

- 将 `cc-java-tui/src/stdio-client.ts` offset 28247 的真实 NUL 改为源码正则转义 `\x00`；严格 UTF-8
  解码通过且 NUL byte count=0，`git diff --numstat` 保持文本 diff。
- `ProviderLoginBridge` 新增可注入 spawn/terminal seam。STORE 非 TTY 明确 fail closed；固定唯一 Java
  main class、唯一且位于末尾的 `--stdio` 启动形态，派生时只保留 main 及 JVM 前缀，丢弃 workspace/model
  参数，`shell=false`、`stdio=inherit`。同步 spawn failure、
  spawn error/exit、timeout/exit、cancel/exit 与并发 claim 均有独立测试；timeout/cancel 会 kill，raw mode/
  pause 只恢复一次。Java `Console.readPassword` 返回 null 也通过可注入 reader 证明稳定抛出
  `AUTH_SECRET_INPUT_REQUIRED`。`StdioClient` 延迟创建桥，避免普通 fake stdio 测试被 Java-spec 校验破坏。
- `/connect` 无参同时请求 `models.list` 与 `auth.list`，但当前消费级向导只展示简短连接状态与逐步选择；
  STORE/ENV/legacy 等高级格式只在 `/help`、带参数命令或用户主动进入高级项时展示。`/connect provider profile`
  直接进入 Java masked Console；ENV 模式只传合法 ENV name；成功后刷新 `auth.list`；Agent Run 中或并发
  登录明确拒绝。Key 不进入 JS/Agent stdio/argv/output。
- `RestrictedFileSecurity.samePrincipal` 移除 username suffix/case-insensitive 匹配，只接受
  `UserPrincipal.equals` 可证明身份，否则 fail closed。补同叶不同域、短叶、大小写伪造拒绝测试，以及
  Windows 真实 owner/ACL round-trip E2E。
- ADR-070 已改成实际继承终端的一次性 Java 交互契约；未提升矩阵、未生成 dashboard/G6。

本次已通过的定向验证：

```text
npm --prefix cc-java-tui test -- --run test/provider-login-bridge.test.ts
PASS: 8 tests
npm --prefix cc-java-tui test -- --run test/slash-command.test.ts test/app.test.tsx
PASS: 38 tests
npm --prefix cc-java-tui run build
PASS
./mvnw -q -pl cc-java-cli -am -Dtest='RestrictedFileSecurityPrincipalTest,RestrictedFileCredentialStoreTest' -Dsurefire.failIfNoSpecifiedTests=false test
PASS
```

集中验证最终结果：

```text
npm --prefix cc-java-tui run check
PASS: TypeScript build；11 files / 146 tests / 0 failures

./mvnw -q -pl cc-java-cli -am test
PASS: cc-java-cli Surefire 68 suites / 412 tests / 0 failures / 0 errors / 12 skips

pwsh ./scripts/TestCodejDevLauncher.ps1
PASS: 60 assertions
```

变更文件严格 UTF-8 扫描为 0 invalid、0 NUL；`git diff --check -- . ':(exclude).claude/ignored/**'`
无 whitespace error（仅既有 LF→CRLF 提示）。本轮未运行 Docker、最终 dashboard 或 G6。

## 2026-08-14 model overlay Batch 2

本批严格只接 Java application service、Picocli 与 Java stdio codec/handler/process；没有修改 TUI、ADR、
功能矩阵或 dashboard。未读取 secret/环境值/credential，未使用 subagent/worktree，未 commit/push，未运行
Docker。

完成项：

- `ProviderAuthApplicationService` 暴露 built-in-only `addModel`/`removeModel`，每次从当前 snapshot generation
  发起 CAS；`addModel(..., setDefault=true)` 使用 overlay 发布后的 generation 持久化默认选择。
- application service 的 provider lookup 统一经 typed 边界映射；`ProviderCatalog.require` 的
  `IllegalArgumentException` 转为 `ProviderAuthException(PROVIDER_UNKNOWN)`，覆盖 login/list/status/probe/
  logout/models/select 与 overlay surface。
- Picocli 新增 `codej models add --provider --model [--set-default]` 和
  `codej models remove --provider --model`。一次性 `models use` 现在默认持久化 provider/model；显式
  `--session-only` 才保留当前进程下一 Run 语义。重开 service/store 测试证明默认选择仍生效。
- stdio `provider.control` 新增严格 schema 的 `models.add`/`models.remove`，只接受 provider/model 与可选
  boolean `setDefault`，未知字段（包括 secret-shaped 字段）在 codec 层拒绝。`models.use` 默认仍只改变当前
  TUI 下一 Run；仅 arguments 明确且类型校验通过的 `setDefault:true` 才持久化。
- 测试覆盖 application service overlay/typed unknown/reopen、Picocli add/remove/use persistence、stdio codec
  strict schema、真实 handler add/remove/session-only/explicit-default 和真实 Java process add/error 安全投影。

验证结果：

```text
./mvnw -q -pl cc-java-cli -am -Dtest='ProviderAuthApplicationServiceTest,ProviderControlCommandsTest,StdioProtocolCodecTest,RuntimeStdioCommandHandlerTest,StdioProtocolProcessTest' -Dsurefire.failIfNoSpecifiedTests=false test
PASS

./mvnw -q -pl cc-java-cli -am test
PASS

./mvnw -q -pl cc-java-cli -am -DskipTests package
PASS

git diff --check -- <本批 9 个 Java 源/测试文件>
PASS；仅 RuntimeStdioCommandHandler.java 既有 LF→CRLF 提示，无 whitespace error
```

一次 PowerShell 形式的首个定向 Maven 命令因 `-Dsurefire.failIfNoSpecifiedTests=false` 被 PowerShell 参数解析为
错误 lifecycle phase 而未运行测试；随后用 Bash 原样执行并通过，且最终串行重跑定向、CLI full test、package
全部通过。

## Working tree policy

Do not commit/push. Do not remove or overwrite TOOL-18 modifications. MODEL-13 remains L0 because final G3–G6,
clean cross-platform evidence and real two-provider BYOK evidence are absent.
