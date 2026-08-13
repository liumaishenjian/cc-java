package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.core.CancellationToken;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 显式、非破坏的 legacy 文件复制迁移用例。
 *
 * <p>迁移只在目标 provider/profile 都不存在时执行；先发布 custom definition，再通过 credential
 * store transaction 发布 STORE profile。legacy 文件由只读 reader 持有且本服务没有写入路径。返回成功前
 * 重读两个目标并验证 identity；失败绝不声称完成，也不把环境 overlay 冒充文件来源。</p>
 */
public final class LegacyCredentialMigrationService {
    private final LegacyProviderConfigurationReader reader;
    private final ProviderDefinitionStore definitions;
    private final CredentialStore credentials;

    /**
     * 创建共用 store 的显式迁移服务。
     *
     * @param reader 只读 legacy 配置来源
     * @param definitions 发布迁移后非秘密 Provider 定义的存储
     * @param credentials 以事务方式发布迁移后凭据 profile 的存储
     */
    public LegacyCredentialMigrationService(LegacyProviderConfigurationReader reader,
                                            ProviderDefinitionStore definitions,
                                            CredentialStore credentials) {
        this.reader = Objects.requireNonNull(reader);
        this.definitions = Objects.requireNonNull(definitions);
        this.credentials = Objects.requireNonNull(credentials);
    }

    /**
     * 复制完整 legacy 三元组；调用方必须显式给出新 provider/profile identity。
     *
     * @param providerId 新建 custom Provider 的标识
     * @param profileId 新建凭据 profile 的标识
     * @param setDefault 是否将新 profile 设为该 Provider 的默认项
     * @param cancellation 贯穿快照、发布与验证过程的取消令牌
     * @return 不含路径、值或 Secret identity 的已验证迁移结果
     */
    public MigrationResult migrate(String providerId, String profileId, boolean setDefault,
                                   CancellationToken cancellation) {
        ProviderDefinitionStore.Snapshot providerSnapshot = definitions.snapshot(cancellation);
        CredentialStore.Snapshot credentialSnapshot = credentials.snapshot(cancellation);
        if (providerSnapshot.catalog().list().stream().anyMatch(value -> value.providerId().equals(providerId))
                || credentialSnapshot.find(providerId, profileId).isPresent()) throw conflict();
        LegacyProviderConfigurationReader.LegacyConfiguration legacy = reader.read().orElseThrow(
                LegacyCredentialMigrationService::incomplete);
        try (legacy) {
            ProviderDefinition definition = new ProviderDefinition(providerId,
                    ProviderDefinition.Kind.OPENAI_COMPATIBLE, "Migrated legacy provider", legacy.baseUri(),
                    ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS, List.of(legacy.modelId()),
                    legacy.modelId(), Map.of(), Duration.ofSeconds(10), Duration.ofSeconds(300));
            ProviderDefinitionStore.Snapshot published = definitions.add(
                    definition, providerSnapshot.generation(), cancellation);
            try {
                credentials.saveStore(providerId, profileId, legacy.secret(), setDefault, cancellation);
            } catch (RuntimeException failedCredential) {
                // Definition 已发布但 credential 未发布时回滚非秘密 definition；原文件始终不变。
                try { definitions.remove(providerId, published.generation(), CancellationToken.none()); }
                catch (RuntimeException ignored) { /* 保留可见 partial，重试将 conflict，绝不覆盖。 */ }
                throw failedCredential;
            }
            ProviderDefinition reread = definitions.snapshot(cancellation).catalog().require(providerId);
            CredentialProfile profile = credentials.snapshot(cancellation).find(providerId, profileId)
                    .orElseThrow(LegacyCredentialMigrationService::conflict);
            if (!reread.baseUri().equals(legacy.baseUri()) || !reread.models().equals(List.of(legacy.modelId()))
                    || !(profile.secretRef() instanceof SecretRef.Store)) throw conflict();
            return new MigrationResult("MIGRATED_COPY_VERIFIED", providerId, profileId);
        }
    }

    private static ProviderAuthException incomplete() {
        return new ProviderAuthException(ProviderAuthException.Code.LEGACY_CONFIGURATION_INCOMPLETE,
                ProviderAuthException.Action.CHECK_LOCAL_STORE, false);
    }
    private static ProviderAuthException conflict() {
        return new ProviderAuthException(ProviderAuthException.Code.LEGACY_MIGRATION_CONFLICT,
                ProviderAuthException.Action.CHECK_LOCAL_STORE, false);
    }

    /**
     * 不含路径、值或 secret identity 的迁移结果。
     *
     * @param code 固定的迁移成功码
     * @param providerId 新建的 provider 标识
     * @param profileId 新建的凭据 profile 标识
     */
    public record MigrationResult(String code, String providerId, String profileId) {
        /** 校验固定成功码和目标 identity。 */
        public MigrationResult {
            if (!"MIGRATED_COPY_VERIFIED".equals(code)) throw new IllegalArgumentException("code 无效");
            new CredentialProfile(profileId, providerId, new SecretRef.Env("CC_VALIDATION_ONLY"),
                    java.time.Instant.EPOCH, java.time.Instant.EPOCH, java.util.Optional.empty());
        }
    }
}
