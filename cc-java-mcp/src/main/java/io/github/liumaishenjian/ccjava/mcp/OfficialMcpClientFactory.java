package io.github.liumaishenjian.ccjava.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 使用官方 MCP Java SDK 2.0 构造 STDIO 或 Streamable HTTP Client。
 *
 * <p>该类型只位于适配器边缘。STDIO 进程仅继承配置点名的环境变量；HTTP 不跟随
 * 重定向，Bearer 值在构造请求时从环境读取且不会进入异常或 {@code toString()}。</p>
 *
 * @since 0.10.0
 */
public final class OfficialMcpClientFactory implements McpClientFactory {

    private final Map<String, String> environment;
    private final McpJsonMapper mapper;

    /** 使用当前进程环境和官方 Jackson 3 Mapper。 */
    public OfficialMcpClientFactory() {
        this(System.getenv(), new JacksonMcpJsonMapperSupplier().get());
    }

    OfficialMcpClientFactory(Map<String, String> environment, McpJsonMapper mapper) {
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment 不能为空"));
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
    }

    @Override
    public McpRemoteClient create(McpServerConfig config) {
        McpServerConfig checked = Objects.requireNonNull(config, "config 不能为空");
        if (!checked.trusted()) {
            throw new IllegalStateException("MCP Server 未通过信任 Gate");
        }
        McpClientTransport transport = switch (checked.transport()) {
            case McpTransportConfig.Stdio stdio -> stdio(stdio);
            case McpTransportConfig.StreamableHttp http -> http(http, checked.requestTimeout());
        };
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("cc-java", "0.1.0"))
                .requestTimeout(checked.requestTimeout())
                .initializationTimeout(checked.requestTimeout())
                .build();
        return new SdkRemoteClient(client, mapper);
    }

    private McpClientTransport stdio(McpTransportConfig.Stdio config) {
        Map<String, String> inherited = new LinkedHashMap<>();
        for (String name : config.inheritedEnvironmentNames()) {
            String value = environment.get(name);
            if (value != null) {
                inherited.put(name, value);
            }
        }
        ServerParameters parameters = ServerParameters.builder(config.executable().toString())
                .args(config.arguments())
                .env(inherited)
                .build();
        return new MinimalEnvironmentStdioTransport(parameters, mapper);
    }

    /**
     * 官方 SDK 默认在父进程完整环境之上追加配置环境；本适配器在进程创建前先清空环境，
     * 从而兑现 {@link McpTransportConfig.Stdio} 的显式 allowlist 契约。
     */
    private static final class MinimalEnvironmentStdioTransport extends StdioClientTransport {
        private MinimalEnvironmentStdioTransport(ServerParameters parameters, McpJsonMapper mapper) {
            super(parameters, mapper);
        }

        @Override
        protected ProcessBuilder getProcessBuilder() {
            ProcessBuilder builder = super.getProcessBuilder();
            builder.environment().clear();
            return builder;
        }
    }

    private McpClientTransport http(McpTransportConfig.StreamableHttp config, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder();
        config.bearerTokenEnvironment().ifPresent(name -> {
            String secret = environment.get(name);
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException("MCP HTTP 认证环境变量缺失");
            }
            request.header("Authorization", "Bearer " + secret);
        });
        return HttpClientStreamableHttpTransport.builder(config.endpoint().toString())
                .clientBuilder(HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER))
                .requestBuilder(request)
                .jsonMapper(mapper)
                .connectTimeout(timeout)
                .build();
    }

    private static final class SdkRemoteClient implements McpRemoteClient {
        private static final int MAX_PAGES = 100;
        private static final int MAX_TOOLS = 2_000;
        private static final int MAX_RESULT_CHARACTERS = 32_768;

        private final McpSyncClient client;
        private final McpJsonMapper mapper;

        private SdkRemoteClient(McpSyncClient client, McpJsonMapper mapper) {
            this.client = client;
            this.mapper = mapper;
        }

        @Override
        public void initialize() {
            client.initialize();
        }

        @Override
        public List<McpToolDescriptor> listTools() {
            List<McpToolDescriptor> result = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
                for (McpSchema.Tool tool : page.tools()) {
                    if (result.size() >= MAX_TOOLS) {
                        throw new IllegalStateException("MCP Tool 数量超过上限");
                    }
                    result.add(new McpToolDescriptor(tool.name(), tool.description(), tool.inputSchema()));
                }
                cursor = page.nextCursor();
                pages++;
            } while (cursor != null && !cursor.isBlank() && pages < MAX_PAGES);
            if (cursor != null && !cursor.isBlank()) {
                throw new IllegalStateException("MCP Tool 分页超过上限");
            }
            return List.copyOf(result);
        }

        @Override
        public McpCallOutcome callTool(String name, Map<String, Object> arguments) {
            try {
                return projectCallResult(client.callTool(new McpSchema.CallToolRequest(name, arguments)));
            } catch (RuntimeException failure) {
                throw mapCallFailure(failure);
            }
        }

        private McpCallOutcome projectCallResult(McpSchema.CallToolResult result) {
            StringBuilder output = new StringBuilder();
            for (McpSchema.Content content : result.content()) {
                appendBounded(output, project(content));
            }
            if (result.structuredContent() != null) {
                try {
                    appendBounded(output, mapper.writeValueAsString(result.structuredContent()));
                } catch (java.io.IOException ignored) {
                    appendBounded(output, "[structured MCP content unavailable]");
                }
            }
            return new McpCallOutcome(Boolean.TRUE.equals(result.isError()), output.toString());
        }

        private static RuntimeException mapCallFailure(Throwable failure) {
            Throwable current = failure;
            for (int depth = 0; current != null && depth < 16; depth++) {
                if (current instanceof McpTransportSessionNotFoundException sessionInvalid) {
                    return new McpSessionInvalidException(sessionInvalid);
                }
                Throwable cause = current.getCause();
                if (cause == current) {
                    break;
                }
                current = cause;
            }
            if (failure instanceof RuntimeException runtime) {
                return runtime;
            }
            return new IllegalStateException("MCP Tool 调用失败", failure);
        }

        @Override
        public List<McpResourceDescriptor> listResources() {
            List<McpResourceDescriptor> resources = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                McpSchema.ListResourcesResult page = cursor == null
                        ? client.listResources() : client.listResources(cursor);
                for (McpSchema.Resource resource : page.resources()) {
                    if (resources.size() >= MAX_TOOLS) throw new IllegalStateException("MCP Resource 数量超过上限");
                    resources.add(new McpResourceDescriptor(
                            resource.uri(), resource.name(), resource.description(), resource.mimeType()));
                }
                cursor = page.nextCursor();
                pages++;
            } while (cursor != null && !cursor.isBlank() && pages < MAX_PAGES);
            if (cursor != null && !cursor.isBlank()) throw new IllegalStateException("MCP Resource 分页超过上限");
            return List.copyOf(resources);
        }

        @Override
        public List<McpPromptDescriptor> listPrompts() {
            List<McpPromptDescriptor> prompts = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                McpSchema.ListPromptsResult page = cursor == null
                        ? client.listPrompts() : client.listPrompts(cursor);
                for (McpSchema.Prompt prompt : page.prompts()) {
                    if (prompts.size() >= MAX_TOOLS) throw new IllegalStateException("MCP Prompt 数量超过上限");
                    prompts.add(new McpPromptDescriptor(
                            prompt.name(), prompt.description(), prompt.arguments() == null ? List.of()
                                    : prompt.arguments().stream().map(McpSchema.PromptArgument::name).toList()));
                }
                cursor = page.nextCursor();
                pages++;
            } while (cursor != null && !cursor.isBlank() && pages < MAX_PAGES);
            if (cursor != null && !cursor.isBlank()) throw new IllegalStateException("MCP Prompt 分页超过上限");
            return List.copyOf(prompts);
        }

        private static String project(McpSchema.Content content) {
            return switch (content) {
                case McpSchema.TextContent text -> text.text();
                case McpSchema.ResourceLink link -> "[resource " + link.uri() + "]";
                case McpSchema.EmbeddedResource embedded -> "[embedded resource "
                        + embedded.resource().uri() + "]";
                case McpSchema.ImageContent image -> "[image " + image.mimeType() + "]";
                case McpSchema.AudioContent audio -> "[audio " + audio.mimeType() + "]";
                default -> "[unsupported MCP content]";
            };
        }

        private static void appendBounded(StringBuilder target, String value) {
            if (target.length() >= MAX_RESULT_CHARACTERS || value == null) {
                return;
            }
            if (!target.isEmpty()) {
                target.append('\n');
            }
            int accepted = Math.min(value.length(), MAX_RESULT_CHARACTERS - target.length());
            target.append(value, 0, accepted);
        }

        @Override
        public void close() {
            client.closeGracefully();
        }
    }
}
