package io.github.liumaishenjian.ccjava.domain.execution;

/**
 * OS 执行隔离相互独立的强制维度。
 *
 * @since 0.13.0
 */
public enum EnforcementDimension {
    FILE,
    PROCESS,
    NETWORK,
    ENVIRONMENT,
    SECRET
}
