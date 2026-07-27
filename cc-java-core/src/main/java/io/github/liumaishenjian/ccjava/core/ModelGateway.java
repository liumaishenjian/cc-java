package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;

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
}
