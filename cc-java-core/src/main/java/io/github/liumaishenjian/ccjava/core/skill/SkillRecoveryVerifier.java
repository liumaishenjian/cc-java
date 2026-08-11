package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryRecord;
import io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 精确比较历史 Skill 恢复身份与当前受信 snapshot；不恢复 Hook、Projection 或 Tool Scope。
 *
 * <p>manifest/body/content/resources/tool/hook 与 Plugin/MCP component 摘要必须全部匹配；任一缺失
 * 或差异均 Fail Closed。effective Tool 摘要由历史激活时的实际交集产生，因此调用方还必须提供
 * 当前 Runtime 对同一 Skill 重新计算的记录。</p>
 *
 * @since 0.11.0
 */
public final class SkillRecoveryVerifier {
    /** 创建无状态的 Skill 恢复身份比较器。 */
    public SkillRecoveryVerifier() { }

    /**
     * 比较历史激活记录与当前 catalog、Plugin/MCP 摘要及有效 Tool scope。
     *
     * @param snapshot 当前 Session 的 Skill catalog snapshot
     * @param records 历史 durable Skill 激活记录
     * @param identities 当前受信内容身份目录
     * @param runtimeToolNames 当前 Runtime Tool 名称
     * @return 是否全部匹配及不匹配 Skill ID
     */
    public SkillRecoveryResult verify(SkillCatalogSnapshot snapshot, List<SkillRecoveryRecord> records,
            SkillRecoveryIdentityCatalog identities, List<String> runtimeToolNames) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        Objects.requireNonNull(records, "records 不能为空");
        Objects.requireNonNull(identities, "identities 不能为空");
        runtimeToolNames = List.copyOf(Objects.requireNonNull(runtimeToolNames, "runtimeToolNames 不能为空"));
        List<SkillId> mismatches = new ArrayList<>();
        List<String> visibleTools = runtimeToolNames;
        for (SkillRecoveryRecord record : records) {
            var found = identities.find(record.skillId());
            if (!snapshot.snapshotId().equals(record.snapshotId()) || found.isEmpty()) {
                mismatches.add(record.skillId());
                continue;
            }
            var current = found.get();
            var descriptor = snapshot.entries().stream()
                    .filter(value -> value.id().equals(record.skillId())).findFirst();
            if (descriptor.isEmpty()) {
                mismatches.add(record.skillId());
                continue;
            }
            visibleTools = new SkillToolScopeNarrower().narrow(visibleTools, descriptor.get().toolRestriction());
            if (!current.manifestDigest().equals(record.manifestDigest())
                    || !current.bodyDigest().equals(record.bodyDigest())
                    || !current.contentDigest().equals(record.contentDigest())
                    || !current.resourcesDigest().equals(record.resourcesDigest())
                    || !digestStrings(visibleTools).equals(record.effectiveToolDigest())
                    || !current.hookSetDigest().equals(record.hookSetDigest())
                    || !current.pluginTreeDigest().equals(record.pluginTreeDigest())
                    || !current.pluginManifestDigest().equals(record.pluginManifestDigest())
                    || !current.mcpConfigDigest().equals(record.mcpConfigDigest())) {
                mismatches.add(record.skillId());
            }
        }
        return new SkillRecoveryResult(mismatches.isEmpty(), mismatches);
    }

    private static String digestStrings(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            values.stream().sorted().forEach(value -> {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
