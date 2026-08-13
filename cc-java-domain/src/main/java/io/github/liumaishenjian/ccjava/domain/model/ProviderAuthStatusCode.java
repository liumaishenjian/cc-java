package io.github.liumaishenjian.ccjava.domain.model;

/**
 * Provider credential 的非秘密、本机可观察状态。
 *
 * <p>状态只描述当前进程和用户级 store 能证明的事实；它不携带路径、SecretRef、
 * endpoint 或远端错误，也不表示 Provider 侧 credential 已被撤销。</p>
 *
 * @since 0.1.0
 */
public enum ProviderAuthStatusCode {
    /** 本机 profile 与其引用可安全解析；尚未证明网络有效。 */
    AVAILABLE_LOCAL,
    /** profile 存在，但引用的本机 secret 不存在。 */
    MISSING_SECRET,
    /** 当前平台无法证明 store 仅允许当前用户访问。 */
    INSECURE_STORE,
    /** Provider Definition 不满足本地契约。 */
    INVALID_DEFINITION,
    /** credential index 或事务日志损坏。 */
    CORRUPT_STORE,
    /** 当前进程已 fence 该 profile，禁止新 lease。 */
    REVOKED_IN_PROCESS,
    /** 未执行显式 probe，网络状态未知。 */
    UNKNOWN_NETWORK
}
