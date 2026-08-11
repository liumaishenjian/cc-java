package io.github.liumaishenjian.ccjava.protocol;

/** Initialize 可协商的稳定能力。 */
public enum ProtocolFeature {
    /** 提交 Agent Run。 */
    RUN,
    /** 取消活动 Run。 */
    CANCEL,
    /** 恢复既有 Session。 */
    SESSION_RESUME,
    /** 导出 Session metadata 或经确认脱敏的正文。 */
    SESSION_EXPORT,
    /** 归档或二次确认永久删除 Session。 */
    SESSION_RETENTION,
    /** 迁移 canonical Session schema。 */
    SESSION_MIGRATION,
    /** 分页列出和搜索 Session metadata index。 */
    SESSION_INDEX,
    /** 查询 Managed Policy 与 Feature Gate 投影。 */
    GOVERNANCE,
    /** 使用 Session Checkpoint 能力。 */
    CHECKPOINT,
    /** 通过独立 stable daemon 进程承载协议。 */
    DAEMON,
    /** 协商实验 Feature Gate 元数据，不改变 stable schema。 */
    EXPERIMENTAL_FEATURE_GATES
}
