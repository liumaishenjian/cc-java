package io.github.liumaishenjian.ccjava.cli.plugins;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.plugin.InMemoryPluginRegistry;
import io.github.liumaishenjian.ccjava.core.plugin.PluginLease;
import io.github.liumaishenjian.ccjava.core.plugin.PluginRunCoordinator;
import io.github.liumaishenjian.ccjava.core.plugin.PluginToolContribution;
import io.github.liumaishenjian.ccjava.core.plugin.PluginToolProviderDescriptor;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentKind;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginFingerprint;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import io.github.liumaishenjian.ccjava.mcp.McpBackedPluginToolProviderFactory;
import io.github.liumaishenjian.ccjava.mcp.McpClientFactory;
import io.github.liumaishenjian.ccjava.mcp.McpServerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 普通 Headless Session 的 directory-only 受信 Plugin composition snapshot。
 *
 * <p>宿主只读取固定 {@code ~/.cc-java/plugins/registry.v1} 与 content-addressed 子目录；每行必须
 * 精确匹配 Plugin ID/version/tree digest，且 fingerprint 还必须列在用户私有
 * {@code plugin-trust.v1}。任何链接、重复、未知目录、配置 digest 或 Provider 创建失败都会让整个
 * Plugin composition 安全降级为零贡献。manifest 只能选择宿主内置 {@code mcp-backed} factory，
 * 不加载 JAR/Class/native/script。Contribution 由 Session 唯一逆序关闭。</p>
 *
 * @since 0.11.0
 */
public final class PluginRuntimeResources implements AutoCloseable {
    private static final int MAX_INDEX_BYTES = 64 * 1_024;
    private static final PluginRuntimeResources DISABLED = new PluginRuntimeResources();

    private final InMemoryPluginRegistry registry;
    private final List<PluginToolContribution> contributions;
    private final List<AgentTool> tools;
    private final PluginRunHookTemplates hookTemplates;
    private final io.github.liumaishenjian.ccjava.cli.skills.PluginSkillSet skills;

    /** 从用户私有固定 store 安全装载受信 snapshots 和 MCP-backed contributions。 */
    public static PluginRuntimeResources load(Path storeRoot, List<McpServerConfig> servers,
            McpClientFactory clientFactory) {
        Objects.requireNonNull(storeRoot, "storeRoot 不能为空");
        Objects.requireNonNull(servers, "servers 不能为空");
        Objects.requireNonNull(clientFactory, "clientFactory 不能为空");
        try {
            return loadChecked(storeRoot.toAbsolutePath().normalize(), servers, clientFactory);
        } catch (RuntimeException | IOException failure) {
            return disabled();
        }
    }

    /** @return 不含 Plugin 的共享安全退化实现 */
    public static PluginRuntimeResources disabled() {
        return DISABLED;
    }

    private static PluginRuntimeResources loadChecked(Path root, List<McpServerConfig> servers,
            McpClientFactory clientFactory) throws IOException {
        if (!safeDirectory(root)) return disabled();
        Path index = root.resolve("registry.v1");
        Path trust = root.resolve("plugin-trust.v1");
        if (!safeFile(root, index) || !safeFile(root, trust)
                || Files.size(index) > MAX_INDEX_BYTES || Files.size(trust) > MAX_INDEX_BYTES) return disabled();
        Set<String> trusted = parseTrust(Files.readAllLines(trust, StandardCharsets.UTF_8));
        List<PluginSnapshot> snapshots = new ArrayList<>();
        var loader = new PluginPackageLoader();
        for (PluginRegistryIndex.Entry entry : PluginRegistryIndex.read(index)) {
            Path directory = matchingDirectory(root, entry);
            PluginSnapshot snapshot = loader.load(directory);
            if (!snapshot.manifest().id().value().equals(entry.id())
                    || !snapshot.manifest().version().equals(entry.version())
                    || !snapshot.fingerprint().treeDigest().equals(entry.treeDigest())
                    || !trusted.contains(trustKey(snapshot.fingerprint()))) {
                throw new IllegalArgumentException("plugin identity mismatch");
            }
            snapshots.add(snapshot);
        }
        snapshots.sort(Comparator.comparing(value -> value.manifest().id().value()));
        Set<String> trustKeys = Set.copyOf(trusted);
        var registry = new InMemoryPluginRegistry(fingerprint -> trustKeys.contains(trustKey(fingerprint)));
        snapshots.forEach(registry::activate);
        var factory = new McpBackedPluginToolProviderFactory(servers, clientFactory);
        List<PluginToolContribution> contributions = new ArrayList<>();
        try {
            for (PluginSnapshot snapshot : snapshots) {
                for (var component : snapshot.manifest().components().stream()
                        .filter(value -> value.kind() == PluginComponentKind.TOOL_PROVIDER).toList()) {
                    PluginLease lease = registry.acquire(snapshot.manifest().id()).orElseThrow();
                    contributions.add(factory.create(new PluginToolProviderDescriptor(snapshot, component), lease));
                }
            }
            return new PluginRuntimeResources(registry, contributions, new PluginRunHookTemplates(root, snapshots),
                    new io.github.liumaishenjian.ccjava.cli.skills.PluginSkillScanner().scan(root, snapshots));
        } catch (Exception failure) {
            closeReverse(contributions);
            throw new IllegalArgumentException("plugin contribution rejected");
        }
    }

    private PluginRuntimeResources(InMemoryPluginRegistry registry, List<PluginToolContribution> contributions,
            PluginRunHookTemplates hookTemplates,
            io.github.liumaishenjian.ccjava.cli.skills.PluginSkillSet skills) {
        this.registry = registry;
        this.contributions = List.copyOf(contributions);
        this.tools = this.contributions.stream().flatMap(value -> value.tools().stream()).toList();
        this.hookTemplates = Objects.requireNonNull(hookTemplates, "hookTemplates 不能为空");
        this.skills = Objects.requireNonNull(skills, "skills 不能为空");
    }

    private PluginRuntimeResources() {
        registry = null;
        contributions = List.of();
        tools = List.of();
        hookTemplates = null;
        skills = io.github.liumaishenjian.ccjava.cli.skills.PluginSkillSet.empty();
    }

    /** @return 必须与 builtin/MCP Tool 一起注册进唯一 Pipeline 的 Plugin Tools */
    public List<AgentTool> tools() { return tools; }

    /** @return 与当前 immutable Plugin snapshots 绑定的 Skill metadata/content 集合 */
    public io.github.liumaishenjian.ccjava.cli.skills.PluginSkillSet skills() { return skills; }

    /** @return 当前 Session contribution 持有的 snapshot lease 数，仅供安全 E2E/诊断 */
    public int contributionLeaseCount() {
        if (registry == null) return 0;
        return registry.activeSnapshot().snapshots().stream()
                .mapToInt(snapshot -> registry.leaseCount(snapshot.manifest().id())).sum();
    }

    /** @return 当前 Session immutable snapshots 的 Run lease 协调器 */
    public PluginRunCoordinator runCoordinator() {
        return registry == null ? PluginRunCoordinator.disabled() : new PluginRunCoordinator(registry);
    }

    /** @return activation 成功后仅按 Skill 引用绑定当前可信模板的端口 */
    public io.github.liumaishenjian.ccjava.core.skill.SkillHookBinder skillHookBinder(
            io.github.liumaishenjian.ccjava.core.hook.HookCoordinator coordinator) {
        Objects.requireNonNull(coordinator, "coordinator 不能为空");
        return hookTemplates == null
                ? io.github.liumaishenjian.ccjava.core.skill.SkillHookBinder.none()
                : hookTemplates.skillBinder(coordinator);
    }

    /** @return Plugin generation Run lease 端口；HOOKS components 本身不会在 Run start 自动启用 */
    public io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks runHooks() {
        return hookTemplates == null
                ? io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks.none() : hookTemplates;
    }

    @Override
    public void close() {
        closeReverse(contributions);
    }

    private static Path matchingDirectory(Path root, PluginRegistryIndex.Entry entry) {
        Path expected = root.resolve(PluginRegistryIndex.directoryName(
                entry.id(), entry.treeDigest())).normalize();
        if (!expected.startsWith(root) || !safeDirectory(expected)) {
            throw new IllegalArgumentException("plugin directory mismatch");
        }
        return expected;
    }

    private static Set<String> parseTrust(List<String> lines) {
        if (lines.size() > 256) throw new IllegalArgumentException("trust limit");
        Set<String> values = new HashSet<>();
        for (String line : lines) {
            if (!line.matches("[a-z0-9]+(?:-[a-z0-9]+)*\\t[^\\t\\r\\n]{1,64}\\t[0-9a-f]{64}\\t[0-9a-f]{64}")
                    || !values.add(line)) throw new IllegalArgumentException("trust invalid");
        }
        return Set.copyOf(values);
    }

    private static String trustKey(PluginFingerprint value) {
        return value.pluginId().value() + '\t' + value.version() + '\t'
                + value.treeDigest() + '\t' + value.manifestDigest();
    }

    private static boolean safeDirectory(Path path) {
        try {
            return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                    && path.toRealPath().equals(path.toAbsolutePath().normalize());
        } catch (IOException failure) {
            return false;
        }
    }

    private static boolean safeFile(Path root, Path path) {
        try {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                    && path.toRealPath().startsWith(root.toRealPath());
        } catch (IOException failure) {
            return false;
        }
    }

    private static void closeReverse(List<? extends AutoCloseable> values) {
        for (int index = values.size() - 1; index >= 0; index--) {
            try { values.get(index).close(); }
            catch (Exception ignored) { /* 尽力清理全部资源，不泄漏底层异常。 */ }
        }
    }
}
