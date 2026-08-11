package io.github.liumaishenjian.ccjava.cli.plugins;

import io.github.liumaishenjian.ccjava.cli.hooks.HttpHookHandler;
import io.github.liumaishenjian.ccjava.core.hook.HookBinding;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks;
import io.github.liumaishenjian.ccjava.core.skill.SkillHookBinder;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookFailurePolicy;
import io.github.liumaishenjian.ccjava.domain.hook.HookMatcher;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import io.github.liumaishenjian.ccjava.domain.skill.SkillSource;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 从受信 Plugin snapshot 内严格 Hook template 构造仅当前 Run 可见的 S09 bindings。
 *
 * <p>S11 Plugin Hook 只允许 loopback HTTP，不允许 Plugin 携带 command/script。文件在 Session
 * composition 时从 immutable content directory 读取并与 manifest fingerprint 绑定；Run 端口只按已捕获
 * fingerprint 选择模板，不重新访问磁盘，也不在 Resume/Fork 自动注册。</p>
 *
 * @since 0.11.0
 */
public final class PluginRunHookTemplates implements PluginRunHooks {
    private static final int MAX_BYTES = 64 * 1_024;
    private static final Set<String> FIELDS = Set.of(
            "version", "id", "event", "subjectGlob", "failurePolicy", "timeoutMs", "url");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final List<Template> templates;

    /**
     * 加载 snapshots 中 manifest 明确声明的 Hook 文件；任一无效 template 使 composition fail closed。
     *
     * @param storeRoot 固定 Plugin store root
     * @param snapshots 已通过 Registry trust Gate 的 immutable snapshots
     */
    public PluginRunHookTemplates(Path storeRoot, List<PluginSnapshot> snapshots) {
        Objects.requireNonNull(storeRoot, "storeRoot 不能为空");
        List<Template> loaded = new ArrayList<>();
        try {
            for (PluginSnapshot snapshot : snapshots) {
                Path directory = pluginDirectory(storeRoot, snapshot);
                for (var component : snapshot.manifest().components().stream()
                        .filter(value -> value.kind()
                                == io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentKind.HOOKS).toList()) {
                    Path file = directory.resolve(component.logicalPath()).normalize();
                    if (!file.startsWith(directory) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(file) || Files.size(file) > MAX_BYTES) throw new IOException();
                    loaded.add(parse(snapshot, component.name(), Files.readAllBytes(file)));
                }
            }
        } catch (RuntimeException | IOException failure) {
            throw new IllegalArgumentException("Plugin Hook template 拒绝");
        }
        templates = List.copyOf(loaded);
    }

    /**
     * 构造只按 Skill descriptor 引用选择模板的动态 binder。
     *
     * <p>引用是同一 Plugin manifest 中 HOOKS component 的名称，不能跨 Plugin 指向或按任意
     * binding ID 猜测。非 Plugin Skill 声明 Hook 时因当前 Session 没有对应可信模板 catalog 而
     * Fail Closed。</p>
     *
     * @param coordinator 接收动态 Run-scoped binding 的 Hook 协调器
     * @return 仅绑定同一 Plugin 内可信模板的 Skill Hook binder
     */
    public SkillHookBinder skillBinder(HookCoordinator coordinator) {
        Objects.requireNonNull(coordinator, "coordinator 不能为空");
        return (runId, descriptor) -> bindSkill(coordinator, runId, descriptor);
    }

    private AutoCloseable bindSkill(HookCoordinator coordinator, RunId runId, SkillDescriptor descriptor) {
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(descriptor, "descriptor 不能为空");
        if (descriptor.hooks().isEmpty()) return () -> { };
        if (descriptor.source() != SkillSource.PLUGIN) {
            throw new IllegalArgumentException("Skill Hook template 未受信");
        }
        String prefix = "plugin__";
        String marker = "__skills__";
        String value = descriptor.id().value();
        int markerIndex = value.indexOf(marker, prefix.length());
        if (!value.startsWith(prefix) || markerIndex < 0) {
            throw new IllegalArgumentException("Plugin Skill identity 非法");
        }
        PluginId pluginId = new PluginId(value.substring(prefix.length(), markerIndex));
        List<HookBinding> selected = new ArrayList<>();
        int order = 10_000;
        for (String reference : descriptor.hooks()) {
            List<Template> matching = templates.stream()
                    .filter(template -> template.pluginId.equals(pluginId)
                            && template.componentName.equals(reference)).toList();
            if (matching.size() != 1) throw new IllegalArgumentException("Skill Hook template 引用非法");
            selected.add(matching.getFirst().binding(order++));
        }
        return coordinator.bindRun(runId, selected);
    }

    @Override
    public List<HookBinding> bindings(RunId runId, Map<PluginId, String> fingerprints) {
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(fingerprints, "fingerprints 不能为空");
        // S11 的 HOOKS component 是受信 template catalog，不因 Plugin generation lease 自动启用。
        return List.of();
    }

    private static Template parse(PluginSnapshot snapshot, String componentName, byte[] bytes) throws IOException {
        JsonNode root = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
        if (root == null || !root.isObject() || root.properties().stream()
                .anyMatch(entry -> !FIELDS.contains(entry.getKey())) || root.path("version").asInt(-1) != 1) {
            throw new IllegalArgumentException();
        }
        String id = text(root, "id");
        HookEventKind event = HookEventKind.valueOf(text(root, "event"));
        HookFailurePolicy policy = HookFailurePolicy.valueOf(text(root, "failurePolicy"));
        long timeout = root.path("timeoutMs").asLong(-1);
        if (timeout < 1 || timeout > 30_000) throw new IllegalArgumentException();
        URI endpoint = URI.create(text(root, "url"));
        String subject = root.has("subjectGlob") ? text(root, "subjectGlob") : null;
        return new Template(snapshot.manifest().id(), snapshot.fingerprint().treeDigest(), componentName,
                "plugin__" + snapshot.manifest().id().value() + "__hooks__" + componentName + "__" + id,
                event, subject, policy, Duration.ofMillis(timeout), endpoint);
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.stringValue().isBlank()) throw new IllegalArgumentException();
        return value.stringValue();
    }

    private static Path pluginDirectory(Path root, PluginSnapshot snapshot) throws IOException {
        Path directory = root.resolve(snapshot.manifest().id().value() + "-"
                + snapshot.safeContentId().substring(0, 16)).normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) throw new IOException();
        return directory;
    }

    private record Template(PluginId pluginId, String treeDigest, String componentName, String id,
            HookEventKind event, String subjectGlob, HookFailurePolicy policy, Duration timeout, URI endpoint) {
        private HookBinding binding(int order) {
            HookMatcher matcher = subjectGlob == null ? HookMatcher.event(event)
                    : HookMatcher.subject(event, subjectGlob);
            return new HookBinding(id, matcher, new HttpHookHandler(id, endpoint, timeout),
                    policy, true, order);
        }
    }
}
