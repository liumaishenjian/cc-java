package io.github.liumaishenjian.ccjava.domain;

/**
 * 命令类 Tool 可以逐步发布的标准输出通道。
 *
 * <p>该枚举只描述逻辑 stdout/stderr，不表示终端 TTY，也不允许 Surface
 * 反向写入子进程 stdin。</p>
 *
 * @since 0.4.0
 */
public enum ToolOutputStream {

    /** 子进程标准输出。 */
    STDOUT,

    /** 子进程标准错误。 */
    STDERR
}
