package io.github.liumaishenjian.ccjava.model.springai.provider;

import io.github.liumaishenjian.ccjava.core.ModelGateway;

/**
 * 把单个 Provider 定义、模型与短生命周期 secret 组装为模型 Gateway 的协议工厂。
 *
 * <p>实现不持久化 secret、不重试选择、不执行 profile rotation，也不创建 {@code ProviderRouter}；
 * Router 由上层以恰好一个 route 统一装配。</p>
 */
public interface ProviderGatewayFactory {
    /**
     * 返回本工厂唯一支持的编译期协议种类。
     *
     * @return 本工厂支持的 Provider 协议种类
     */
    ProviderGatewayKind kind();

    /**
     * 创建一个只服务当前 run selection 的 Gateway。
     *
     * @param configuration 已验证的短生命周期 Provider 连接输入
     * @return 仅服务当前 run selection 的模型 Gateway
     */
    ModelGateway create(ProviderGatewayConfiguration configuration);
}
