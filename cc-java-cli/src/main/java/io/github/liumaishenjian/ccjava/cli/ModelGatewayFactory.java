package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.core.ModelGateway;

/**
 * 在 CLI Composition Root 边界创建一个真实模型 Gateway。
 *
 * <p>该端口只隔离尚在演进的 Spring AI Provider 装配。创建出的
 * {@link ModelGateway} 仍只完成一个模型回合，Agent Loop、Tool Pipeline、
 * 取消和终态决策全部留在 Core。Secret 只能在本边界从
 * {@link CliEnvironment} 按需读取。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface ModelGatewayFactory {

    /**
     * 创建单回合模型 Gateway。
     *
     * @param configuration 不含 Secret 值的最终 CLI 配置
     * @param environment Secret 的延迟读取边界
     * @return 已装配 Gateway
     * @throws CliStartupException Provider 配置或初始化失败时
     */
    ModelGateway create(
            CliConfiguration configuration,
            CliEnvironment environment) throws CliStartupException;
}
