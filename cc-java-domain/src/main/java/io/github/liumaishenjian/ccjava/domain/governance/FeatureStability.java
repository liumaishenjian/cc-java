package io.github.liumaishenjian.ccjava.domain.governance;

/** Feature gate 的兼容性承诺等级。 */
public enum FeatureStability {
    /** stable protocol 可协商的兼容能力。 */
    STABLE,
    /** 可禁用且不改变 stable schema 的实验能力。 */
    EXPERIMENTAL
}
