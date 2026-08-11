package io.github.liumaishenjian.ccjava.model.springai.config;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * 从固定 Git 忽略配置或环境变量加载可选 Anthropic Provider。
 *
 * <p>三项缺失时返回 empty；部分配置 Fail Closed。密钥不进入字符串、日志或异常。</p>
 *
 * @since 0.1.0
 */
public final class AnthropicSettingsLoader {
    /** Anthropic-compatible Base URL 环境覆盖键。 */
    public static final String BASE_URL_ENV = "CC_JAVA_ANTHROPIC_BASE_URL";
    /** Anthropic API Key 环境覆盖键。 */
    public static final String API_KEY_ENV = "CC_JAVA_ANTHROPIC_API_KEY";
    /** Anthropic 模型环境覆盖键。 */
    public static final String MODEL_ENV = "CC_JAVA_ANTHROPIC_MODEL";

    /** 创建无状态配置加载器。 */
    public AnthropicSettingsLoader() {
    }

    /**
     * 使用当前进程环境加载可选配置。
     *
     * @param repositoryRoot 固定配置仓库根
     * @return 完整配置，三项均缺失时为空
     */
    public Optional<AnthropicSettings> load(Path repositoryRoot) {
        return load(repositoryRoot, System.getenv());
    }

    /**
     * 使用显式环境映射加载可选配置，便于离线验证 precedence。
     *
     * @param repositoryRoot 固定配置仓库根
     * @param environment 环境覆盖映射
     * @return 完整配置，三项均缺失时为空
     */
    public Optional<AnthropicSettings> load(
            Path repositoryRoot, Map<String, String> environment) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot 不能为空");
        Objects.requireNonNull(environment, "environment 不能为空");
        Path root = repositoryRoot.toAbsolutePath().normalize();
        Path file = root.resolve(ProviderSettingsLoader.LOCAL_CONFIG_PATH).normalize();
        if (!file.startsWith(root)) {
            throw new ProviderConfigurationException(
                    ProviderConfigurationException.Code.READ_FAILED,
                    "Provider config path escaped repository root");
        }
        Properties properties = read(file);
        String baseUrl = overlay(environment, BASE_URL_ENV, properties.getProperty("anthropic.base-url"));
        String apiKey = overlay(environment, API_KEY_ENV, properties.getProperty("anthropic.api-key"));
        String model = overlay(environment, MODEL_ENV, properties.getProperty("anthropic.model"));
        boolean none = blank(baseUrl) && blank(apiKey) && blank(model);
        if (none) {
            return Optional.empty();
        }
        if (blank(baseUrl) || blank(apiKey) || blank(model)) {
            throw new ProviderConfigurationException(
                    ProviderConfigurationException.Code.REQUIRED_VALUE_MISSING,
                    "Anthropic provider configuration incomplete");
        }
        try {
            return Optional.of(new AnthropicSettings(URI.create(baseUrl), apiKey, model));
        } catch (RuntimeException failure) {
            throw new ProviderConfigurationException(
                    ProviderConfigurationException.Code.INVALID_BASE_URL,
                    "Anthropic provider configuration invalid");
        }
    }

    private static Properties read(Path file) {
        Properties properties = new Properties();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return properties;
        }
        if (Files.isSymbolicLink(file)) {
            throw new ProviderConfigurationException(
                    ProviderConfigurationException.Code.SYMBOLIC_LINK_REJECTED,
                    "Provider config must not be a link");
        }
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16 * 1024) {
                throw new IOException("Provider config 不是有界普通文件");
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return properties;
        } catch (IOException failure) {
            throw new ProviderConfigurationException(
                    ProviderConfigurationException.Code.READ_FAILED,
                    "Provider config could not be read");
        }
    }

    private static String overlay(
            Map<String, String> environment, String key, String value) {
        return environment.containsKey(key) ? environment.get(key) : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
