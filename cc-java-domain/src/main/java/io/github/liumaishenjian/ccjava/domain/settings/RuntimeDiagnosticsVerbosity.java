package io.github.liumaishenjian.ccjava.domain.settings;

/**
 * Runtime 可消费的诊断详细程度。
 *
 * <p>该枚举只控制已经脱敏的诊断投影粒度，不能开启原始 Settings、凭证、端点、
 * selector 或 Tool 参数的输出。</p>
 *
 * @since 0.8.0
 */
public enum RuntimeDiagnosticsVerbosity {

    /** 不向普通 Runtime 投影额外诊断。 */
    OFF,

    /** 投影固定分类和来源摘要。 */
    SUMMARY,

    /** 投影受限字段路径等已脱敏细节。 */
    DETAIL
}
