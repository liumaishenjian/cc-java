package io.github.liumaishenjian.ccjava.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 一项经过确定性应用代码验证后写入 Ledger 的有界证据引用。
 *
 * <p>引用只保存相对路径或 Call ID、摘要和封闭 reason，不保存文件正文、命令输出、模型文本、
 * Prompt、Secret 或异常原文。SKIPPED 必须携带独立用户决定 ID。</p>
 *
 * @param requirementId 对应要求
 * @param status 证据结论
 * @param sourceType 固定来源类型
 * @param sourceReference 相对路径、Call ID 或用户决定 ID
 * @param contentDigest 可用时的内容摘要
 * @param reasonCode 封闭、隐私安全原因
 * @param recordedAt durable 记录时间
 * @since 0.1.0
 */
public record PlanEvidenceReference(String requirementId, PlanEvidenceStatus status, String sourceType,
                                    String sourceReference, Optional<String> contentDigest,
                                    String reasonCode, Instant recordedAt) {
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    /** 验证引用不携带自由输出或无界元数据。 */
    public PlanEvidenceReference {
        requirementId = bounded(requirementId, "requirementId", 64);
        status = Objects.requireNonNull(status, "status 不能为空");
        sourceType = bounded(sourceType, "sourceType", 32);
        sourceReference = bounded(sourceReference, "sourceReference", 512);
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest 不能为空");
        contentDigest.ifPresent(value -> { if (!SHA.matcher(value).matches()) throw new IllegalArgumentException("contentDigest 无效"); });
        reasonCode = bounded(reasonCode, "reasonCode", 64);
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt 不能为空");
        if (status == PlanEvidenceStatus.EXPECTED) throw new IllegalArgumentException("引用不能保持 EXPECTED");
        if (status == PlanEvidenceStatus.SKIPPED && !sourceType.equals("USER_SKIP_DECISION")) {
            throw new IllegalArgumentException("SKIPPED 必须来自显式用户决定");
        }
    }

    private static String bounded(String value, String field, int max) {
        value = Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(field + " 无效");
        return value;
    }
}
