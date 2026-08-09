package io.github.liumaishenjian.ccjava.core.plugin;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginFingerprint;

/**
 * 在 Plugin 内容进入 Registry 或 Provider 前验证精确 fingerprint 的宿主信任端口。
 *
 * <p>信任只针对完整内容身份；它不代表签名验证、作者认证或 OS Sandbox。</p>
 *
 * @since 0.11.0
 */
@FunctionalInterface
public interface PluginTrustGate {

    /**
     * 判断宿主是否信任该精确 fingerprint。
     *
     * @param fingerprint 已重新计算的内容身份
     * @return 仅精确匹配可信配置时为 {@code true}
     */
    boolean isTrusted(PluginFingerprint fingerprint);
}
