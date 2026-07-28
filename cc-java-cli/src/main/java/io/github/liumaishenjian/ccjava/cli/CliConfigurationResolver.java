package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.cli.CliConfiguration.ResolvedValue;
import io.github.liumaishenjian.ccjava.cli.CliConfiguration.SecretStatus;
import io.github.liumaishenjian.ccjava.cli.CliConfiguration.Source;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 按 {@code CLI → Environment → Defaults} 合并 S02 起步配置。
 *
 * <p>该解析器不读取项目配置文件，也不设计 S08 的多层 Merge 语义。Secret 只在此处
 * 判断是否存在，不复制进 {@link CliConfiguration}。</p>
 *
 * @since 0.1.0
 */
public final class CliConfigurationResolver {

    static final String MODEL_ENV = "CC_JAVA_MODEL";
    static final String OLLAMA_BASE_URL_ENV = "CC_JAVA_OLLAMA_BASE_URL";
    static final String MAX_OUTPUT_TOKENS_ENV = "CC_JAVA_MAX_OUTPUT_TOKENS";
    static final String WORKSPACE_ENV = "CC_JAVA_WORKSPACE";
    static final String TIMEOUT_ENV = "CC_JAVA_TIMEOUT_SECONDS";
    static final String MAX_RETRIES_ENV = "CC_JAVA_MAX_RETRIES";
    static final int MAX_CONFIGURED_RETRIES = 3;
    static final int MAX_MODEL_NAME_LENGTH = 256;

    private final CliDefaults defaults;
    private final CliEnvironment environment;

    /**
     * 创建配置解析器。
     *
     * @param defaults    Composition Root 默认值
     * @param environment 进程环境边界
     */
    public CliConfigurationResolver(
            CliDefaults defaults,
            CliEnvironment environment) {
        this.defaults = Objects.requireNonNull(defaults, "defaults 不能为空");
        this.environment = Objects.requireNonNull(environment, "environment 不能为空");
    }

    /**
     * 解析、校验并标记配置来源。
     *
     * @param overrides Picocli 覆盖项
     * @return 不含 Secret 的最终配置
     * @throws CliConfigurationException 输入值无效时
     */
    public CliConfiguration resolve(CliOverrides overrides)
            throws CliConfigurationException {
        Objects.requireNonNull(overrides, "overrides 不能为空");

        ResolvedValue<Path> workspace = resolveWorkspace(overrides.workspace());
        ResolvedValue<String> model = resolveRequiredText(
                overrides.model(),
                MODEL_ENV,
                "model");
        ResolvedValue<URI> ollamaBaseUrl =
                resolveOllamaBaseUrl(overrides.ollamaBaseUrl());
        ResolvedValue<Integer> maxOutputTokens =
                resolveMaxOutputTokens(overrides.maxOutputTokens());
        ResolvedValue<Duration> timeout = resolvePositiveDuration(
                overrides.timeoutSeconds());
        ResolvedValue<Integer> maxRetries = resolveRetries(overrides.maxRetries());
        boolean secretPresent = environment
                .read(defaults.apiKeyEnvironmentVariable())
                .filter(value -> !value.isBlank())
                .isPresent();

        return new CliConfiguration(
                defaults.providerId(),
                workspace,
                model,
                ollamaBaseUrl,
                maxOutputTokens,
                timeout,
                maxRetries,
                new SecretStatus(
                        defaults.apiKeyEnvironmentVariable(),
                        defaults.apiKeyRequired(),
                        secretPresent),
                defaults.systemInstructions(),
                false);
    }

    private ResolvedValue<Path> resolveWorkspace(Path cliValue)
            throws CliConfigurationException {
        Source source;
        Path value;
        if (cliValue != null) {
            source = Source.CLI;
            value = cliValue;
        } else {
            Optional<String> environmentValue = nonBlankEnvironment(WORKSPACE_ENV);
            if (environmentValue.isPresent()) {
                source = Source.ENVIRONMENT;
                try {
                    value = Path.of(environmentValue.orElseThrow());
                } catch (RuntimeException exception) {
                    throw new CliConfigurationException(
                            WORKSPACE_ENV + " 不是有效路径");
                }
            } else {
                source = Source.DEFAULT;
                value = defaults.workspace();
            }
        }

        Path normalized = value.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new CliConfigurationException("Workspace 不是已存在目录: " + normalized);
        }
        return new ResolvedValue<>(normalized, source);
    }

    private ResolvedValue<String> resolveRequiredText(
            String cliValue,
            String environmentName,
            String displayName) throws CliConfigurationException {
        if (cliValue != null) {
            if (cliValue.isBlank()) {
                throw new CliConfigurationException(displayName + " 不能为空白");
            }
            return new ResolvedValue<>(
                    validateModelName(cliValue, displayName),
                    Source.CLI);
        }
        Optional<String> environmentValue = nonBlankEnvironment(environmentName);
        if (environmentValue.isPresent()) {
            return new ResolvedValue<>(
                    validateModelName(
                            environmentValue.orElseThrow(),
                            environmentName),
                    Source.ENVIRONMENT);
        }
        throw new CliConfigurationException(
                displayName + " 必须通过 --model 或 " + environmentName + " 指定");
    }

    private String validateModelName(String value, String source)
            throws CliConfigurationException {
        boolean containsUnsafeCharacter = value.codePoints().anyMatch(
                codePoint -> Character.isWhitespace(codePoint)
                        || Character.isISOControl(codePoint));
        if (containsUnsafeCharacter || value.length() > MAX_MODEL_NAME_LENGTH) {
            throw new CliConfigurationException(
                    source + " 必须是不含空白或控制字符且不超过 "
                            + MAX_MODEL_NAME_LENGTH
                            + " 个字符的模型名");
        }
        return value;
    }

    private ResolvedValue<URI> resolveOllamaBaseUrl(URI cliValue)
            throws CliConfigurationException {
        if (cliValue != null) {
            return new ResolvedValue<>(
                    validateBaseUrl(cliValue, "--ollama-base-url"),
                    Source.CLI);
        }
        Optional<String> environmentValue = nonBlankEnvironment(OLLAMA_BASE_URL_ENV);
        if (environmentValue.isPresent()) {
            URI value;
            try {
                value = URI.create(environmentValue.orElseThrow());
            } catch (IllegalArgumentException exception) {
                throw new CliConfigurationException(
                        OLLAMA_BASE_URL_ENV + " 不是有效 URI");
            }
            return new ResolvedValue<>(
                    validateBaseUrl(value, OLLAMA_BASE_URL_ENV),
                    Source.ENVIRONMENT);
        }
        return new ResolvedValue<>(
                validateBaseUrl(defaults.ollamaBaseUrl(), "默认 Ollama Base URL"),
                Source.DEFAULT);
    }

    private ResolvedValue<Integer> resolveMaxOutputTokens(Integer cliValue)
            throws CliConfigurationException {
        if (cliValue != null) {
            return new ResolvedValue<>(
                    outputTokens(cliValue, "--max-output-tokens"),
                    Source.CLI);
        }
        Optional<String> environmentValue =
                nonBlankEnvironment(MAX_OUTPUT_TOKENS_ENV);
        if (environmentValue.isPresent()) {
            return new ResolvedValue<>(
                    outputTokens(
                            parseInteger(
                                    environmentValue.orElseThrow(),
                                    MAX_OUTPUT_TOKENS_ENV),
                            MAX_OUTPUT_TOKENS_ENV),
                    Source.ENVIRONMENT);
        }
        return new ResolvedValue<>(
                outputTokens(
                        defaults.maxOutputTokens(),
                        "默认 maxOutputTokens"),
                Source.DEFAULT);
    }

    private ResolvedValue<Duration> resolvePositiveDuration(Integer cliSeconds)
            throws CliConfigurationException {
        if (cliSeconds != null) {
            return new ResolvedValue<>(
                    duration(cliSeconds, "--timeout-seconds"),
                    Source.CLI);
        }
        Optional<String> environmentValue = nonBlankEnvironment(TIMEOUT_ENV);
        if (environmentValue.isPresent()) {
            return new ResolvedValue<>(
                    duration(parseInteger(environmentValue.orElseThrow(), TIMEOUT_ENV), TIMEOUT_ENV),
                    Source.ENVIRONMENT);
        }
        return new ResolvedValue<>(defaults.timeout(), Source.DEFAULT);
    }

    private ResolvedValue<Integer> resolveRetries(Integer cliValue)
            throws CliConfigurationException {
        if (cliValue != null) {
            return new ResolvedValue<>(
                    retries(cliValue, "--max-retries"),
                    Source.CLI);
        }
        Optional<String> environmentValue = nonBlankEnvironment(MAX_RETRIES_ENV);
        if (environmentValue.isPresent()) {
            return new ResolvedValue<>(
                    retries(
                            parseInteger(environmentValue.orElseThrow(), MAX_RETRIES_ENV),
                            MAX_RETRIES_ENV),
                    Source.ENVIRONMENT);
        }
        return new ResolvedValue<>(
                retries(defaults.maxRetries(), "默认 maxRetries"),
                Source.DEFAULT);
    }

    private Optional<String> nonBlankEnvironment(String name) {
        return environment.read(name).filter(value -> !value.isBlank());
    }

    private int parseInteger(String value, String source)
            throws CliConfigurationException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new CliConfigurationException(source + " 必须是整数");
        }
    }

    private Duration duration(int seconds, String source)
            throws CliConfigurationException {
        if (seconds <= 0) {
            throw new CliConfigurationException(source + " 必须大于 0");
        }
        return Duration.ofSeconds(seconds);
    }

    private int retries(int value, String source)
            throws CliConfigurationException {
        if (value < 0 || value > MAX_CONFIGURED_RETRIES) {
            throw new CliConfigurationException(
                    source + " 必须在 0.." + MAX_CONFIGURED_RETRIES + " 之间");
        }
        return value;
    }

    private URI validateBaseUrl(URI value, String source)
            throws CliConfigurationException {
        String scheme = value.getScheme();
        boolean supportedScheme =
                "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!supportedScheme
                || value.getHost() == null
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null) {
            throw new CliConfigurationException(
                    source
                            + " 必须是无凭证、查询参数和 Fragment 的 HTTP(S) 根地址");
        }
        return value;
    }

    private int outputTokens(int value, String source)
            throws CliConfigurationException {
        if (value < 1 || value > 1_000_000) {
            throw new CliConfigurationException(
                    source + " 必须在 1..1000000 之间");
        }
        return value;
    }
}
