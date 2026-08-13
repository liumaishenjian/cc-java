# ADR-070：S15 本地直连 Provider Definition 与 Credential Profile 契约

- Status: Implemented at L1（未 Accepted，未达到 L2）
- Date: 2026-08-14
- Stage: S15 Independent Innovation（OPEN）
- Feature ID: `MODEL-13`
- Current → Exit Target: `L1 → L2`（L2 仍需真实 BYOK 在线证据）
- Depends On: ADR-024、027、031、039、047、064、066、069
- Baseline: `R2026.03`；Authorized Snapshot: `AUTH-SRC-2026-07-29-A`

## 1. 决策与范围

实现用户级、仓库外、本地直连 BYOK Provider/Auth 控制面。非秘密 Provider Definition、Credential
Profile metadata、secret material、Session model selection 与 live Gateway 分离。TUI/CLI 共用
`ProviderAuthApplicationService`，真实请求仍只走既有 `ModelGateway`/`ProviderRouter`：

```text
TUI /connect,/auth,/models + CLI auth/providers/models
 → ProviderAuthApplicationService
 → SelectedProviderRouteFactory
 → Spring AI ProviderGatewayFactory
 → one ModelProviderRoute → existing ProviderRouter → Agent Runtime
```

每个 Run 只绑定一个 `(providerId, profileId, modelId, authGeneration)`。不得因 401、429、timeout、
missing secret 或 logout 自动换 profile/Provider。

L2 包含 OpenAI-compatible custom URL/model、Anthropic 官方 API、OpenRouter 官方 API；API key 的
`STORE|ENV` SecretRef；多 profile、local status、显式 probe、Session pin/default；权限受限文件 store、
原子/锁/恢复、legacy 非破坏迁移；logout 取消同进程 active runs、清应用引用/cache并删除本地 secret。

不包含官方中转 Gateway、团队 secret sync、任意 OAuth/refresh/remote revoke、OS keychain 实现或
“OS vault”声明、file/exec SecretRef、silent rotation/failover、自动 probe/discovery。Provider/Auth
不是 Tool Permission 或 OS Sandbox；profile/secret 不进入 Canonical/Session。

## 2. 模块、包与类型职责

| 模块/包 | Proposed 类型 | 职责；禁止职责 |
| --- | --- | --- |
| `domain/.../model` | `ProviderSelectionSnapshot`、`ProviderAuthStatusCode` | 非秘密 ID/状态；禁止 secret、路径、store handle |
| `core/.../model` | 既有 `ProviderRouter`、`ModelProviderRoute` | 唯一 route/capability/deadline/cancel；禁止文件/UI/store/fallback |
| `model-spring-ai/.../provider` | `ProviderGatewayFactory` 及三个 Provider factory | definition/model+secret lease→Gateway；禁止持久化/改 profile/raw SDK error |
| `cli/.../provider` | `ProviderDefinition`、`ProviderCatalog`、`ProviderDefinitionStore`、`SelectedProviderRouteFactory` | 非秘密定义/catalog/composition；禁止 Loop/Session/secret |
| `cli/.../auth` | `CredentialProfile`、`SecretRef`、`CredentialStore`、`RestrictedFileCredentialStore`、`CredentialResolver`、`CredentialLeaseRegistry` | metadata/store/precedence/generation/lease；禁止 Provider 请求/UI |
| `cli/.../runtime` | `ProviderAuthApplicationService`、`ProviderAuthRuntimeResources` | 全部用例与资源关闭；禁止旁路 Router |
| Picocli/TUI | `AuthCommand`、`ProvidersCommand`、`ModelsCommand`；`ConnectDialog`、`AuthPanel`、`ModelPicker` | 参数/展示/Intent；禁止 store直写或 TUI 直连网络 |

不新建 Maven module。Domain/Core 不依赖文件系统、Jackson、Spring、Picocli 或 Ink；公共契约、安全
边界和状态机必须有契约型中文 Javadoc。

## 3. 字段与不变量

- `providerId/profileId`：`[a-z0-9][a-z0-9-]{0,62}`；保留 `legacy-env/properties` 前缀。
- `modelId`：1～256 code point、1 KiB UTF-8；拒绝 control/NUL/CRLF/首尾空白，精确比较。
- display name≤80 code point/256 bytes；`secretId` 为 store 生成的 128-bit lowercase hex。
- `authGeneration` 每次 create/replace/logout 单调递增，只用于进程内 lease fencing，不进 Session。

`ProviderDefinition` 字段：providerId、kind=`OPENAI_COMPATIBLE|ANTHROPIC|OPENROUTER`、displayName、
baseUri、apiVariant、models[1..128]、defaultModelId、staticHeaders[0..16]、connect/request timeout。

1. custom compatible 必须 absolute HTTPS；仅测试 seam 允许 loopback HTTP；拒绝 user-info/query/fragment。
2. Anthropic/OpenRouter 固定官方 HTTPS origin和 API variant，不允许改为中转 URL。
3. model 唯一且 default 属于 catalog。built-in Provider 的代码基线分别为 Anthropic
   `claude-sonnet-4-6` 与 OpenRouter `anthropic/claude-sonnet-4.6`；用户级文件只保存严格解析的本地
   `modelOverrides`（model ID 集合的显式 add/remove），不得覆盖 Provider kind、官方 origin、API variant
   或认证 Header。add 已存在、remove 不存在、remove 当前 default、unknown/duplicate field、越过数量或
   字节 ceiling 均 fail closed；持久化后仍须保证 catalog 非空且 default 属于有效 catalog。
4. 普通 `models list/add/remove/use` 只读写本地 catalog，必须保持 0 DNS、0 HTTP、0 ModelGateway
   request；不调用 Provider models API。remote sync/discovery 尚未实现，只允许显式 `auth probe` 按第 7 节
   访问远端 models endpoint，且 probe 结果不得自动改写本地 catalog。
5. Anthropic 基线与模型 ID 以官方 Models 文档
   <https://platform.claude.com/docs/en/about-claude/models/overview> 为准，官方 Models List API 为
   <https://platform.claude.com/docs/en/api/models-list>；OpenRouter 基线接入参考官方 Quickstart
   <https://openrouter.ai/docs/quickstart>，模型列表 API 参考官方 Get models
   <https://openrouter.ai/docs/api-reference/list-available-models>。这些 URL 是设计溯源与人工更新入口，
   不构成运行时 remote sync。
6. connect 1～30 秒，request 1～300 秒，仍受 Runtime 更窄 deadline/cancel。
7. Header name/value≤64/1024 bytes，总计≤8 KiB；拒绝 control/重复及 authorization、proxy-
   authorization、x-api-key、api-key、cookie、set-cookie。认证 Header 只能由 Adapter 构造。
8. list 只显示 provider/kind/model count/default，不显示 base URI/header value。

`CredentialProfile` 仅含 profileId/providerId、authMethod=`API_KEY`、`STORE(secretId)|ENV(variableName)`、
created/updated、可选 lastProbe(code/time/definitionDigest/modelId)。同 Provider≤16、全局≤64；ENV 名
`[A-Z][A-Z0-9_]{0,127}`；lastProbe 禁止 body/header/request ID/endpoint/exception/secret fingerprint。
未来 OAuth 只能是编译注册的 `OFFICIAL_OAUTH` extension，固定 issuer/client/redirect，配置不可自定义。

`SecretMaterial` 仅在 CLI auth/model adapter edge：可清零 `char[]/byte[]`，1～16 KiB，拒绝 NUL/CRLF/
blank；`toString=<redacted>`，不序列化或 value equals/hash；close 清零项目数组。第三方 SDK String 无法
保证清零是明确 gap，因此 Gateway 与 lease 同生命周期，logout cancel+close；ENV value 不缓存。

## 4. 用户级存储、权限与事务

固定根只由 Composition Root 从一次解析的 `user.home` 派生，绝对路径不进 Domain/Session/event/error/
evidence：

```text
~/.cc-java/providers.v1.json
~/.cc-java/auth/profiles.v1.json
~/.cc-java/auth/secrets/<secretId>.json
~/.cc-java/auth/.lock
~/.cc-java/auth/.txn.v1.json
```

普通文件 backend 固定称 `RestrictedFileCredentialStore`/“权限受限的本机文件”，不得称 OS vault。

### 4.1 格式与 ceiling

`providers.v1.json`：`schemaVersion=1`、可选 defaultSelection(providerId/modelId)、providers 数组；单项
字段对应第 3 节。文件≤256 KiB、providers≤32、严格 UTF-8、duplicate key/unknown field 拒绝。built-in
Anthropic/OpenRouter 由代码注册，文件只能增加 custom compatible或 default，不能覆盖 built-in。

`profiles.v1.json`：`schemaVersion=1`、generation、`providerDefaults`、profiles 数组。示例：

```json
{
  "schemaVersion": 1,
  "generation": 7,
  "providerDefaults": {"anthropic": "personal"},
  "profiles": [{
    "id": "personal", "providerId": "anthropic", "authMethod": "API_KEY",
    "secretRef": {"kind": "STORE", "secretId": "0123456789abcdef0123456789abcdef"},
    "createdAt": "2026-08-13T00:00:00Z", "updatedAt": "2026-08-13T00:00:00Z"
  }]
}
```

单 secret 文件≤20 KiB，固定 `{schemaVersion,secretId,kind:"API_KEY",value}`。文档占位符不是真实
credential；tracked 仓库不创建真实文件或非空示例，测试/evidence 只用随机 sentinel且不得输出。

### 4.2 路径、权限、链接、锁

每次访问必须从可信 home 固定派生，逐段 `NOFOLLOW_LINKS`，拒绝 symlink、Windows Junction/reparse、
非普通文件、root escape及平台可查时 hard-link count>1；创建前验证最近存在父目录 realpath，打开后
复核 file key/identity，竞态不确定 fail closed。

Unix 目录 owner=current/mode `0700`、文件 owner=current/mode `0600`；Windows DACL 必须拒绝
Everyone/Users/Authenticated Users 读写，只保留当前用户和必要系统管理员主体。ACL view 不可用或
无法证明时 STORE login fail closed；ENV profile仍可用。错误只含固定 code/逻辑对象/修复动作，禁止
绝对路径、ACL dump、JSON片段和 cause message。

providers/auth各用固定 lock；auth writer 获取 `.lock` exclusive `FileChannel` lock，默认等待5秒并响应
cancel。进程内锁先于进程间锁且顺序固定。写入同目录 owner-only random temp，严格序列化、force、
重读验证，再 `ATOMIC_MOVE+REPLACE_EXISTING`；不支持 atomic move则 fail closed，不 truncate-in-place。

create/replace：新 secret→无 secret txn phase→发布 index/generation→删旧 secret→清 txn。logout 必须
先完成运行态 revoke/drain，再 txn 发布移除后的 index→删 secret→清 txn。txn 只含 operation/profileId/
old/new secretId/expectedGeneration/phase，不含 secret/env value/endpoint。恢复以 index/generation 为
唯一事实源：删 orphan，保留 index 引用的新 secret；被引用 secret缺失即 `MISSING_SECRET`，不回退旧
secret。corrupt/conflict/lock timeout/temp/txn identity不确定都禁止使用受影响 profile。

## 5. 解析优先级与选择

Credential profile 对已确定 provider 的唯一顺序：

1. 当前 Run/Session `--profile` 或显式 pin；
2. Provider default profile；
3. environment ephemeral profile；
4. legacy `config/provider.local.properties` ephemeral profile；
5. 否则 `AUTH_PROFILE_REQUIRED`。

显式/default profile存在但 missing/insecure/revoked 时直接失败，不向下回退；只有该层未配置才进入下一
层。每次只返回一个 profile，不排序、轮转或 fallback。

| kind | API key env | 配置来源 |
| --- | --- | --- |
| OpenAI-compatible | `CC_JAVA_OPENAI_API_KEY` | definition；旧 BASE_URL/MODEL 只走 legacy path |
| Anthropic | `CC_JAVA_ANTHROPIC_API_KEY` | built-in/catalog；旧变量只走 legacy path |
| OpenRouter | `CC_JAVA_OPENROUTER_API_KEY` | built-in/catalog |

普通 `OPENAI_API_KEY/ANTHROPIC_API_KEY` 不自动读；需要时显式创建 ENV ref，避免宿主环境意外改变目的地。

Provider/model 顺序：one-shot CLI或 `/models` 显式选择→user defaultSelection→仅一个候选时确定选择→
多候选则要求选择。model必须属于 definition。profile/model正交；选择只影响下一 Run，active Run持有
启动 snapshot，不在模型回合中途切换。

## 6. CLI 与 TUI 契约

```text
codej providers list [--json]
codej providers add --id <id> --kind openai-compatible --base-url <https-url> --model <id>...
codej providers remove --id <id> [--yes]
codej auth login --provider <id> --profile <id> [--api-key-stdin|--from-env <ENV>] [--set-default]
codej auth list [--provider <id>] [--json]
codej auth status|probe|logout --provider <id> --profile <id> [...]
codej auth migrate-legacy --provider <id> --profile <id> [--set-default]
codej models list [--provider <id>] [--json]
codej models use --provider <id> --model <id> [--profile <id>] [--set-default]
```

不提供 `--api-key <value>`、secret positional/system property。非 TTY只接受 stdin/env；TTY两者省略时
`Console.readPassword`，Console不可用则拒绝。stdin≤16 KiB，只 trim单个终止换行并清零。JSON只输出
version、validated IDs、authMethod/refKind/local status/default/lastProbe code/time；禁止 env name、
secretId/path/base URL/header/endpoint/raw failure。退出码：0成功、2输入、3缺失、4不安全/损坏/锁、5 probe
拒绝、6 timeout/cancel、7 logout drain失败。Provider仍被 profile/default/active selection引用时 remove
拒绝且不级联删 credential。

TUI 实际交互契约（Batch B 安全降级）：

```text
/connect
  → 同时请求 models.list 与 auth.list
  → 展示内置/custom Provider、已有 profile 与可操作格式
/connect <provider> <profile>
  → IDLE→PAUSE_INK→JAVA_INHERITED_TTY→MASKED_CONSOLE→SAVED|FAILED|CANCELLED|TIMED_OUT
  → 成功后 auth.list 刷新
/connect <provider> <profile> env <ENV_NAME>
  → 只把经白名单校验的变量名称交给一次性 Java；Node 不读取变量值
```

一次性 Java 进程只从已验证的启动 `ChildProcessSpec` 派生：固定 executable/cwd/env/JVM 前缀、唯一
`CcJavaCliMain`、唯一且位于末尾的 `--stdio`，派生时只保留主类及其 JVM 前缀并固定追加 `auth login`；`shell=false`、
`stdio=inherit`。STORE 模式要求真实 TTY，Java `Console.readPassword` 遮蔽输入；Console 或
`readPassword` 返回 null 时稳定返回 typed failure。TUI 暂停输入并关闭 raw mode，所有 spawn error/exit/
timeout/cancel 竞争只能结算一次，终端也只恢复一次；timeout、cancel、TUI terminate 都终止子进程。
Agent Run 中或已有登录时拒绝新登录。login 不自动 probe，也不得把 key 放入 JS、Agent stdio、argv 或输出。

`/auth list` 为零网络本地读取；logout成功必须提示“仅删除本机 credential，未在 Provider侧 revoke”。
active Run时 `/models` 返回 `RUN_ACTIVE`，不得中途切换。

原设计的 Java stdin `auth.secret.submit` raw frame 因 G3 尚无法证明 ingress 完全不记录而不启用；当前继承
终端的一次性 Java masked Console 是 ADR 要求的 fail-closed fallback。不得退回 `--api-key-stdin` 管道，
因为那会让 Node/JS 成为 secret 字节通道。

## 7. Application Service、probe 与 logout

唯一服务：

```text
connect(ConnectRequest, SecretInput, CancellationToken)
listProfiles(ProfileFilter); status(ProfileKey)
probe(ProbeRequest, CancellationToken)
logout(LogoutRequest, CancellationToken)
listModels(ModelFilter); selectModel(ModelSelectionRequest)
migrateLegacy(MigrationRequest, CancellationToken)
```

Request/Result只含 ID/枚举/计数/status；SecretInput是独立 edge SPI。mutation统一 validate→guard→lock→
transaction→publish；UI不得直写 store。model selection复用 Settings/RuntimeScope CAS，只影响下一 Run，
失败保留 last-known-good scope。

local list/status不读 secret value、不创建 Gateway、不 DNS/HTTP，返回 `AVAILABLE_LOCAL|MISSING_SECRET|
INSECURE_STORE|INVALID_DEFINITION|CORRUPT_STORE|REVOKED_IN_PROCESS|UNKNOWN_NETWORK`。

显式 probe 每次只测一个 profile/provider/model，exactly one attempt，无 rotation/retry/fallback；使用新增
`NetworkPurpose.PROVIDER_AUTH_PROBE`+既有 `NetworkAccessPort`，绑定 definition scheme/host/port，
redirect NEVER。单一 monotonic deadline默认5秒、最大30秒，覆盖 resolve/connect/headers/≤64 KiB body/
parse，cancel关闭 stream/future/resource。OpenAI-compatible/OpenRouter有界调用 models endpoint，Anthropic
调用官方 models endpoint；不支持返回 `UNSUPPORTED`，不回退可能计费的 completion。仅2xx严格 JSON；
3xx/401/403/429/timeout/cancel/unreachable/protocol typed。probe不进 Tool Pipeline/Agent Session，仅原子保存
privacy-safe lastProbe；该应用层网络控制不是 OS Sandbox。

`CredentialLeaseRegistry` 按 `(providerId,profileId,generation)` 管 active model route：

1. Run前原子获取 ACTIVE且generation匹配的 lease。
2. logout CAS→`REVOKING`，立即拒绝新 lease，只对外显示 active count。
3. 用既有 ApplicationService/CancellationToken取消同进程 Run并关闭 Gateway stream/client，lease terminal
   callback恰好一次。
4. 共享10秒 drain；清零后清 resolver/gateway cache和 SecretMaterial，再执行 store logout txn。
5. store成功 generation++、状态 REVOKED、发布唯一完成结果。
6. 不合作 Run/Gateway超时则 `AUTH_LOGOUT_DRAIN_FAILED`，状态 `REVOKING_BLOCKED`，拒绝新 Run且不谎报
   删除成功；retry或shutdown继续 cancel/close。不得删 store后保留可用 live credential。
7. runtime已清而 store删除失败则 `STORE_DELETE_FAILED`，新 lease仍拒绝；重试只做幂等删除。

active Run通过既有唯一 terminal结束并使用 privacy-safe `AUTH_REVOKED`，不能追加第二终态。logout不发远端
revoke，必须提示用户到 Provider控制台轮换/删除 key。

## 8. Legacy 非破坏迁移

旧 properties/env loader保留为最低优先级 ephemeral compatibility；启动不自动复制、创建或删除 secret。
`auth migrate-legacy` 仅显式 provider/profile：显示字段名/目标 ID不显示值；复用 fixed path、NOFOLLOW、
regular、16 KiB guard；env overlay不冒充文件迁移。base/model写新 definition，key经 SecretInput写 STORE；
目标存在、partial legacy、冲突/insecure target fail closed。发布并重读后返回
`MIGRATED_COPY_VERIFIED`；旧文件 bytes永不修改/truncate/rename。提示用户 probe后手工清旧 key。crash
recovery只处理用户 store txn，测试精确断言旧 bytes前后相等。tracked example继续只含空 secret。

## 9. 错误、事件与隐私

错误码：

```text
PROVIDER_DEFINITION_INVALID PROVIDER_UNKNOWN MODEL_UNKNOWN MODEL_SELECTION_AMBIGUOUS
AUTH_PROFILE_REQUIRED AUTH_PROFILE_UNKNOWN AUTH_PROFILE_CONFLICT
AUTH_SECRET_INPUT_REQUIRED AUTH_SECRET_UNAVAILABLE AUTH_STORE_INSECURE
AUTH_STORE_LOCKED AUTH_STORE_CORRUPT AUTH_TRANSACTION_CONFLICT
AUTH_PROBE_REJECTED AUTH_PROBE_RATE_LIMITED AUTH_PROBE_UNSUPPORTED
AUTH_PROBE_UNREACHABLE AUTH_PROBE_TIMED_OUT AUTH_CANCELLED AUTH_REVOKED
AUTH_LOGOUT_DRAIN_FAILED AUTH_STORE_DELETE_FAILED
LEGACY_CONFIGURATION_INCOMPLETE LEGACY_MIGRATION_CONFLICT
```

错误只含 code、已验证 provider/profile ID、retryable、用户动作枚举；禁止 cause message、URI/path/header、
env name/value、secretId、Provider body或 stack。

非 durable application results：`AUTH_PROFILE_SAVED/LISTED`、`AUTH_PROBE_COMPLETED`、
`AUTH_LOGOUT_STARTED/COMPLETED`、`MODEL_SELECTION_CHANGED`、`LEGACY_MIGRATION_COMPLETED`。字段仅
operationId、validated IDs、status、count、duration bucket、timestamp；不进 Session/Canonical/默认 OTel。
必须对 Domain/exception/Picocli/TUI/stdout/stderr/stdio v0/v1/daemon/SDK/Session/OTel/diagnostics/evidence
做 sentinel scan，任一命中为 blocker。

## 10. G0-G6 验收矩阵

| Gate | 当前证据与剩余项 | 当前 |
| --- | --- | --- |
| G0 | ADR-069 官方 URL/日期、AUTH-01 边界、Codex commit/LICENSE、Unknown/停止条件已固定 | PASSED |
| G1 | `MODEL-13` 三 Provider、ENV/STORE，以及无 Gateway rotation/OAuth 的范围已固定 | PASSED |
| G2 | 本 ADR package/type/format/state/race/privacy 契约已完成 review | PASSED |
| G3 | store/service/CLI/TUI/factories/Router composition 与中文 Javadoc 已在本地实现 | PASSED |
| G4 | clean verify、TUI/launcher、fault/security/loopback/logout race/secret scan 与普通测试零网络证据已完成 | PASSED |
| G5 | 离线 Demo 与负例已完成；尚缺至少两个 distinct Provider 的真实 BYOK text stream、Tool call、cancel、auth-negative E2E | PARTIAL |
| G6 | 提交前 implementation commit-scoped 证据及文档/Demo/Gap/Dashboard 最终对账 | OPEN |

## 11. 测试、E2E 与量化阈值

单元/属性：ID/model/URI/header/timeout/unknown/duplicate/size；definition/profile数量/default引用/built-in
覆盖拒绝；secret redaction/close/stdin/ENV不缓存；precedence全排列及显式失效不回退；status零网络；
probe单 attempt/deadline/cancel/redirect/body limit；strict JSON/orphan/missing/generation conflict；symlink/
Junction/reparse/hardlink/parent swap/ACL/mode/oversize/non-atomic move；lock/cancel与每个 txn crash point；
迁移 conflict/partial/crash且 legacy bytes相同；所有输出 sentinel零命中。

集成/并发：三个 Provider JDK loopback覆盖 text/stream/tool/usage/auth error/cancel；route factory只生成一个
route且既有 Router为唯一链；TUI/CLI同 service/store；login-login/login-logout/probe-logout/Run acquire-
logout barrier race；active Run 0/1/N的 fence/cancel/close/唯一 terminal/store delete及不合作 Gateway的
DRAIN_FAILED；Session resume不恢复 secret/profile；Windows ACL/Junction和Linux mode/symlink真实 selector；
测试后无 temp/txn/secret residue。

| 场景 | 阈值 |
| --- | --- |
| list/status/models | 0 DNS、0 HTTP、0 ModelGateway request |
| secret leakage corpus | 全 surface 0 sentinel |
| profile determinism | 100 seeds同输入同 profile；fallback=0 |
| logout race | 100次：fence后新 lease=0、重复终态=0 |
| fault injection | 每个 publish phase恢复到一个事实源；legacy bytes 100%不变 |
| probe | exactly one attempt；deadline有界；cancel后无 active resource |
| real BYOK | 用户显式 opt-in至少两个 Provider，各完成 text stream+Tool call+cancel/auth-negative |

真实 key只来自 env/user store，不进命令/evidence且不作为普通 CI。至少两个 Provider真实通过前不得升
`MODEL-13 L2`；单一 compatible endpoint不能冒充三 Provider完成。

## 12. 实现批次与 gap

- **Batch A contracts+store（本地实现已完成）**：Fake clock/filesystem/security inspector；strict
  definition/profile、guard、lock/txn/recovery、SecretMaterial、local status/precedence/legacy read-only。
- **Batch B application+CLI/TUI（本地实现已完成）**：application service、Picocli/JSON、四个 slash UX、
  secret channel、model LKG/scope swap、显式迁移、CLI/TUI parity及 secret scan。
- **Batch C Gateway+probe/logout（本地实现已完成）**：三个 factory、single-route Router、lease registry、
  active-run drain、NetworkAccess probe、loopback/race/fault/full regression、离线 Demo 与负例。
- **L2 在线证据 gap**：仍需用户显式 opt-in，在至少两个 distinct Provider 上分别完成真实 BYOK text
  stream、Tool call、cancel 与 auth-negative E2E；remote model sync/discovery 仍未实现。

达到 L2后仍不宣称 OS vault、OAuth、remote revoke、secret sync、自动 discovery、Provider SLA、跨进程
active-run revoke、NFS事务、fallback、第三方 SDK String清零、L3长期兼容或 S15 L4收益。`MODEL-13` 是
参考能力补齐，不是创新 L4；S15 Exit继续 OPEN，等待真实在线证据、G6 提交前收尾及相对 S14 的创新
A/B收益/成本/安全阈值。
