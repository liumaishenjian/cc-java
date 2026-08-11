package io.github.liumaishenjian.ccjava.domain.execution;

/**
 * 用户在可信 CLI 控制面明确选择的执行模式；模型和项目文本不能设置。
 *
 * @since 0.13.0
 */
public enum ExecutionBackendPreference {
    /** 明确接受未提供 OS Sandbox 的 Local 执行。 */
    LOCAL,
    /** 要求平台 Sandbox，缺失时默认 Fail Closed。 */
    SANDBOX,
    /** 要求可选 Container backend。 */
    CONTAINER
}
