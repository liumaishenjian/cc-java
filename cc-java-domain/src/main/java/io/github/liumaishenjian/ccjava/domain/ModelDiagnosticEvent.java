package io.github.liumaishenjian.ccjava.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次模型尝试的隐私安全诊断事件。
 *
 * <p>该 record 的字段集合是严格白名单：只包含项目身份关联、封闭枚举、布尔事实、
 * 有界耗时和时间戳。它刻意没有 Prompt、响应、Endpoint、Header、Provider request ID、
 * 路径、Tool 数据、异常或 SDK 类型字段，也不进入 AgentRunResult、Session 或 stdio。</p>
 *
 * @param schemaVersion 诊断记录 schema，当前固定为 1
 * @param kind 封闭事件种类
 * @param sessionCorrelation 不可逆的 Session 关联值，不保存原始 SessionId
 * @param runCorrelation 不可逆的 Run 关联值，不保存原始 RunId
 * @param turnNumber 从 1 开始的模型回合序号
 * @param attemptNumber 从 1 开始的本回合尝试序号
 * @param stage 失败或当前生命周期所在阶段
 * @param reason 失败原因；非失败事件必须为 UNKNOWN
 * @param statusClass 粗粒度 HTTP 状态组
 * @param receivedProviderFrame 是否收到过 Provider frame
 * @param emittedUserText 是否向用户事件通道发出过文本
 * @param elapsedMillis 本次尝试已耗时毫秒
 * @param recordedAt 本机记录时间
 * @since 0.1.0
 */
public record ModelDiagnosticEvent(
        int schemaVersion,
        ModelDiagnosticKind kind,
        UUID sessionCorrelation,
        UUID runCorrelation,
        int turnNumber,
        int attemptNumber,
        ModelFailureStage stage,
        ModelFailureReason reason,
        ModelDiagnosticStatusClass statusClass,
        boolean receivedProviderFrame,
        boolean emittedUserText,
        long elapsedMillis,
        Instant recordedAt) {

    /** 当前诊断 schema 版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * 校验封闭诊断事件。
     */
    public ModelDiagnosticEvent {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion 必须为 1");
        }
        Objects.requireNonNull(kind, "kind 不能为空");
        Objects.requireNonNull(sessionCorrelation, "sessionCorrelation 不能为空");
        Objects.requireNonNull(runCorrelation, "runCorrelation 不能为空");
        Objects.requireNonNull(stage, "stage 不能为空");
        Objects.requireNonNull(reason, "reason 不能为空");
        Objects.requireNonNull(statusClass, "statusClass 不能为空");
        Objects.requireNonNull(recordedAt, "recordedAt 不能为空");
        if (turnNumber < 1 || attemptNumber < 1) {
            throw new IllegalArgumentException("turnNumber 和 attemptNumber 必须从 1 开始");
        }
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis 不能为负数");
        }
        if (kind != ModelDiagnosticKind.FAILURE && reason != ModelFailureReason.UNKNOWN) {
            throw new IllegalArgumentException("非失败事件的 reason 必须为 UNKNOWN");
        }
    }
}
