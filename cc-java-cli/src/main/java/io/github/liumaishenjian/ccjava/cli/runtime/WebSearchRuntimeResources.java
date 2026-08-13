package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessDecision;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessReason;
import io.github.liumaishenjian.ccjava.core.network.NetworkPurpose;
import io.github.liumaishenjian.ccjava.tools.web.HostedMcpWebSearchClient;
import io.github.liumaishenjian.ccjava.tools.web.WebSearchConfiguration;
import io.github.liumaishenjian.ccjava.tools.web.WebSearchTool;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Headless production 对固定 hosted MCP Web Search 配置、Client 与 AgentTool 的关闭所有权。 */
final class WebSearchRuntimeResources implements AutoCloseable {
    private final Optional<AgentTool> tool;
    private final HostedMcpWebSearchClient client;

    private WebSearchRuntimeResources(Optional<AgentTool> tool, HostedMcpWebSearchClient client) {
        this.tool = tool;
        this.client = client;
    }

    static WebSearchRuntimeResources disabled() {
        return new WebSearchRuntimeResources(Optional.empty(), null);
    }

    static WebSearchRuntimeResources production() {
        String configuredRoot = System.getenv("CC_JAVA_REPOSITORY_ROOT");
        Path repositoryRoot = configuredRoot == null || configuredRoot.isBlank()
                ? Path.of("").toAbsolutePath().normalize()
                : Path.of(configuredRoot).toAbsolutePath().normalize();
        return fromConfiguration(WebSearchSettingsLoader.load(repositoryRoot));
    }

    /** 包级 composition seam：使用已从可信外部来源解析的配置装配真实 JDK HTTP Tool。 */
    static WebSearchRuntimeResources fromConfiguration(WebSearchConfiguration configuration) {
        if (!configuration.enabled()) return disabled();
        URI endpoint = configuration.endpoint().orElseThrow();
        int endpointPort = endpoint.getPort() == -1
                ? ("https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80) : endpoint.getPort();
        HostedMcpWebSearchClient client = new HostedMcpWebSearchClient(configuration, (request, cancellation) -> {
            if (cancellation.isCancellationRequested()) {
                return NetworkAccessDecision.deny(NetworkAccessReason.CANCELLED);
            }
            boolean targetMatches = request.purpose() == NetworkPurpose.WEB_SEARCH
                    && request.scheme().equals(endpoint.getScheme().toLowerCase(Locale.ROOT))
                    && request.host().equals(endpoint.getHost().toLowerCase(Locale.ROOT))
                    && request.port() == endpointPort
                    && !request.redirectsAllowed();
            return targetMatches ? NetworkAccessDecision.allow()
                    : NetworkAccessDecision.deny(NetworkAccessReason.INVALID_TARGET);
        });
        return new WebSearchRuntimeResources(Optional.of(new WebSearchTool(client)), client);
    }

    Optional<AgentTool> tool() { return tool; }

    @Override
    public void close() {
        if (client != null) client.close();
    }
}
