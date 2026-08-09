package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.Objects;

/**
 * Session journal 中用于精确恢复验证的隐私安全 Skill 身份。
 *
 * <p>所有自由文本均先聚合为 SHA-256；该记录不保存正文、资源、调用参数、路径、endpoint、
 * 环境变量、Hook lease 或 Tool Scope。Plugin-only 字段对本地 Skill 使用空集合摘要，避免
 * nullable 字段产生宽松匹配。</p>
 *
 * @param skillId 历史 Skill
 * @param snapshotId 历史 catalog digest
 * @param manifestDigest metadata manifest 的规范摘要
 * @param bodyDigest Markdown body 摘要
 * @param contentDigest 完整 SKILL.md 内容摘要
 * @param resourcesDigest 资源逻辑名与内容摘要的聚合摘要
 * @param effectiveToolDigest 实际可见 Tool 名集合摘要
 * @param hookSetDigest Hook template 引用集合摘要
 * @param pluginTreeDigest Plugin canonical tree 摘要；本地 Skill 为 empty digest
 * @param pluginManifestDigest Plugin manifest 摘要；本地 Skill 为 empty digest
 * @param mcpConfigDigest 相关 MCP component identity 聚合摘要；本地 Skill 为 empty digest
 * @since 0.11.0
 */
public record SkillRecoveryRecord(
        SkillId skillId,
        String snapshotId,
        String manifestDigest,
        String bodyDigest,
        String contentDigest,
        String resourcesDigest,
        String effectiveToolDigest,
        String hookSetDigest,
        String pluginTreeDigest,
        String pluginManifestDigest,
        String mcpConfigDigest) {

    /** 校验全部恢复摘要，禁止缺省或非 SHA-256 身份。 */
    public SkillRecoveryRecord {
        skillId = Objects.requireNonNull(skillId, "skillId 不能为空");
        snapshotId = digest(snapshotId, "snapshotId");
        manifestDigest = digest(manifestDigest, "manifestDigest");
        bodyDigest = digest(bodyDigest, "bodyDigest");
        contentDigest = digest(contentDigest, "contentDigest");
        resourcesDigest = digest(resourcesDigest, "resourcesDigest");
        effectiveToolDigest = digest(effectiveToolDigest, "effectiveToolDigest");
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
