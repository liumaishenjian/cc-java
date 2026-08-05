package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * 面向内部观察的、隐私安全的单次 Context 使用量快照。
 *
 * <p>该类型只保存数值、枚举和 {@link ContextReduction} 的统计元数据，不保存 Projection 消息、
 * Prompt、项目指令正文、文件路径、Tool 参数/结果、记忆正文、模型异常或任意自由文本。它是 S07
 * 的内部契约，不是稳定外部协议或 S08 {@code /context} 的输出模型。</p>
 *
 * @param usage 按 System、Instructions、Transcript、Tool 和 Memory 分类的估算
 * @param maximumInputTokens 模型声明的最大输入容量
 * @param reservedOutputTokens 为输出保留的容量
 * @param safetyMarginTokens 为估算误差保留的容量
 * @param availableInputTokens 实际可供 Projection 使用的输入预算
 * @param freeTokens 可用输入预算减去当前总估算后的余量，可为负数
 * @param overflowTokens 超过输入预算的 Token 数，不能为负数
 * @param sourceRevision Canonical Transcript 的数值 revision
 * @param appliedReductions 已提交的 C1-C4 数值化统计
 * @param status Preparation 或 overflow recovery 的终态
 * @param reasonCodes 固定解释码，不能包含自由文本
 * @param modelRequestAttempts 当前 recovery 阶段已执行的模型请求数；普通准备为零
 * @since 0.7.0
 */
public record ContextUsageView(
        ContextUsage usage,
        long maximumInputTokens,
        long reservedOutputTokens,
        long safetyMarginTokens,
        long availableInputTokens,
        long freeTokens,
        long overflowTokens,
        long sourceRevision,
        List<ContextReduction> appliedReductions,
        ContextPreparationStatus status,
        List<ContextUsageReasonCode> reasonCodes,
        int modelRequestAttempts) {

    /**
     * 校验容量派生值、使用量和受限诊断后创建 View。
     *
     * @throws IllegalArgumentException 数值不一致、计数非法或普通准备携带模型请求次数时
     */
    public ContextUsageView {
        usage = Objects.requireNonNull(usage, "usage 不能为空");
        appliedReductions = List.copyOf(Objects.requireNonNull(
                appliedReductions, "appliedReductions 不能为空"));
        status = Objects.requireNonNull(status, "status 不能为空");
        reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes 不能为空"));
        if (maximumInputTokens <= 0 || reservedOutputTokens < 0 || safetyMarginTokens < 0
                || availableInputTokens <= 0 || overflowTokens < 0 || sourceRevision < 0
                || modelRequestAttempts < 0 || modelRequestAttempts > 2) {
            throw new IllegalArgumentException("Context Usage View 计数非法");
        }
        long expectedAvailable = Math.subtractExact(
                Math.subtractExact(maximumInputTokens, reservedOutputTokens), safetyMarginTokens);
        if (availableInputTokens != expectedAvailable || availableInputTokens <= 0) {
            throw new IllegalArgumentException("availableInputTokens 必须由容量边界派生");
        }
        long expectedFree = Math.subtractExact(availableInputTokens, usage.totalTokens());
        if (freeTokens != expectedFree || usage.remainingTokens() != freeTokens) {
            throw new IllegalArgumentException("freeTokens 必须匹配 Usage 和容量预算");
        }
        if (overflowTokens != Math.max(0L, -freeTokens)) {
            throw new IllegalArgumentException("overflowTokens 必须匹配负余量");
        }
        if (usage.instructionTokens() != 0
                || !reasonCodes.contains(ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM)) {
            throw new IllegalArgumentException("项目指令必须与 System 合并且不可单独归因");
        }
        if (reasonCodes.size() != new java.util.HashSet<>(reasonCodes).size()) {
            throw new IllegalArgumentException("reasonCodes 不能重复");
        }
        if (status == ContextPreparationStatus.PREPARED) {
            if (modelRequestAttempts != 0
                    || reasonCodes.size() != 1) {
                throw new IllegalArgumentException("普通准备不能携带恢复尝试或恢复原因");
            }
        } else {
            if (modelRequestAttempts == 0
                    || !reasonCodes.contains(ContextUsageReasonCode.TYPED_CONTEXT_OVERFLOW)) {
                throw new IllegalArgumentException("overflow recovery 必须携带已执行次数和 typed overflow 原因");
            }
            long summaryOutcomeCodes = reasonCodes.stream().filter(code -> code
                    == ContextUsageReasonCode.OVERFLOW_SUMMARY_ADOPTED
                    || code == ContextUsageReasonCode.OVERFLOW_SUMMARY_UNCHANGED
                    || code == ContextUsageReasonCode.OVERFLOW_RECOVERY_CANCELLED).count();
            if (summaryOutcomeCodes > 1) {
                throw new IllegalArgumentException("overflow recovery 不能携带互相矛盾的摘要结果");
            }
        }
    }

    /**
     * 从 Projection 和相同容量边界构造普通准备 View。
     *
     * @param projection 已完成的短生命周期 Projection
     * @param capacity 产生该 Projection Usage 的容量边界
     * @return 不携带模型请求次数的安全数值 View
     */
    public static ContextUsageView prepared(ContextProjection projection, ContextCapacity capacity) {
        return create(projection, capacity, ContextPreparationStatus.PREPARED,
                List.of(ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM), 0);
    }

    /**
     * 从最终 Projection 构造 typed overflow recovery 的安全 View。
     *
     * @param projection 恢复终态对应的 Projection
     * @param capacity 恢复请求使用的容量边界
     * @param reasonCodes 固定 recovery 解释码
     * @param modelRequestAttempts 实际执行次数
     * @return recovery 终态 View
     */
    public static ContextUsageView recovered(
            ContextProjection projection,
            ContextCapacity capacity,
            List<ContextUsageReasonCode> reasonCodes,
            int modelRequestAttempts) {
        Objects.requireNonNull(reasonCodes, "reasonCodes 不能为空");
        java.util.ArrayList<ContextUsageReasonCode> codes = new java.util.ArrayList<>(reasonCodes);
        if (!codes.contains(ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM)) {
            codes.add(ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM);
        }
        if (!codes.contains(ContextUsageReasonCode.TYPED_CONTEXT_OVERFLOW)) {
            codes.add(ContextUsageReasonCode.TYPED_CONTEXT_OVERFLOW);
        }
        return create(projection, capacity, ContextPreparationStatus.OVERFLOW_RECOVERY_COMPLETED,
                codes, modelRequestAttempts);
    }

    private static ContextUsageView create(
            ContextProjection projection,
            ContextCapacity capacity,
            ContextPreparationStatus status,
            List<ContextUsageReasonCode> reasonCodes,
            int modelRequestAttempts) {
        Objects.requireNonNull(projection, "projection 不能为空");
        Objects.requireNonNull(capacity, "capacity 不能为空");
        ContextUsage usage = projection.usage();
        long available = capacity.availableInputTokens();
        long free = Math.subtractExact(available, usage.totalTokens());
        return new ContextUsageView(
                usage,
                capacity.maximumInputTokens(),
                capacity.reservedOutputTokens(),
                capacity.safetyMarginTokens(),
                available,
                free,
                Math.max(0L, -free),
                projection.sourceRevision(),
                projection.appliedReductions(),
                status,
                reasonCodes,
                modelRequestAttempts);
    }
}
