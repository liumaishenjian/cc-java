package io.github.liumaishenjian.ccjava.domain.execution;

/**
 * 用户在可信 CLI 控制面明确选择的执行模式；模型和项目文本不能设置。
 *
 * @since 0.13.0
 */
public enum ExecutionBackendPreference {
    LOCAL,
    SANDBOX,
    CONTAINER
}
