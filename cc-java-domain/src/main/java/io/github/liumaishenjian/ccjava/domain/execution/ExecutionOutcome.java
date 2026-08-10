package io.github.liumaishenjian.ccjava.domain.execution;

import java.util.Objects;
import java.util.Optional;

/**
 * 进程终态、输出及实际强制证据。产生终态不表示 exit code 为零。
 *
 * @param exitCode 进程退出码；未正常退出时使用后端约定值
 * @param timedOut 是否因期限到达终止
 * @param cancelled 是否因取消终止
 * @param stdout 有界标准输出
 * @param stderr 有界标准错误
 * @param truncated 输出是否被截断
 * @param originalCharacters 截断前观测到的字符总数
 * @param enforcement 实际后端与五维强制报告
 * @param failure 可选结构化失败
 * @since 0.13.0
 */
public record ExecutionOutcome(
        int exitCode,
        boolean timedOut,
        boolean cancelled,
        String stdout,
        String stderr,
        boolean truncated,
        long originalCharacters,
        EnforcementReport enforcement,
        Optional<ExecutionFailure> failure) {
    public ExecutionOutcome {
        stdout = Objects.requireNonNull(stdout);
        stderr = Objects.requireNonNull(stderr);
        enforcement = Objects.requireNonNull(enforcement);
        failure = Objects.requireNonNull(failure);
    }
}
