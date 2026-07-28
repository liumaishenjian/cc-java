package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import java.util.Objects;

/**
 * 执行一个完整模型回合的 Provider-neutral 端口。
 *
 * <p>Gateway 返回模型原始 Tool Call，但绝不能自行执行 Tool 或驱动后续
 * Agent Loop。S01 使用测试源中的 Scripted Fake 实现，真实 Spring AI
 * Adapter 从 S02 开始接入。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface ModelGateway {

    /**
     * 执行并聚合一个 Model Turn。
     *
     * @param request 不可变请求快照
     * @return 聚合完成的模型回合
     * @throws ModelGatewayException Provider 调用失败时抛出
     */
    ModelTurn complete(ModelRequest request) throws ModelGatewayException;

    /**
     * 在项目自有的流观察、取消和截止时间边界内执行一个 Model Turn。
     *
     * <p>默认实现保留 S01 单参数 Fake 和同步 Gateway 的兼容性，但只能在调用
     * 前后检查取消。S02 真实流式 Adapter 必须覆盖本方法，在订阅期间发布 Delta，
     * 并使用 Token 回调与 deadline 主动终止底层请求。</p>
     *
     * @param request 不可变请求快照
     * @param context 流观察、取消、截止时间和尝试序号
     * @return 聚合完成的模型回合
     * @throws ModelGatewayException Provider 调用失败、取消或超时时
     */
    default ModelTurn complete(
            ModelRequest request,
            ModelCallContext context) throws ModelGatewayException {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        if (context.cancellationToken().isCancellationRequested()) {
            throw ModelGatewayException.cancelled("模型请求在开始前已取消");
        }
        ModelTurn turn = complete(request);
        if (context.cancellationToken().isCancellationRequested()) {
            throw ModelGatewayException.cancelled("模型请求已取消");
        }
        return turn;
    }
}
