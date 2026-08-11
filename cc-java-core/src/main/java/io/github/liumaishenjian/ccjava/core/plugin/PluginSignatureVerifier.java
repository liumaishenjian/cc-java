package io.github.liumaishenjian.ccjava.core.plugin;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginSignatureEnvelope;

/**
 * 宿主提供的 Plugin signature verification port。
 *
 * <p>Port 不负责 key discovery、publisher identity、revocation 或 Marketplace。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface PluginSignatureVerifier {
    /**
     * 验证信封与精确 payload 字节，不执行 key discovery 或网络访问。
     *
     * @param envelope Plugin 提供的最小签名信封
     * @param payload 要验证的精确 canonical 字节
     * @return 固定原因与布尔结果
     */
    PluginSignatureVerification verify(PluginSignatureEnvelope envelope, byte[] payload);
}
