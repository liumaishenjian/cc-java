package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.Objects;

/**
 * 当前 Session 冻结的单个 Skill 恢复身份，不包含 catalog snapshot ID。
 *
 * <p>Adapter 在 composition 时从已验证 metadata/body/resource 和受信 Plugin snapshot 计算该值；
 * Core 只能读取，不能用任意路径或 descriptor 猜测 Plugin 身份。</p>
 *
 * @param skillId 规范 Skill ID
 * @param manifestDigest metadata manifest 摘要
 * @param bodyDigest Markdown body 摘要
 * @param contentDigest 完整 SKILL.md 摘要
 * @param resourcesDigest 资源聚合摘要
 * @param toolRestrictionDigest 声明 allowed-tools 摘要
 * @param hookSetDigest Hook 引用集合摘要
 * @param pluginTreeDigest Plugin tree 摘要或 empty digest
 * @param pluginManifestDigest Plugin manifest 摘要或 empty digest
 * @param mcpConfigDigest MCP component identity 摘要或 empty digest
 * @since 0.11.0
 */
public record SkillRecoveryIdentity(
        SkillId skillId,
        String manifestDigest,
        String bodyDigest,
        String contentDigest,
        String resourcesDigest,
        String toolRestrictionDigest,
        String hookSetDigest,
        String pluginTreeDigest,
        String pluginManifestDigest,
        String mcpConfigDigest) {

    /** 校验 Skill identity 与全部恢复摘要均为规范 SHA-256。 */
    public SkillRecoveryIdentity {
        skillId = Objects.requireNonNull(skillId, "skillId 不能为空");
        manifestDigest = digest(manifestDigest, "manifestDigest");
        bodyDigest = digest(bodyDigest, "bodyDigest");
        contentDigest = digest(contentDigest, "contentDigest");
        resourcesDigest = digest(resourcesDigest, "resourcesDigest");
        toolRestrictionDigest = digest(toolRestrictionDigest, "toolRestrictionDigest");
        hookSetDigest = digest(hookSetDigest, "hookSetDigest");
        pluginTreeDigest = digest(pluginTreeDigest, "pluginTreeDigest");
        pluginManifestDigest = digest(pluginManifestDigest, "pluginManifestDigest");
        mcpConfigDigest = digest(mcpConfigDigest, "mcpConfigDigest");
    }

    private static String digest(String value, String field) {
        value = Objects.requireNonNull(value, field + " 不能为空");
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " 非法");
        return value;
    }
}
