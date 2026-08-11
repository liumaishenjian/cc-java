package io.github.liumaishenjian.ccjava.domain.model;

/**
 * 能力证据的确定性状态；未知值绝不能被当作支持。
 *
 * @since 0.1.0
 */
public enum CapabilitySupport {
    /** 配置与实际观察均证明支持。 */
    SUPPORTED,
    /** 配置或实际观察明确证明不支持。 */
    UNSUPPORTED,
    /** 缺少足够证据，调用方必须按不支持处理。 */
    UNKNOWN
}
