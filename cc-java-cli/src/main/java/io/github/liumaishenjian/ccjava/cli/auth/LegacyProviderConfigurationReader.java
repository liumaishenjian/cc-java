package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition;
import io.github.liumaishenjian.ccjava.model.springai.config.ProviderSettingsLoader;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * 固定仓库路径的 legacy OpenAI-compatible properties 只读视图。
 *
 * <p>只接受 {@code config/provider.local.properties}，拒绝链接、非普通文件和超过 16 KiB 的内容。
 * 环境 overlay 不属于文件迁移，读取过程绝不创建、修改、rename 或删除 legacy 文件。</p>
 */
public final class LegacyProviderConfigurationReader {
    private static final int MAXIMUM_BYTES = 16 * 1024;
    private static final String BASE_URL = "openai.base-url";
    private static final String API_KEY = "openai.api-key";
    private static final String MODEL = "openai.model";

    private final Path repositoryRoot;
    private final Path legacyFile;

    /**
     * 从已解析 repository root 派生唯一 legacy 路径。
     *
     * @param repositoryRoot 用于定位固定 legacy 配置相对路径的仓库根目录
     */
    public LegacyProviderConfigurationReader(Path repositoryRoot) {
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot 不能为空")
                .toAbsolutePath().normalize();
        this.legacyFile = this.repositoryRoot.resolve(ProviderSettingsLoader.LOCAL_CONFIG_PATH).normalize();
        if (!legacyFile.startsWith(this.repositoryRoot)) throw incomplete();
    }

    /**
     * 返回文件中的完整三元组；不存在返回 empty，partial 或不安全则 fail closed。
     *
     * @return 不存在时为空，否则为持有可清零 Secret 的完整 legacy 配置
     */
    public Optional<LegacyConfiguration> read() {
        if (!Files.exists(legacyFile, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            Path parent = legacyFile.getParent();
            if (Files.isSymbolicLink(legacyFile)
                    || !Files.isRegularFile(legacyFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(legacyFile) < 1 || Files.size(legacyFile) > MAXIMUM_BYTES
                    || !parent.toRealPath().equals(parent.toAbsolutePath().normalize())
                    || !legacyFile.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(legacyFile.toRealPath())) {
                throw incomplete();
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(legacyFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            String baseUrl = clean(properties.getProperty(BASE_URL));
            String apiKey = clean(properties.getProperty(API_KEY));
            String model = clean(properties.getProperty(MODEL));
            if (baseUrl == null && apiKey == null && model == null) return Optional.empty();
            if (baseUrl == null || apiKey == null || model == null) throw incomplete();
            ProviderDefinition definition = new ProviderDefinition(
                    "legacy-check", ProviderDefinition.Kind.OPENAI_COMPATIBLE, "Legacy compatible provider",
                    URI.create(baseUrl), ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS,
                    java.util.List.of(model), model, Map.of(), Duration.ofSeconds(10), Duration.ofSeconds(300));
            return Optional.of(new LegacyConfiguration(definition.baseUri(), definition.defaultModelId(),
                    new SecretMaterial(apiKey.toCharArray())));
        } catch (ProviderAuthException failure) {
            throw failure;
        } catch (IOException | RuntimeException invalid) {
            throw incomplete();
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
    private static ProviderAuthException incomplete() {
        return new ProviderAuthException(ProviderAuthException.Code.LEGACY_CONFIGURATION_INCOMPLETE,
                ProviderAuthException.Action.CHECK_LOCAL_STORE, false);
    }

    /**
     * 完整 legacy 文件值；调用方必须关闭以清零 secret。
     *
     * @param baseUri legacy provider 的基础 URI
     * @param modelId legacy provider 的默认模型标识
     * @param secret 可清零的 legacy API 凭据
     */
    public record LegacyConfiguration(URI baseUri, String modelId, SecretMaterial secret) implements AutoCloseable {
        /** 校验并持有短生命周期可清零 secret。 */
        public LegacyConfiguration {
            Objects.requireNonNull(baseUri); Objects.requireNonNull(modelId); Objects.requireNonNull(secret);
        }
        @Override public void close() { secret.close(); }
        @Override public String toString() { return "LegacyConfiguration[<redacted>]"; }
    }
}
