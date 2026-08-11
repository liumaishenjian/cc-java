package io.github.liumaishenjian.ccjava.core.plugin;

/**
 * Plugin signature Port 的封闭验证结果，不代表 publisher 身份或撤销状态。
 *
 * @param valid 当前 verifier 是否接受该 envelope
 * @param reason 封闭原因码，用于安全诊断而非自由文本
 */
public record PluginSignatureVerification(boolean valid, PluginSignatureReason reason) {
}
