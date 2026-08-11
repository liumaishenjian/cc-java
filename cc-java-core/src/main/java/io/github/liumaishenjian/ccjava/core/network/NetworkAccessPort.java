package io.github.liumaishenjian.ccjava.core.network;

import io.github.liumaishenjian.ccjava.core.CancellationToken;

/**
 * 对 JVM 内受控出站访问执行审计和策略判断的端口。
 *
 * <p>该端口不是 OS Sandbox；第三方 SDK 绕过该端口时必须报告不受控，不能宣称网络强制。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface NetworkAccessPort {
    /**
     * 在创建或执行出站请求前做确定性判断。
     *
     * @param request 不含 Header、凭证或正文的访问意图
     * @param cancellation 当前调用取消令牌
     * @return 允许、拒绝或不受控的封闭决定
     */
    NetworkAccessDecision authorize(NetworkAccessRequest request, CancellationToken cancellation);
}
