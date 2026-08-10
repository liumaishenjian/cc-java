package io.github.liumaishenjian.ccjava.tools.local.command;

import io.github.liumaishenjian.ccjava.domain.execution.EnforcementReport;

/**
 * 一次受控命令执行的稳定结果。
 *
 * @param shell Shell ID
 * @param exitCode 进程退出码；被终止且无法取得时为 -1
 * @param timedOut 是否达到命令自身期限
 * @param cancelled 是否观察到 Run 取消
 * @param stdout 有界 stdout
 * @param stderr 有界 stderr
 * @param truncated 是否丢弃了超出预算的输出
 * @param originalCharacters 已观察到的原始输出字符数
 * @param enforcement 实际执行后端与五维强制报告
 * @since 0.4.0
 */
public record CommandExecutionResult(
        String shell,
        int exitCode,
        boolean timedOut,
        boolean cancelled,
        String stdout,
        String stderr,
        boolean truncated,
        long originalCharacters,
        EnforcementReport enforcement) {
}
