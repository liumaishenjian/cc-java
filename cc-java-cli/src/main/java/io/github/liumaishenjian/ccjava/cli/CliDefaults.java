package io.github.liumaishenjian.ccjava.cli;

import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 描述由 Composition Root 提供的 Provider 与 Runtime 默认值。
 *
 * <p>该类型只保存 Provider 标识、非敏感默认值和 Secret 的环境变量名称，不保存
 * Secret 本身。模型名称故意没有默认值，必须由 CLI 或环境显式指定，避免下载或
 * 假定本机模型 Tag。</p>
 *
 * @param providerId                 Provider 的项目内稳定标识
 * @param apiKeyEnvironmentVariable  API Key 环境变量名称
 * @param apiKeyRequired             当前 Provider 是否必须提供 API Key
 * @param timeout                    单个 Run 的默认时间边界
 * @param maxRetries                 可重试模型错误的默认最大重试次数
 * @param workspace                  默认 Workspace
 * @param systemInstructions         Runtime 稳定系统指令
 * @param ollamaBaseUrl              默认 Ollama 根地址
 * @param maxOutputTokens            默认单回合输出 Token 上限
 * @since 0.1.0
 */
public record CliDefaults(
        String providerId,
        String apiKeyEnvironmentVariable,
        boolean apiKeyRequired,
        Duration timeout,
        int maxRetries,
        Path workspace,
        String systemInstructions,
        URI ollamaBaseUrl,
        int maxOutputTokens) {

    /**
     * 校验 Composition Root 提供的默认值。
     */
    public CliDefaults {
        providerId = requireText(providerId, "providerId");
        apiKeyEnvironmentVariable = requireText(
                apiKeyEnvironmentVariable,
                "apiKeyEnvironmentVariable");
        timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        workspace = Objects.requireNonNull(workspace, "workspace 不能为空")
                .toAbsolutePath()
                .normalize();
        systemInstructions = requireText(systemInstructions, "systemInstructions");
        ollamaBaseUrl = Objects.requireNonNull(ollamaBaseUrl, "ollamaBaseUrl 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能小于 0");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 1_000_000) {
            throw new IllegalArgumentException(
                    "maxOutputTokens 必须在 1 到 1000000 之间");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
