package io.github.liumaishenjian.ccjava.cli.skills;

import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillResourceSnapshot;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 受信 Plugin snapshot 中已经绑定 immutable content directory 的 Skill 集合。
 *
 * <p>该值只由 Plugin composition 创建；{@code skillFile} 和 {@code contentRoot} 不进入 Domain、
 * Session 或诊断。恢复身份仅暴露摘要，不能由任意 descriptor 或路径伪造。</p>
 *
 * @param entries 按 Plugin/组件稳定顺序排列的绑定项
 * @since 0.11.0
 */
public record PluginSkillSet(List<Entry> entries) {
    /** 冻结并拒绝重复全局 Skill ID。 */
    public PluginSkillSet {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries 不能为空"));
        Map<SkillId, Entry> ids = new LinkedHashMap<>();
        for (Entry entry : entries) {
            entry = Objects.requireNonNull(entry, "entry 不能为空");
            if (entry.descriptor().source() != io.github.liumaishenjian.ccjava.domain.skill.SkillSource.PLUGIN
                    || ids.putIfAbsent(entry.descriptor().id(), entry) != null) {
                throw new IllegalArgumentException("Plugin Skill 身份重复或来源非法");
            }
        }
    }

    /** @return 空 Plugin Skill 集合 */
    public static PluginSkillSet empty() { return new PluginSkillSet(List.of()); }

    /**
     * @param descriptor metadata-only 全局 Skill 描述
     * @param skillFile immutable content directory 内的 SKILL.md，仅用于来源审计
     * @param contentRoot 已验证 Plugin content root
     * @param markdown Session composition 时冻结的正文，不随安装目录漂移
     * @param resources Session composition 时冻结的资源文本快照
     * @param manifestDigest frontmatter identity digest
     * @param bodyDigest 正文 identity digest
     * @param resourcesDigest 声明资源 identity aggregate digest
     * @param toolDigest 声明 allowed-tools digest
     * @param hookDigest 声明 Hook set digest
     * @param pluginTreeDigest Plugin canonical tree digest
     * @param pluginManifestDigest Plugin manifest digest
     * @param configDigest 相关 MCP Provider config identity aggregate digest
     */
    public record Entry(SkillDescriptor descriptor, Path skillFile, Path contentRoot,
            String markdown, List<SkillResourceSnapshot> resources,
            String manifestDigest, String bodyDigest, String resourcesDigest,
            String toolDigest, String hookDigest,
            String pluginTreeDigest, String pluginManifestDigest, String configDigest) {
        public Entry {
            descriptor = Objects.requireNonNull(descriptor, "descriptor 不能为空");
            skillFile = Objects.requireNonNull(skillFile, "skillFile 不能为空").toAbsolutePath().normalize();
            contentRoot = Objects.requireNonNull(contentRoot, "contentRoot 不能为空").toAbsolutePath().normalize();
            markdown = Objects.requireNonNull(markdown, "markdown 不能为空");
            resources = List.copyOf(Objects.requireNonNull(resources, "resources 不能为空"));
            manifestDigest = digest(manifestDigest, "manifestDigest");
            bodyDigest = digest(bodyDigest, "bodyDigest");
            resourcesDigest = digest(resourcesDigest, "resourcesDigest");
            toolDigest = digest(toolDigest, "toolDigest");
            hookDigest = digest(hookDigest, "hookDigest");
            pluginTreeDigest = digest(pluginTreeDigest, "pluginTreeDigest");
            pluginManifestDigest = digest(pluginManifestDigest, "pluginManifestDigest");
            configDigest = digest(configDigest, "configDigest");
        }
        private static String digest(String value, String field) {
            value = Objects.requireNonNull(value, field + " 不能为空");
            if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " 非法");
            return value;
        }
    }
}
