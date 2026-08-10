package io.github.liumaishenjian.ccjava.domain.execution;

/**
 * 进程执行后端的稳定身份。{@link #LOCAL} 明确表示未提供 OS Sandbox。
 *
 * @since 0.13.0
 */
public enum ExecutionBackendId {
    LOCAL,
    WSL2_BWRAP,
    DOCKER_CONTAINER,
    NATIVE_WINDOWS,
    MACOS_SANDBOX
}
