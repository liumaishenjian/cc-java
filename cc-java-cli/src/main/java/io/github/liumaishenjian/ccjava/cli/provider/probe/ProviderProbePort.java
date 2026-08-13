package io.github.liumaishenjian.ccjava.cli.provider.probe;

import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.time.Duration;

/**
 * 对一个已验证 definition/profile 执行恰好一次、不计费的鉴权探测端口。
 *
 * <p>实现只能调用由 Provider definition 派生的 models endpoint，禁止接受通用 URL、重试、重定向、
 * profile rotation 或 completion fallback。secret 数组由调用方拥有，实现不得记录或保留。</p>
 */
@FunctionalInterface
public interface ProviderProbePort {
    /**
     * 执行一次受控探测并返回封闭状态；transport 失败不得携带远端正文或异常消息。
     *
     * @param definition 已通过校验且用于派生探测端点的 Provider 定义
     * @param modelId Provider 模型目录中的精确模型标识
     * @param apiKey 仅供本次探测使用且所有权仍属于调用方的密钥字符数组
     * @param timeout 本次探测允许占用的最长时间
     * @param cancellation 用于中止本次探测的取消令牌
     * @return 不包含远端正文或异常消息的稳定探测结果
     */
    ProbeOutcome probe(ProviderDefinition definition, String modelId, char[] apiKey,
                       Duration timeout, CancellationToken cancellation);

    /** 可持久化的稳定探测状态。 */
    enum ProbeOutcome {
        /** Provider 接受凭证且探测成功。 */
        SUCCESS,
        /** Provider 明确拒绝凭证。 */
        REJECTED,
        /** Provider 对探测实施了速率限制。 */
        RATE_LIMITED,
        /** 无法连接 Provider 探测端点。 */
        UNREACHABLE,
        /** 探测未在规定时限内完成。 */
        TIMED_OUT,
        /** 探测被调用方取消。 */
        CANCELLED,
        /** Provider 不支持约定的探测方式。 */
        UNSUPPORTED
    }
}