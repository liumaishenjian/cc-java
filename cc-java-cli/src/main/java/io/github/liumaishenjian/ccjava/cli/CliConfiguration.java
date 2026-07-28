package io.github.liumaishenjian.ccjava.cli;

import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 表示一次 CLI 进程启动后使用的类型化、可诊断配置。
 *
 * <p>每个普通值保留最终来源。API Key 只记录 {@code present/missing}，真实值仍由
 * {@link CliEnvironment} 在 Provider 边界按需读取，不进入本记录。</p>
 *
 * @param providerId     当前 Provider 标识
 * @param workspace      Workspace 与来源
 * @param model          模型名与来源
 * @param ollamaBaseUrl  Ollama 根地址与来源
 * @param maxOutputTokens 单回合输出上限与来源
 * @param timeout        Run 超时与来源
 * @param maxRetries     最大重试次数与来源
 * @param secretStatus   Provider Secret 的非敏感状态
 * @param systemInstructions Runtime 稳定系统指令
 * @param ansiEnabled    当前输出是否允许 ANSI
 * @since 0.1.0
 */
public record CliConfiguration(
        String providerId,
        ResolvedValue<Path> workspace,
        ResolvedValue<String> model,
        ResolvedValue<URI> ollamaBaseUrl,
        ResolvedValue<Integer> maxOutputTokens,
        ResolvedValue<Duration> timeout,
        ResolvedValue<Integer> maxRetries,
        SecretStatus secretStatus,
        String systemInstructions,
        boolean ansiEnabled) {

    /** 配置值的最终来源。 */
    public enum Source {
        /** 显式 Picocli 参数。 */
        CLI,
        /** 进程环境变量。 */
        ENVIRONMENT,
        /** Composition Root 默认值。 */
        DEFAULT
    }

    /**
     * 把配置值和其最终来源绑定。
     *
     * @param value  最终类型化值
     * @param source 最终来源
     * @param <T>    值类型
     */
    public record ResolvedValue<T>(T value, Source source) {

        /** 校验值和来源。 */
        public ResolvedValue {
            value = Objects.requireNonNull(value, "value 不能为空");
            source = Objects.requireNonNull(source, "source 不能为空");
        }
    }

    /**
     * 只公开 Secret 的环境变量名称和存在状态。
     *
     * @param environmentVariable 环境变量名称
     * @param required            Provider 是否要求它
     * @param present             是否存在非空值
     */
    public record SecretStatus(
            String environmentVariable,
            boolean required,
            boolean present) {

        /** 校验环境变量名称。 */
        public SecretStatus {
            environmentVariable = Objects.requireNonNull(
                    environmentVariable,
                    "environmentVariable 不能为空");
            if (environmentVariable.isBlank()) {
                throw new IllegalArgumentException("environmentVariable 不能为空白");
            }
        }

        /**
         * 返回可以安全显示的状态。
         *
         * @return {@code present} 或 {@code missing}
         */
        public String displayValue() {
            return present ? "present" : "missing";
        }
    }

    /** 校验完整配置。 */
    public CliConfiguration {
        providerId = requireText(providerId, "providerId");
        workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        model = Objects.requireNonNull(model, "model 不能为空");
        ollamaBaseUrl = Objects.requireNonNull(
                ollamaBaseUrl,
                "ollamaBaseUrl 不能为空");
        maxOutputTokens = Objects.requireNonNull(
                maxOutputTokens,
                "maxOutputTokens 不能为空");
        timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        maxRetries = Objects.requireNonNull(maxRetries, "maxRetries 不能为空");
        secretStatus = Objects.requireNonNull(secretStatus, "secretStatus 不能为空");
        systemInstructions = requireText(systemInstructions, "systemInstructions");
    }

    /**
     * 返回仅改变终端能力标记的副本。
     *
     * @param enabled 是否允许 ANSI
     * @return 配置副本
     */
    public CliConfiguration withAnsiEnabled(boolean enabled) {
        return new CliConfiguration(
                providerId,
                workspace,
                model,
                ollamaBaseUrl,
                maxOutputTokens,
                timeout,
                maxRetries,
                secretStatus,
                systemInstructions,
                enabled);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
