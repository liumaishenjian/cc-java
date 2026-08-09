package io.github.liumaishenjian.ccjava.mcp;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * 管理多个 MCP Server 的初始化、失败隔离、Tool 发现与关闭。
 *
 * <p>Server 按配置顺序连接；单 Server 失败不会丢弃其他 Server。Manager 只发布
 * 已完成 initialize 且通过过滤的稳定 Tool 快照，配置未信任时绝不创建 Transport。</p>
 *
 * @since 0.10.0
 */
public final class McpClientManager implements AutoCloseable {
    private final List<McpServerConfig> configs;
    private final McpClientFactory factory;
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private boolean started;
    private boolean closed;

    /**
     * 创建尚未启动的多 Server Manager。
     *
     * @param configs 稳定顺序的 Server 配置，最多 32 项且名称唯一
     * @param factory Transport/SDK Client 工厂
     */
    public McpClientManager(List<McpServerConfig> configs, McpClientFactory factory) {
        this.configs = List.copyOf(Objects.requireNonNull(configs, "configs 不能为空"));
        this.factory = Objects.requireNonNull(factory, "factory 不能为空");
        if (this.configs.size() > 32
                || this.configs.stream().map(McpServerConfig::name).distinct().count() != this.configs.size()) {
            throw new IllegalArgumentException("MCP Server 数量或名称重复");
        }
    }

    /**
     * 初始化所有可信 Server，并返回可直接注册到统一 Pipeline 的 Tool。
     *
     * @return 按配置与远端名称稳定排序的 MCP AgentTool 快照
     */
    public synchronized List<AgentTool> start() {
        if (closed || started) {
            throw new IllegalStateException("MCP Manager 已启动或关闭");
        }
        started = true;
        List<AgentTool> tools = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(
                Math.max(1, Math.min(4, configs.size())),
                Thread.ofVirtual().name("cc-java-mcp-connect-", 0).factory())) {
            var futures = configs.stream().map(config -> executor.submit(() -> connect(config))).toList();
            for (int index = 0; index < configs.size(); index++) {
                McpServerConfig config = configs.get(index);
                Connection connection;
                try {
                    connection = futures.get(index).get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    connection = Connection.failed(config);
                } catch (java.util.concurrent.ExecutionException failure) {
                    connection = Connection.failed(config);
                }
                connections.put(config.name(), connection);
                tools.addAll(connection.tools());
            }
        }
        long names = tools.stream().map(tool -> tool.definition().name()).distinct().count();
        if (names != tools.size()) {
            close();
            throw new IllegalStateException("MCP Tool 前缀后仍发生名称冲突");
        }
        return List.copyOf(tools);
    }

    private Connection connect(McpServerConfig config) {
        if (!config.trusted()) {
            return Connection.untrusted(config);
        }
        McpRemoteClient client = null;
        try {
            client = factory.create(config);
            client.initialize();
            List<McpToolDescriptor> descriptors = client.listTools().stream()
                    .filter(tool -> config.includes(tool.name()))
                    .sorted(Comparator.comparing(McpToolDescriptor::name))
                    .toList();
            McpRemoteClient connectedClient = new RecoveringClient(config, factory, client);
            List<AgentTool> serverTools = descriptors.stream()
                    .map(tool -> (AgentTool) new McpAgentTool(
                            config.name(), tool, connectedClient, config.requestTimeout()))
                    .toList();
            return Connection.connected(config, connectedClient, serverTools);
        } catch (RuntimeException failure) {
            closeQuietly(client);
            return Connection.failed(config);
        }
    }

    /**
     * 返回隐私安全的多 Server 状态。
     *
     * @return 不含 endpoint、argv、Secret 或异常正文的状态快照
     */
    public synchronized List<McpServerSnapshot> snapshots() {
        if (!started) {
            return configs.stream().map(config -> new McpServerSnapshot(
                    config.name(), config.trusted() ? McpConnectionStatus.PENDING : McpConnectionStatus.UNTRUSTED, 0))
                    .toList();
        }
        return connections.values().stream().map(Connection::snapshot).toList();
    }

    /**
     * 显式发现各已连接 Server 的 Resource/Prompt 元数据；单 primitive 失败互不影响。
     *
     * @return 已连接 Server 的元数据目录；尚未启动或已关闭时为空
     */
    public synchronized List<McpContextCatalog> contextCatalogs() {
        if (!started || closed) return List.of();
        List<McpContextCatalog> catalogs = new ArrayList<>();
        for (Connection connection : connections.values()) {
            if (connection.status != McpConnectionStatus.CONNECTED || connection.client == null) continue;
            List<McpResourceDescriptor> resources = List.of();
            List<McpPromptDescriptor> prompts = List.of();
            boolean resourcesAvailable = true;
            boolean promptsAvailable = true;
            try { resources = connection.client.listResources(); }
            catch (RuntimeException failure) { resourcesAvailable = false; }
            try { prompts = connection.client.listPrompts(); }
            catch (RuntimeException failure) { promptsAvailable = false; }
            catalogs.add(new McpContextCatalog(connection.config.name(), resources, prompts,
                    resourcesAvailable, promptsAvailable));
        }
        return List.copyOf(catalogs);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        connections.values().forEach(connection -> closeQuietly(connection.client));
        connections.replaceAll((name, connection) -> connection.closed());
    }

    private static void closeQuietly(McpRemoteClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // 关闭诊断不携带外部异常；Server 已从可用快照移除。
            }
        }
    }

    private record Connection(
            McpServerConfig config,
            McpRemoteClient client,
            McpConnectionStatus status,
            List<AgentTool> tools) {
        private static Connection connected(McpServerConfig config, McpRemoteClient client, List<AgentTool> tools) {
            return new Connection(config, client, McpConnectionStatus.CONNECTED, List.copyOf(tools));
        }
        private static Connection untrusted(McpServerConfig config) {
            return new Connection(config, null, McpConnectionStatus.UNTRUSTED, List.of());
        }
        private static Connection failed(McpServerConfig config) {
            return new Connection(config, null, McpConnectionStatus.FAILED, List.of());
        }
        private McpServerSnapshot snapshot() {
            return new McpServerSnapshot(config.name(), status, tools.size());
        }
        private Connection closed() {
            return new Connection(config, null, McpConnectionStatus.CLOSED, List.of());
        }
    }

    /** 首次调用失败时重建完整 initialize 会话并只重试一次。 */
    private static final class RecoveringClient implements McpRemoteClient {
        private final McpServerConfig config;
        private final McpClientFactory factory;
        private McpRemoteClient delegate;
        private boolean closed;

        private RecoveringClient(McpServerConfig config, McpClientFactory factory, McpRemoteClient delegate) {
            this.config = config;
            this.factory = factory;
            this.delegate = delegate;
        }

        @Override public void initialize() { }
        @Override public synchronized List<McpToolDescriptor> listTools() { return delegate.listTools(); }
        @Override public synchronized List<McpResourceDescriptor> listResources() { return delegate.listResources(); }
        @Override public synchronized List<McpPromptDescriptor> listPrompts() { return delegate.listPrompts(); }

        @Override
        public synchronized McpCallOutcome callTool(String name, Map<String, Object> arguments) {
            if (closed) throw new IllegalStateException("MCP connection 已关闭");
            try {
                return delegate.callTool(name, arguments);
            } catch (RuntimeException firstFailure) {
                closeQuietly(delegate);
                delegate = factory.create(config);
                delegate.initialize();
                return delegate.callTool(name, arguments);
            }
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                closeQuietly(delegate);
            }
        }
    }
}
