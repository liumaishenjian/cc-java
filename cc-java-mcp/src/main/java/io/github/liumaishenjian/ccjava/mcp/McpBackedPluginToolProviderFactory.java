package io.github.liumaishenjian.ccjava.mcp;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.plugin.PluginLease;
import io.github.liumaishenjian.ccjava.core.plugin.PluginToolContribution;
import io.github.liumaishenjian.ccjava.core.plugin.PluginToolProviderDescriptor;
import io.github.liumaishenjian.ccjava.core.plugin.PluginToolProviderFactory;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentKind;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginNamespace;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * S11 唯一宿主 Provider：把 manifest 已声明的 named MCP servers 映射为 Plugin Tool。
 *
 * <p>factory 接收 lease 后立即接管所有权：成功时转交 Contribution，任何验证、client create、
 * initialize、discover 或映射失败均先关闭 call gate、逆序关闭 client，再 exactly-once 释放 lease。
 * 创建 client 前必须以 {@link McpPluginConfigDigest} 精确匹配 manifest configDigest；诊断不含 endpoint、
 * argv、绝对路径或环境变量名。</p>
 *
 * @since 0.11.0
 */
public final class McpBackedPluginToolProviderFactory implements PluginToolProviderFactory {
    private final Map<String, McpServerConfig> hostConfigs;
    private final McpClientFactory clientFactory;

    /**
     * 创建只允许引用宿主既有 MCP 配置的 Plugin provider factory。
     *
     * @param hostConfigs 已通过设置与 trust Gate 的宿主 MCP 配置
     * @param clientFactory 创建独占远端 Client 的工厂
     */
    public McpBackedPluginToolProviderFactory(List<McpServerConfig> hostConfigs, McpClientFactory clientFactory) {
        Objects.requireNonNull(hostConfigs, "hostConfigs 不能为空");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory 不能为空");
        var indexed = new LinkedHashMap<String, McpServerConfig>();
        for (McpServerConfig config : hostConfigs) {
            if (indexed.putIfAbsent(config.name(), config) != null) {
                throw new IllegalArgumentException("重复 MCP Server config");
            }
        }
        this.hostConfigs = Map.copyOf(indexed);
    }

    @Override public String providerType() { return "mcp-backed"; }

    @Override
    public PluginToolContribution create(
            PluginToolProviderDescriptor descriptor, PluginLease snapshotLease) throws Exception {
        Objects.requireNonNull(snapshotLease, "snapshotLease 不能为空");
        var clients = new ArrayList<McpRemoteClient>();
        var tools = new ArrayList<AgentTool>();
        var callGate = new PluginToolCallGate();
        boolean transferred = false;
        try {
            descriptor = Objects.requireNonNull(descriptor, "descriptor 不能为空");
            if (!snapshotLease.snapshot().equals(descriptor.snapshot())) {
                throw rejected();
            }
            var manifestServers = descriptor.snapshot().manifest().components().stream()
                    .filter(component -> component.kind() == PluginComponentKind.MCP_SERVER)
                    .map(component -> component.name()).collect(java.util.stream.Collectors.toSet());
            if (!manifestServers.containsAll(descriptor.component().references())) throw rejected();

            List<McpServerConfig> referenced = new ArrayList<>();
            for (String serverName : descriptor.component().references()) {
                McpServerConfig config = hostConfigs.get(serverName);
                if (config == null || !config.trusted()) throw rejected();
                referenced.add(config);
            }
            if (!McpPluginConfigDigest.compute(referenced).equals(descriptor.component().configDigest())) {
                throw rejected();
            }

            String providerName = PluginNamespace.qualified(
                    descriptor.snapshot().manifest().id(), PluginComponentKind.TOOL_PROVIDER,
                    descriptor.component().name());
            for (McpServerConfig config : referenced) {
                McpRemoteClient client = clientFactory.create(config);
                clients.add(client);
                client.initialize();
                client.listTools().stream()
                        .filter(tool -> config.includes(tool.name()))
                        .sorted(Comparator.comparing(McpToolDescriptor::name))
                        .map(tool -> (AgentTool) new PluginMcpAgentTool(
                                providerName, tool, client, callGate, config.requestTimeout()))
                        .forEach(tools::add);
            }
            if (tools.stream().map(tool -> tool.definition().name()).distinct().count() != tools.size()) {
                throw rejected();
            }
            var resources = new ArrayList<AutoCloseable>();
            resources.addAll(clients);
            resources.add(callGate);
            PluginToolContribution contribution = new PluginToolContribution(tools, resources, snapshotLease);
            transferred = true;
            return contribution;
        } catch (Exception failure) {
            throw failure;
        } finally {
            if (!transferred) {
                callGate.close();
                closeReverse(clients);
                snapshotLease.close();
            }
        }
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("Plugin MCP Provider 配置拒绝");
    }

    private static void closeReverse(List<McpRemoteClient> clients) {
        for (int index = clients.size() - 1; index >= 0; index--) {
            try { clients.get(index).close(); }
            catch (RuntimeException ignored) { /* 隐私安全清理。 */ }
        }
    }
}
