package io.github.liumaishenjian.ccjava.domain.instructions;

/**
 * 指令发现诊断的安全展示严重级别。
 *
 * @since 0.8.0
 */
public enum InstructionDiagnosticSeverity {
    /** 候选被安全跳过，但可继续使用其他指令。 */
    WARNING,
    /** 请求本身无法形成完整投影。 */
    ERROR
}
