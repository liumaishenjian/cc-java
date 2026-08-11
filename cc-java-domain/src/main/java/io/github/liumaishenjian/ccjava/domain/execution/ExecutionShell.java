package io.github.liumaishenjian.ccjava.domain.execution;

/**
 * 命令的显式解释语义；后端不得隐式改写 Shell。
 *
 * @since 0.13.0
 */
public enum ExecutionShell {
    /** Windows PowerShell/cmd 平台语义。 */
    WINDOWS_PLATFORM,
    /** host POSIX /bin/sh 语义。 */
    POSIX_PLATFORM,
    /** 明确批准的 Linux /bin/sh 语义。 */
    LINUX_SH,
    /** 宿主预注册且不经过 Shell 的 argv。 */
    FIXED_ARGV
}
