package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.SummaryRequest;
import java.util.List;
import java.util.Objects;

/**
 * 单次 C3/C4 规划使用的显式、不可变 Gate 输入。
 *
 * <p>{@code rollingWindowEndExclusive} 是 Canonical/Projection 消息位置边界；零表示 C3
 * 不具备 rolling window。C4 仅在 {@code fullSummaryPrerequisitesSatisfied} 为真时归纳全部
 * 非 System、非 protected 尾部消息。两个层级分别携带必须原样保留的事实锚点。</p>
 *
 * @param rollingWindowEndExclusive C3 前缀来源的排他结束位置，零表示跳过 C3
 * @param fullSummaryPrerequisitesSatisfied C4 的外部业务前提是否已满足
 * @param rollingProtectedAnchors C3 必须保留的事实锚点
 * @param fullProtectedAnchors C4 必须保留的事实锚点
 * @param maxOutputUtf8Bytes 每个候选的请求级 UTF-8 字节上限
 * @param maxOutputTokens 每个候选的请求级 Token 上限
 * @since 0.7.0
 */
public record SummaryReductionPolicy(
        int rollingWindowEndExclusive,
        boolean fullSummaryPrerequisitesSatisfied,
        List<String> rollingProtectedAnchors,
        List<String> fullProtectedAnchors,
        int maxOutputUtf8Bytes,
        long maxOutputTokens) {

    /** 防御性复制并校验不依赖具体 Projection 的静态边界。 */
    public SummaryReductionPolicy {
        if (rollingWindowEndExclusive < 0) {
            throw new IllegalArgumentException("rollingWindowEndExclusive 不能为负数");
        }
        rollingProtectedAnchors = List.copyOf(Objects.requireNonNull(
                rollingProtectedAnchors, "rollingProtectedAnchors 不能为空"));
        fullProtectedAnchors = List.copyOf(Objects.requireNonNull(
                fullProtectedAnchors, "fullProtectedAnchors 不能为空"));
        if (rollingProtectedAnchors.size() > SummaryRequest.MAX_PROTECTED_ANCHORS
                || fullProtectedAnchors.size() > SummaryRequest.MAX_PROTECTED_ANCHORS) {
            throw new IllegalArgumentException("protected anchors 超过数量上限");
        }
        if (maxOutputUtf8Bytes < 1
                || maxOutputUtf8Bytes > SummaryRequest.MAX_OUTPUT_UTF8_BYTES) {
            throw new IllegalArgumentException("maxOutputUtf8Bytes 超出允许范围");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > SummaryRequest.MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("maxOutputTokens 超出允许范围");
        }
    }
}
