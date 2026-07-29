package io.github.liumaishenjian.ccjava.model.springai.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static io.github.liumaishenjian.ccjava.model.springai.config.ProviderConfigurationException.Code.FILE_TOO_LARGE;
import static io.github.liumaishenjian.ccjava.model.springai.config.ProviderConfigurationException.Code.READ_FAILED;
import static io.github.liumaishenjian.ccjava.model.springai.config.ProviderConfigurationException.Code.SYMBOLIC_LINK_REJECTED;

/**
 * 从仓库内 Git 忽略文件加载 OpenAI-compatible Provider 配置。
 *
 * <p>默认文件为 {@value #LOCAL_CONFIG_PATH}。环境变量只作为 CI 或临时运行覆盖，
 * 优先级高于本地文件。Loader 固定文件位置、拒绝符号链接并限制字节数，避免把任意文件
 * 误当作秘密配置读取。</p>
 *
 * @since 0.1.0
 */
public final class ProviderSettingsLoader {

    /** 相对于仓库根目录的唯一 S02 本地配置路径。 */
    public static final String LOCAL_CONFIG_PATH = "config/provider.local.properties";
    /** 覆盖本地 Base URL 的环境变量名。 */
    public static final String BASE_URL_ENV = "CC_JAVA_OPENAI_BASE_URL";
    /** 覆盖本地 API Key 的环境变量名。 */
    public static final String API_KEY_ENV = "CC_JAVA_OPENAI_API_KEY";
    /** 覆盖本地模型名的环境变量名。 */
    public static final String MODEL_ENV = "CC_JAVA_OPENAI_MODEL";

    private static final String BASE_URL_PROPERTY = "openai.base-url";
    private static final String API_KEY_PROPERTY = "openai.api-key";
    private static final String MODEL_PROPERTY = "openai.model";
    private static final long MAX_CONFIG_BYTES = 16 * 1024;

    /**
     * 创建无状态配置 Loader。
     */
    public ProviderSettingsLoader() {
    }

    /**
     * 使用当前进程环境加载配置。
     *
     * @param repositoryRoot cc-java 仓库根目录
     * @return 已校验、字符串表示已脱敏的 Provider 配置
     */
    public OpenAiCompatibleSettings load(Path repositoryRoot) {
        return load(repositoryRoot, System.getenv());
    }

    /**
     * 使用显式环境映射加载配置，供 Composition Root 和确定性测试使用。
     *
     * @param repositoryRoot cc-java 仓库根目录
     * @param environment 环境变量快照；对应键存在时覆盖本地文件
     * @return 已校验的 Provider 配置
     */
    public OpenAiCompatibleSettings load(Path repositoryRoot, Map<String, String> environment) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(environment, "environment");

        Path normalizedRoot = repositoryRoot.toAbsolutePath().normalize();
        Path configPath = normalizedRoot.resolve(LOCAL_CONFIG_PATH).normalize();
        if (!configPath.startsWith(normalizedRoot)) {
            throw new ProviderConfigurationException(READ_FAILED, "Provider config path escaped repository root");
        }

        Properties properties = readProperties(configPath);
        return new OpenAiCompatibleSettings(
                overlay(environment, BASE_URL_ENV, properties.getProperty(BASE_URL_PROPERTY)),
                overlay(environment, API_KEY_ENV, properties.getProperty(API_KEY_PROPERTY)),
                overlay(environment, MODEL_ENV, properties.getProperty(MODEL_PROPERTY))
        );
    }

    private static Properties readProperties(Path configPath) {
        Properties properties = new Properties();
        if (!Files.exists(configPath, LinkOption.NOFOLLOW_LINKS)) {
            return properties;
        }
        if (Files.isSymbolicLink(configPath)) {
            throw new ProviderConfigurationException(
                    SYMBOLIC_LINK_REJECTED,
                    "Provider config must not be a symbolic link"
            );
        }
        try {
            if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new ProviderConfigurationException(READ_FAILED, "Provider config is not a regular file");
            }
            if (Files.size(configPath) > MAX_CONFIG_BYTES) {
                throw new ProviderConfigurationException(FILE_TOO_LARGE, "Provider config exceeds 16 KiB");
            }
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return properties;
        } catch (IOException exception) {
            throw new ProviderConfigurationException(READ_FAILED, "Provider config could not be read");
        }
    }

    private static String overlay(Map<String, String> environment, String key, String fileValue) {
        return environment.containsKey(key) ? environment.get(key) : fileValue;
    }
}
