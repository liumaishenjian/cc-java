package io.github.liumaishenjian.ccjava.core.settings;

import io.github.liumaishenjian.ccjava.domain.JsonObject;

/**
 * 由可信 Composition Root 为单个 builtin Tool 声明的非敏感配置 schema。
 *
 * <p>schema 只验证 Tool 自己允许的参数形状，不能授予执行权限或改变 Tool Definition、
 * Workspace、Shell、网络、Sandbox、超时、敏感路径及结果上限。</p>
 *
 * @since 0.8.0
 */
@FunctionalInterface
public interface TrustedToolConfigurationSchema {

    /**
     * 判断已解析的配置是否属于此 Tool 明确允许的非敏感子集。
     *
     * @param configuration 不可变 JSON 对象
     * @return 仅当配置完全符合可信 schema 时为 {@code true}
     */
    boolean accepts(JsonObject configuration);
}
