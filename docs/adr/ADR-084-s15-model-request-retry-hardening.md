# ADR-084：S15 模型请求重试生产链路加固

- Status: Accepted
- Date: 2026-08-22
- Stage: S15 Independent Innovation（生产正确性修复）
- Feature IDs: `LOOP-08/09`、`MODEL-10`、`OBS-04`、`PLAN-01`（等级不变）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Capability Change: 无；S15 Exit 保持 OPEN

## 1. 问题

生产 Provider factory 已关闭第三方 SDK 内建 retry，并将一次 Run 冻结为确定 profile、model、credential
lease 的单 route `ProviderRouter(maxAttempts=1)`。但该 route 之前没有装配 Core 的同 Provider retry
装饰器，导致 Adapter 即使把连接失败、429 或 5xx 分类为 `RETRYABLE`，真实 production composition
仍只发起一次请求。旧 Core 策略同时只有固定 delay 列表，不能消费 typed `Retry-After`、生产 jitter
或独立 retry lifecycle；accepted Plan 的模型失败还会被错误投影为 `plan.verification.required`。

本 ADR 修复同一确定 Provider route 内的请求恢复，不引入 silent profile/provider rotation，不把 Permission、
Checkpoint、Plan recovery 或 Context Overflow 混入模型 attempt 预算，也不自动重放已产生可见输出、Provider
frame 或 Tool intent 的请求。

## 2. 受控参考研究

按 ADR-022 对授权快照的模型调用重试、错误分类、流式提交边界和进度事件做窄读，只提炼职责、状态迁移、
边界和验证方法；未复制或翻译函数体、Prompt、注释、错误文案、私有名称、布局、常量、Fixture 或字节。

| 分类 | 抽象结论 |
| --- | --- |
| Observed | 参考机制的配置语义是最多 10 次 retry；首次请求不属于 retry，因此最坏总调用数是 11 attempts。 |
| Observed | 网络/连接类失败、408、409、429、5xx 与 529 属于可恢复候选；普通 401/403、404 和 validation/其他 4xx 不应盲目重试。 |
| Observed | `Retry-After` 可影响下一次等待；等待、attempt 与终止受取消和总运行预算约束。 |
| Observed | 流式响应一旦形成外部可见进展，就不能把同一请求当成尚未提交而静默重放。 |
| Inferred | retry 应绑定同一已选 Provider/profile/client，而跨 Provider fallback、认证刷新、Context Overflow recovery 和 Plan recovery 是不同状态机。 |
| Inferred | attempt/wait 进度可作为观察旁路公开，但只能携带枚举、计数和时长，不能携带 URI、Header、body、Prompt、Request ID 或异常正文。 |
| Unknown | 授权快照准确发行版本、所有 Provider SDK 的内部 retry 默认值、401/403 refresh 的原子性契约、HTTP-date `Retry-After` 覆盖率及全部平台网络错误映射。 |

“10 retries”在本项目中固定解释为：attempt 1 是首次请求，失败后最多执行 attempt 2～11；因此
`maxAttempts=11`，不是总共十次请求。测试和用户 Surface 一律报告 attempt，总数包含首次请求。

## 3. 独立设计决策

1. `RetryingModelGateway` 是同 Provider retry 的唯一 Core owner。生产 `SelectedProviderRouteFactory`
   先装饰 raw Provider Gateway，再将装饰后的 Gateway 放入单 route Router；Router 继续保持
   `maxAttempts=1`，只承担 capability/fallback 边界，不用同一 route 重试伪装 fallback。
2. SDK retry 保持关闭，避免 SDK、Core 和 Router 三层叠加。一个 Run 只解析一次 selection、profile、
   credential generation 和 lease；全部 attempts 复用同一 client，Run close 只取消/关闭一次。
3. 生产策略为最多 11 attempts，基准退避从 500 ms 指数增长并封顶 32 s，加入 `0..25%` 正 jitter；
   Provider typed `Retry-After` 与 policy delay 取较大值，单次等待再封顶五分钟。测试通过
   `ModelRetryRuntime` 注入 deterministic random/sleeper，不真实等待。
4. 每次 attempt 前检查取消和 `CancellationToken.remainingTime()`。若等待大于或等于剩余 Run deadline，
   立即以 typed deadline/cancel 终止，不启动下一次请求；等待本身也必须可取消。
5. 只有 `FailureKind.RETRYABLE` 且尚未越过提交 fence 才能重试。可见 Delta 是 Core 第二道 fence；
   Spring AI Adapter 收到任意 Provider `ChatResponse` frame 后的 timeout、IO 或状态失败统一映射为
   `INCOMPLETE_STREAM`。成功 Tool intent 返回后自然由 Runtime 形成 commit boundary，不能自动重放。
6. Provider 边缘将 socket/HTTP timeout 明确映射为 `REQUEST_TIMEOUT + TIMEOUT`，将连接、DNS、TLS、
   connection reset 和其他 SDK IO family 映射为 `NETWORK_ERROR + NETWORK_IO`；两类都只在首个 Provider
   frame 前可重试。408、409、429、5xx/529 可重试；401/403 为永久认证失败，404、validation 和其他
   4xx 为永久请求失败。只有未来存在可证明安全、原子且不轮换用户选择的 credential refresh 契约时，
   401/403 才能进入独立恢复路径。
7. OpenAI 与 Anthropic typed Header 只接受唯一、非负十进制 delta-seconds `Retry-After`，并封顶五分钟；
   重复、非法、负值或溢出值忽略。当前不解析 HTTP-date，作为明确 gap 保留，不能从自由文本或 body 猜测。
8. Context Overflow 继续由 S07 的一次性 typed recovery coordinator 管理；cancel、incomplete stream 和
   permanent failure 不进入一般 retry。Plan execution recovery 继续依赖 durable 状态和显式恢复，不计入
   model attempts，也不自动重放副作用。
9. Core 发布 `ModelAttemptStarted` 与 `ModelRetryScheduled` lifecycle；stdio/TUI 只投影 turn、attempt、
   maxAttempts、waitMillis 和固定 category。事件不进入 Canonical Session/Prompt，观察出口失败不改变请求。
10. accepted Plan 只有正常 Run 完成后才投影 `plan.verification.completed/required`。模型失败、耗尽、取消、
    deadline、limit 或 incomplete stream 投影 session-level `plan.execution.failed`，只含 planId、durable
    status、typed stopReason 和可选脱敏 `ModelFailureSummary`；不得伪装成待验证或自动重启执行。

## 4. 可证伪验证

- Core deterministic tests 证明 transient→success、11 total attempts exhaustion、指数退避、jitter 边界、
  typed `Retry-After` 优先、等待前 deadline、等待期间 cancel、permanent only once、可见 Delta 后不重试、
  attempt correlation 和 privacy-safe lifecycle 顺序。
- Spring AI 参数化测试直接证明 `UnknownHostException`、`ConnectException`、socket reset 与 TLS handshake
  为 `NETWORK_ERROR + NETWORK_IO`，socket/HTTP timeout 为 `REQUEST_TIMEOUT + TIMEOUT`；上述异常在任意
  Provider frame 后一律变为 `INCOMPLETE_STREAM`。HTTP 408/409/429/500/503/529 与 400/401/403/404
  矩阵、唯一 delta-seconds `Retry-After`、非法/重复忽略和五分钟封顶均有确定性覆盖。
- CLI composition test 证明一个 selection/lease/client 上 transient 两次后第三次成功；另以 fake runtime
  证明生产默认 11 attempts 耗尽时 selection/profile/Provider/lease 不重建、不切换，Run close 只关闭一次；
  stdio Plan E2E 证明 `MODEL_ERROR` 只发 `plan.execution.failed`，不发
  `plan.verification.required`，且错误正文 sentinel 不进入 payload。
- TUI exact-schema、reducer 和 Ink tests 证明 attempt/wait 可见、额外 endpoint/body 字段 fail closed、Plan
  execution failure 显示“不自动重放”的可行动提示。
- 聚焦 Maven、完整 TUI check、Dashboard generate/check/self-test 和 diff check 必须通过；更广 verify 结果按
  实际执行报告，不因本修复自动提升 Capability Level。

## 5. 剩余差距

- 未提供真实 Provider 在线 retry 证据、跨平台网络故障注入、HTTP-date `Retry-After` 或安全 credential
  refresh；这些不能由 loopback/Fake 测试替代。
- `OBS-04` 新增了 retry lifecycle Surface，但尚无稳定外部活动协议、完整 retry/recovery/cost-known Metrics
  backend 和线上质量评测，因此保持 L1。
- `PLAN-01` 修正失败投影但仍缺真实 Provider proposal/执行质量与跨平台安装 Eval，保持 L1；S15 Exit 继续 OPEN。
