package io.github.liumaishenjian.ccjava.core.telemetry;

/** 生产观测出口允许的封闭信号类型。 */
public enum TelemetrySignalKind {
    /** Agent Run 生命周期。 */
    RUN,
    /** 单个模型回合生命周期。 */
    MODEL_TURN,
    /** Tool Call 生命周期。 */
    TOOL_CALL,
    /** Provider 重试尝试。 */
    RETRY,
    /** Checkpoint、compaction 或 fallback 恢复。 */
    RECOVERY,
    /** Provider 明确报告的 Token Usage。 */
    TOKEN_USAGE,
    /** 可信价格与 Usage 同时存在时的已知费用。 */
    KNOWN_COST,
    /** Run 唯一终止原因。 */
    STOP
}
