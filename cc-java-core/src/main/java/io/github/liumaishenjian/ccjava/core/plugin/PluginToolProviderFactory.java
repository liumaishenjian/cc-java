package io.github.liumaishenjian.ccjava.core.plugin;

/**
 * 由宿主代码注册的受限 Plugin Tool Provider factory。
 *
 * <p>manifest 只能选择宿主已知的 provider type，不能声明 class、JAR、ServiceLoader、
 * reflection、native 或 script 入口。</p>
 *
 * @since 0.11.0
 */
public interface PluginToolProviderFactory {

    /**
     * 返回宿主固定的 provider type。
     *
     * @return manifest 可引用的稳定 provider type；S11 首个实现只能是 {@code mcp-backed}
     */
    String providerType();

    /**
     * 创建独占 Tool 和底层资源。
     *
     * @param descriptor 已绑定可信 snapshot 的 provider 描述
     * @param snapshotLease 所有权转移给成功返回的 contribution
     * @return 必须经统一 ToolRegistry/Pipeline 使用的 contribution
     * @throws Exception 创建失败；factory 必须回收已创建资源并关闭传入 lease
     */
    PluginToolContribution create(
            PluginToolProviderDescriptor descriptor,
            PluginLease snapshotLease) throws Exception;
}
