package io.github.liumaishenjian.ccjava.core.plugin;

/** Plugin 签名验证的固定隐私安全原因。 */
public enum PluginSignatureReason {
    /** 签名字节与 payload digest/key reference 匹配。 */
    VALID,
    /** 宿主不支持信封声明的算法。 */
    ALGORITHM_UNSUPPORTED,
    /** 宿主未配置对应 key reference。 */
    KEY_UNKNOWN,
    /** 计算所得 payload digest 与信封不一致。 */
    PAYLOAD_DIGEST_MISMATCH,
    /** 签名验证明确失败。 */
    SIGNATURE_INVALID,
    /** Verifier 内部故障，按失败关闭处理。 */
    VERIFIER_FAILURE
}
