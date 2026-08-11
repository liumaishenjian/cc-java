package io.github.liumaishenjian.ccjava.domain.execution;

/**
 * 进程执行后端的稳定身份；身份本身不证明 enforcement。
 *
 * @since 0.13.0
 */
public enum ExecutionBackendId {
    /** 当前用户账户直接执行，明确 UNSANDBOXED。 */
    LOCAL,
    /** Windows host 上的 WSL2 Ubuntu + bwrap。 */
    WSL2_BWRAP,
    /** Docker daemon + pinned image。 */
    DOCKER_CONTAINER,
    /** 原生 Windows process/env 控制。 */
    NATIVE_WINDOWS,
    /** macOS sandbox seam；当前仅契约级。 */
    MACOS_SANDBOX
}
