package io.github.liumaishenjian.ccjava.cli.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 不含 secret value 的 credential profile metadata。
 *
 * @param profileId profile identity
 * @param providerId Provider identity
 * @param secretRef store 或环境引用；其内部 identity 不得输出到 surface
 * @param createdAt 创建时间
 * @param updatedAt 最近替换时间
 * @param lastProbe privacy-safe 的最近显式 probe
 * @since 0.1.0
 */
public record CredentialProfile(
        String profileId, String providerId, SecretRef secretRef, Instant createdAt, Instant updatedAt,
        Optional<ProbeRecord> lastProbe) {
    /** 校验 metadata；认证方法在当前版本固定为 API_KEY。 */
    public CredentialProfile {
        profileId = id(profileId, "profileId"); providerId = id(providerId, "providerId");
        Objects.requireNonNull(secretRef, "secretRef 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空"); Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        lastProbe = Objects.requireNonNull(lastProbe, "lastProbe 不能为空");
    }
    /**
     * 返回当前固定的认证方法。
     *
     * @return 始终为 {@code API_KEY}
     */
    public String authMethod() { return "API_KEY"; }
    private static String id(String v, String f) {
        Objects.requireNonNull(v, f + " 不能为空");
        if (!v.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException(f + " 格式无效");
        return v;
    }
    /**
     * 不含响应、endpoint 或 request identity 的 probe 摘要。
     *
     * @param code 不泄露隐私的探测结果码
     * @param probedAt 探测执行时间
     * @param definitionDigest 探测定义摘要
     * @param modelId 探测使用的模型标识
     */
    public record ProbeRecord(String code, Instant probedAt, String definitionDigest, String modelId) {
        /** 校验所有摘要字段。 */ public ProbeRecord {
            Objects.requireNonNull(code); Objects.requireNonNull(probedAt); Objects.requireNonNull(definitionDigest);
            Objects.requireNonNull(modelId);
        }
    }
}
