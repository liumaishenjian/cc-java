package io.github.liumaishenjian.ccjava.domain.execution;

/**
 * 命令的显式解释语义。{@link #WINDOWS_PLATFORM} 不得被后端隐式转换为 Linux shell。
 *
 * @since 0.13.0
 */
public enum ExecutionShell {
    WINDOWS_PLATFORM,
    POSIX_PLATFORM,
    LINUX_SH,
    FIXED_ARGV
}
