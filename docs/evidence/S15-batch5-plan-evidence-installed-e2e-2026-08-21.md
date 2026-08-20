# S15 Batch 5 Plan Evidence 与安装版 E2E 证据

- Date: 2026-08-21
- Stage: S15 `IN_PROGRESS` / Exit `OPEN`
- Feature IDs: `PLAN-01`、`CLI-01/06`、`DIST-02/06`
- Authorized Snapshot: `AUTH-SRC-2026-07-29-A`
- Capability Level: 无变化；`PLAN-01` L1
- Working tree: 未提交；本证据不冒充 Commit-scoped Stage Exit

## G0-G3

ADR-080 记录 Batch 5 只读研究与 `Observed/Inferred/Unknown`。`PlanEvidenceLedger` 是 codej
独立增强：规划期声明要求，批准时绑定 ExecutionBrief/workspace revision，执行后只接受确定性文件或
ToolResult 证据；普通 Agent Run 完成但证据不足时保持 `NEEDS_VERIFICATION`。

## G4-G5 验证结果

| 命令 | 结果 |
| --- | --- |
| `./mvnw.cmd clean verify` | BUILD SUCCESS；1,121 tests / 36 skips / 0 failures / 0 errors（186 Surefire XML） |
| `npm --prefix cc-java-tui run check` | build 通过；15 files / 221 tests 全绿 |
| real Java Plan E2E | 1/1；真实 Java stdio、durable review、真实 `git_status` Tool/output、唯一完成、exit 0、stderr 0 |
| `scripts/TestBuildRelease.ps1` | 77 components；4,810 checksums；commit/source/JAR/TUI digest 对账与 drift 负例通过 |
| `scripts/TestCodejDevLauncher.ps1` | 60 assertions |
| package/install lifecycle | Windows archive、安装、版本/build identity、doctor、checksum tamper、卸载通过 |

Dashboard generate/check/self-test 均通过；`git diff --check` exit 0（仅 Windows line-ending warning）。普通 CI 不使用网络或 Provider Secret。

## Gaps

1. 至少两个真实 Provider 的 BYOK 在线 Plan/Tool/cancel 证据仍缺；
2. 本批未证明 Linux/macOS 安装版交互 TUI 与 child lifecycle；
3. typed verification skip 当前为 Java Application API，尚未新增公开 TUI picker；
4. Evidence declaration 只支持相对普通文件和成功 Tool 名，不执行内容语义或测试报告格式解析；
5. S15 L4 A/B Eval、多人 Plan 冲突与稳定外部 migration 仍未完成。
