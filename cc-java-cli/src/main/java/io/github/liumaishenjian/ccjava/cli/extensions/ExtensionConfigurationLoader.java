package io.github.liumaishenjian.ccjava.cli.extensions;

import io.github.liumaishenjian.ccjava.cli.hooks.CommandHookHandler;
import io.github.liumaishenjian.ccjava.cli.hooks.HttpHookHandler;
import io.github.liumaishenjian.ccjava.core.hook.HookBinding;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.core.hook.HookHandler;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookFailurePolicy;
import io.github.liumaishenjian.ccjava.domain.hook.HookMatcher;
import io.github.liumaishenjian.ccjava.mcp.McpClientManager;
import io.github.liumaishenjian.ccjava.mcp.McpServerConfig;
import io.github.liumaishenjian.ccjava.mcp.McpTransportConfig;
import io.github.liumaishenjian.ccjava.mcp.OfficialMcpClientFactory;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 从固定 user/project 文件加载 S09/S10 扩展配置。
 *
 * <p>User 文件固定为 {@code ~/.cc-java/extensions.json}；Project 文件固定为
 * {@code .cc-java/extensions.json}。项目文件只有当用户私有 trust store 中记录了
 * workspace 与文件内容的精确 SHA-256 时才可启动任何 Handler/Server。解析采用未知字段
 *拒绝、重复键拒绝、64 KiB 与 no-follow 边界；失败整体降级为禁用扩展。</p>
 *
 * @since 0.10.0
 */
public final class ExtensionConfigurationLoader {
    /** Workspace 内固定 project 扩展配置路径。 */
    public static final String PROJECT_PATH = ".cc-java/extensions.json";
    /** 单个扩展或 Trust JSON 文件的最大字节数。 */
    public static final int MAX_BYTES = 64 * 1_024;
    private static final Set<String> ROOT_FIELDS = Set.of("version", "hooks", "mcpServers");
    private static final Set<String> HOOK_FIELDS = Set.of(
            "id", "event", "subjectGlob", "failurePolicy", "timeoutMs", "command", "url");
    private static final Set<String> MCP_FIELDS = Set.of(
            "name", "transport", "command", "args", "env", "endpoint", "bearerTokenEnv",
            "allowTools", "denyTools", "timeoutMs");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final Path userRoot;
    private final WorkspaceGuard workspace;

    /**
     * 创建固定来源的扩展配置加载器。
     *
     * @param userHome 用户根目录，用于解析私有 {@code .cc-java}
     * @param workspace 已验证的当前 Workspace
     */
    public ExtensionConfigurationLoader(Path userHome, WorkspaceGuard workspace) {
        this.userRoot = Objects.requireNonNull(userHome, "userHome 不能为空")
                .toAbsolutePath().normalize().resolve(".cc-java");
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
    }

    /**
     * 加载配置、建立可信资源并连接 MCP；任何配置错误安全降级。
     *
     * @return 由调用 Session 唯一关闭的扩展资源快照
     */
    public ExtensionRuntime load() {
        var hookDefinitions = new LinkedHashMap<String, HookDefinition>();
        var serverDefinitions = new LinkedHashMap<String, ServerDefinition>();
        boolean userLoaded = false;
        boolean projectPresent = false;
        boolean projectTrusted = false;
        Optional<String> projectFingerprint = Optional.empty();
        Optional<String> diagnostic = Optional.empty();
        String failureCode = "EXTENSION_USER_READ_INVALID";
        try {
            Optional<byte[]> user = readUser(userRoot.resolve("extensions.json"));
            if (user.isPresent()) {
                failureCode = "EXTENSION_USER_PARSE_INVALID";
                Parsed parsed = parse(user.orElseThrow(), true);
                merge(hookDefinitions, serverDefinitions, parsed);
                userLoaded = true;
            }
            failureCode = "EXTENSION_PROJECT_INVALID";
            Optional<byte[]> project = readProject();
            if (project.isPresent()) {
                projectPresent = true;
                String fingerprint = digest(project.orElseThrow());
                projectFingerprint = Optional.of(fingerprint);
                projectTrusted = trustMatches(fingerprint);
                Parsed parsed = parse(project.orElseThrow(), projectTrusted);
                if (projectTrusted) {
                    merge(hookDefinitions, serverDefinitions, parsed);
                }
                if (!projectTrusted) {
                    diagnostic = Optional.of("PROJECT_TRUST_REQUIRED");
                }
            }
        } catch (RuntimeException | IOException | WorkspaceAccessException failure) {
            return new ExtensionRuntime(HookCoordinator.disabled(), null, null, List.of(),
                    new ExtensionStatus(userLoaded, projectPresent, false, 0, 0,
                            projectFingerprint, Optional.of(failureCode)));
        }

        List<HookBinding> bindings = new ArrayList<>();
        int order = 0;
        for (HookDefinition definition : hookDefinitions.values()) {
            if (definition.trusted) {
                bindings.add(definition.binding(workspace.workspace(), order++));
            }
        }
        var executor = bindings.isEmpty() ? null : Executors.newFixedThreadPool(Math.min(4, bindings.size()),
                Thread.ofVirtual().name("cc-java-hook-", 0).factory());
        McpClientManager manager = null;
        try {
            HookCoordinator hooks = bindings.isEmpty() ? HookCoordinator.disabled()
                    : new HookCoordinator(bindings, executor, Duration.ofSeconds(30));
            List<McpServerConfig> servers = serverDefinitions.values().stream()
                    .map(ServerDefinition::config).toList();
            manager = servers.isEmpty() ? null
                    : new McpClientManager(servers, new OfficialMcpClientFactory());
            List<io.github.liumaishenjian.ccjava.core.AgentTool> tools = manager == null
                    ? List.of() : manager.start();
            return new ExtensionRuntime(hooks, executor, manager, tools,
                    new ExtensionStatus(userLoaded, projectPresent, projectTrusted, bindings.size(), servers.size(),
                            projectFingerprint, diagnostic));
        } catch (RuntimeException failure) {
            if (manager != null) manager.close();
            if (executor != null) executor.shutdownNow();
            return new ExtensionRuntime(HookCoordinator.disabled(), null, null, List.of(),
                    new ExtensionStatus(userLoaded, projectPresent, projectTrusted, 0, 0,
                            projectFingerprint, Optional.of("EXTENSION_START_FAILED")));
        }
    }

    /**
     * 计算当前 Project 配置需要写入 trust store 的隐私安全指纹。
     *
     * @return 配置存在且安全可读时的 SHA-256
     */
    public Optional<String> projectFingerprint() {
        try {
            return readProject().map(ExtensionConfigurationLoader::digest);
        } catch (IOException | WorkspaceAccessException failure) {
            return Optional.empty();
        }
    }

    /**
     * 将当前 Project 扩展文件的精确摘要写入用户私有 Trust Store。
     *
     * <p>调用者必须由显式 CLI 动作触发；本方法不在普通启动或配置刷新时自动批准。</p>
     *
     * @return 固定成功状态、可选指纹与诊断码
     */
    public TrustResult approveProject() {
        try {
            Optional<byte[]> project = readProject();
            if (project.isEmpty()) return new TrustResult(false, Optional.empty(), "PROJECT_CONFIG_MISSING");
            String fingerprint = digest(project.orElseThrow());
            if (Files.exists(userRoot, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(userRoot) || !Files.isDirectory(userRoot, LinkOption.NOFOLLOW_LINKS)
                        || !userRoot.toRealPath().equals(userRoot.toAbsolutePath().normalize())) {
                    return new TrustResult(false, Optional.of(fingerprint), "TRUST_ROOT_UNSAFE");
                }
            } else {
                Path parent = userRoot.getParent();
                if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                    return new TrustResult(false, Optional.of(fingerprint), "TRUST_ROOT_UNAVAILABLE");
                }
                Files.createDirectory(userRoot);
            }
            Path target = userRoot.resolve("extension-trust.json");
            Map<String, String> workspaces = new LinkedHashMap<>();
            Optional<byte[]> existing = readUser(target);
            if (existing.isPresent()) {
                JsonNode root = JSON.readTree(existing.orElseThrow());
                if (!root.isObject() || root.path("version").asInt(-1) != 1
                        || !root.path("workspaces").isObject()) {
                    return new TrustResult(false, Optional.of(fingerprint), "TRUST_STORE_INVALID");
                }
                root.path("workspaces").properties().forEach(entry -> {
                    if (!entry.getValue().isTextual()) throw new IllegalArgumentException("trust value invalid");
                    workspaces.put(entry.getKey(), entry.getValue().asText());
                });
            }
            String workspaceId = digest(workspace.workspace().toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            workspaces.put(workspaceId, fingerprint);
            byte[] output = JSON.writeValueAsBytes(Map.of("version", 1, "workspaces", workspaces));
            Path temporary = Files.createTempFile(userRoot, "extension-trust-", ".tmp");
            boolean moved = false;
            try {
                Files.write(temporary, output, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } finally {
                if (!moved) Files.deleteIfExists(temporary);
            }
            return new TrustResult(true, Optional.of(fingerprint), "TRUST_RECORDED");
        } catch (RuntimeException | IOException | WorkspaceAccessException failure) {
            return new TrustResult(false, projectFingerprint(), "TRUST_WRITE_FAILED");
        }
    }

    /**
     * Project Trust 命令的隐私安全终态。
     *
     * @param successful Trust Store 是否已原子更新
     * @param fingerprint 当前 project 配置摘要
     * @param code 固定结果码
     */
    public record TrustResult(boolean successful, Optional<String> fingerprint, String code) {
        /** 校验结果字段。 */
        public TrustResult {
            fingerprint = Objects.requireNonNull(fingerprint, "fingerprint 不能为空");
            code = Objects.requireNonNull(code, "code 不能为空");
        }
    }

    private Optional<byte[]> readUser(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (Files.isSymbolicLink(userRoot) || Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) > MAX_BYTES
                || !target.toRealPath().startsWith(userRoot.toRealPath())) {
            throw new IOException("unsafe user extension file");
        }
        byte[] bytes = Files.readAllBytes(target);
        if (bytes.length > MAX_BYTES) throw new IOException("extension byte limit");
        return Optional.of(bytes);
    }

    private Optional<byte[]> readProject() throws IOException, WorkspaceAccessException {
        Path logical = workspace.workspace().resolve(PROJECT_PATH);
        if (!Files.exists(logical, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        Path real = workspace.requireRegularFile(PROJECT_PATH).realPath();
        if (Files.size(real) > MAX_BYTES) throw new IOException("extension byte limit");
        byte[] bytes = Files.readAllBytes(real);
        if (bytes.length > MAX_BYTES) throw new IOException("extension byte limit");
        return Optional.of(bytes);
    }

    private boolean trustMatches(String fingerprint) throws IOException {
        Optional<byte[]> trust = readUser(userRoot.resolve("extension-trust.json"));
        if (trust.isEmpty()) return false;
        JsonNode root = JSON.readTree(trust.orElseThrow());
        if (!root.isObject() || root.path("version").asInt(-1) != 1) return false;
        String workspaceId = digest(workspace.workspace().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return fingerprint.equals(root.path("workspaces").path(workspaceId).asText());
    }

    private static Parsed parse(byte[] bytes, boolean trusted) throws IOException {
        JsonNode root = JSON.readTree(bytes);
        requireObject(root, ROOT_FIELDS);
        if (root.path("version").asInt(-1) != 1) throw new IllegalArgumentException("unsupported version");
        List<HookDefinition> hooks = new ArrayList<>();
        JsonNode hookArray = root.get("hooks");
        if (hookArray != null) {
            if (!hookArray.isArray() || hookArray.size() > 128) throw new IllegalArgumentException("hooks invalid");
            for (JsonNode node : hookArray) hooks.add(parseHook(node, trusted));
        }
        List<ServerDefinition> servers = new ArrayList<>();
        JsonNode serverArray = root.get("mcpServers");
        if (serverArray != null) {
            if (!serverArray.isArray() || serverArray.size() > 32) throw new IllegalArgumentException("servers invalid");
            for (JsonNode node : serverArray) servers.add(parseServer(node, trusted));
        }
        return new Parsed(hooks, servers);
    }

    private static HookDefinition parseHook(JsonNode node, boolean trusted) {
        requireObject(node, HOOK_FIELDS);
        String id = text(node, "id");
        HookEventKind event = HookEventKind.valueOf(text(node, "event"));
        Optional<String> subject = optionalText(node, "subjectGlob");
        HookFailurePolicy policy = HookFailurePolicy.valueOf(text(node, "failurePolicy"));
        Duration timeout = Duration.ofMillis(integer(node, "timeoutMs", 1, 30_000));
        boolean command = node.has("command");
        boolean url = node.has("url");
        if (command == url) throw new IllegalArgumentException("hook handler invalid");
        List<String> argv = command ? strings(node.get("command"), 64) : List.of();
        Optional<URI> endpoint = url ? Optional.of(URI.create(text(node, "url"))) : Optional.empty();
        return new HookDefinition(id, event, subject, policy, timeout, argv, endpoint, trusted);
    }

    private static ServerDefinition parseServer(JsonNode node, boolean trusted) {
        requireObject(node, MCP_FIELDS);
        String name = text(node, "name");
        String transport = text(node, "transport");
        Duration timeout = Duration.ofMillis(integer(node, "timeoutMs", 1, 120_000));
        List<String> allow = node.has("allowTools") ? strings(node.get("allowTools"), 256) : List.of();
        List<String> deny = node.has("denyTools") ? strings(node.get("denyTools"), 256) : List.of();
        McpTransportConfig transportConfig;
        if (transport.equals("stdio")) {
            transportConfig = new McpTransportConfig.Stdio(
                    Path.of(text(node, "command")),
                    node.has("args") ? strings(node.get("args"), 64) : List.of(),
                    node.has("env") ? strings(node.get("env"), 32) : List.of());
        } else if (transport.equals("streamable-http")) {
            transportConfig = new McpTransportConfig.StreamableHttp(
                    URI.create(text(node, "endpoint")), optionalText(node, "bearerTokenEnv"));
        } else {
            throw new IllegalArgumentException("transport invalid");
        }
        return new ServerDefinition(new McpServerConfig(
                name, transportConfig, allow, deny, timeout, trusted));
    }

    private static void merge(Map<String, HookDefinition> hooks, Map<String, ServerDefinition> servers, Parsed parsed) {
        parsed.hooks.forEach(value -> hooks.put(value.id, value));
        parsed.servers.forEach(value -> servers.put(value.config.name(), value));
    }

    private static void requireObject(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("object required");
        if (node.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey()))) {
            throw new IllegalArgumentException("unknown field");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " invalid");
        }
        return value.asText();
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        return node.has(field) ? Optional.of(text(node, field)) : Optional.empty();
    }

    private static long integer(JsonNode node, String field, long minimum, long maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) throw new IllegalArgumentException(field + " invalid");
        long number = value.asLong();
        if (number < minimum || number > maximum) throw new IllegalArgumentException(field + " invalid");
        return number;
    }

    private static List<String> strings(JsonNode node, int maximum) {
        if (node == null || !node.isArray() || node.size() > maximum) throw new IllegalArgumentException("array invalid");
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("array invalid");
            values.add(value.asText());
        });
        return List.copyOf(values);
    }

    private static String digest(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private record Parsed(List<HookDefinition> hooks, List<ServerDefinition> servers) { }

    private record HookDefinition(String id, HookEventKind event, Optional<String> subject,
                                  HookFailurePolicy policy, Duration timeout, List<String> command,
                                  Optional<URI> endpoint, boolean trusted) {
        private HookBinding binding(Path workspace, int order) {
            HookHandler handler = endpoint.<HookHandler>map(uri -> new HttpHookHandler(id, uri, timeout))
                    .orElseGet(() -> new CommandHookHandler(
                            id, command, workspace, timeout, CommandHookHandler.DEFAULT_MAX_OUTPUT_BYTES));
            HookMatcher matcher = subject.map(value -> HookMatcher.subject(event, value))
                    .orElseGet(() -> HookMatcher.event(event));
            return new HookBinding(id, matcher, handler, policy, trusted, order);
        }
    }

    private record ServerDefinition(McpServerConfig config) { }
}
