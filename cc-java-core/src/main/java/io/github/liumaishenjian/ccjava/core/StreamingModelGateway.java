package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;

/**
 * 支持文本增量和异步取消的单回合模型端口。
 *
 * <p>实现必须在返回前聚合完整 Assistant Message 和 Tool Call；增量只用于观察，
 * 不能提前进入规范消息历史。</p>
 *
 * @since 0.1.0
 */
public interface StreamingModelGateway extends ModelGateway {

    /**
     * 流式执行并聚合一个 Model Turn。
     *
     * @param request 不可变请求快照
     * @param observer 文本增量观察者
     * @param cancellation 取消传播 Token
     * @return 聚合完成的模型回合
     * @throws ModelGatewayException Provider 失败或请求取消时抛出
     */
    ModelTurn complete(
            ModelRequest request,
            ModelStreamObserver observer,
            CancellationToken cancellation) throws ModelGatewayException;

    @Override
    default ModelTurn complete(ModelRequest request) throws ModelGatewayException {
        return complete(request, ModelStreamObserver.noop(), CancellationToken.none());
    }
}
