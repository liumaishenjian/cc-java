package io.github.liumaishenjian.ccjava.domain.execution;

/**
 * OS 执行隔离相互独立的强制维度。
 *
 * @since 0.13.0
 */
public enum EnforcementDimension {
    /** 文件系统读取/写入边界。 */
    FILE,
    /** 进程及后代生命周期边界。 */
    PROCESS,
    /** 网络 egress 边界。 */
    NETWORK,
    /** 子进程环境变量构造边界。 */
    ENVIRONMENT,
    /** Secret 注入与泄漏边界。 */
    SECRET
}
