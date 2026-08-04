package io.github.liumaishenjian.ccjava.domain;

/**
 * 标识 Context Token 数量的来源可信度。
 *
 * <p>估算值只用于 Core 的本地预算规划，不能冒充 Provider 返回的真实 Usage。</p>
 *
 * @since 0.7.0
 */
public enum ContextEstimateKind {

    /** 由确定性本地算法估算。 */
    ESTIMATED,

    /** 由模型 Provider 的精确计数能力提供。 */
    EXACT
}
