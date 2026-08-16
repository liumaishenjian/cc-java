# S15 PERM-05 Gap Report

- Date: 2026-08-17
- Feature: `PERM-05 Auto Mode`
- Level: `L1`
- Stage: S15 IN_PROGRESS / Stage Exit OPEN

## 已实现

- `Plan / Ask for approval / Approve for me` 三项产品选择及不可变 mode/reviewer 映射；
- stdio v0 严格 selection 输入与 mode/reviewer/selection 查询投影；
- React/Ink 三项 picker、保守默认、单次提交、Esc 与兼容 mode；
- final-ASK-only 的 Headless 模型复核、空 Tool 请求、严格 once/deny；
- 失败关闭、共享取消、Run-owned 三次 non-allow circuit 与批次 typed stop；
- 离线 Fake、Headless、stdio 和 TUI E2E。

## 仍缺失

1. 至少一个真实 Provider 上的对抗性误放行、误拒绝与提示注入评测；
2. 能兼顾最小披露和有效语义判断的上下文/Tool 摘要质量基线；
3. reviewer 延迟、token 成本、失败率及与人工审批的 A/B 数据；
4. 独立 reviewer 模型/路由、配额与可观察性策略；
5. 稳定外部协议或 SDK 承诺。

## 风险

- 当前粗粒度脱敏摘要可能导致 reviewer 主要按 Tool effect 决策，不能证明对具体意图有足够辨识力；
- 复用当前 Provider Gateway 会增加一次模型请求的延迟与成本；
- Fake 的确定性通过不能代表真实模型在对抗输入下的安全性。

在上述质量证据完成前，README、矩阵和发布说明不得把该能力描述为成熟 Auto Mode、无人监督执行、
与参考产品等价或 `PERM-05 L2`。
