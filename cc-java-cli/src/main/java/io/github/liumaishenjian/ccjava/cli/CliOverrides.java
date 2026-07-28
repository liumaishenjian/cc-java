package io.github.liumaishenjian.ccjava.cli;

import java.nio.file.Path;
import java.net.URI;

/**
 * 保存 Picocli 已完成语法解析、但尚未与环境和默认值合并的覆盖项。
 *
 * @param workspace     用户通过 CLI 指定的 Workspace
 * @param model         用户通过 CLI 指定的模型名
 * @param ollamaBaseUrl 用户通过 CLI 指定的 Ollama 根地址
 * @param maxOutputTokens 用户通过 CLI 指定的单回合输出上限
 * @param timeoutSeconds 用户通过 CLI 指定的 Run 超时秒数
 * @param maxRetries    用户通过 CLI 指定的最大重试次数
 * @param noColor       是否强制禁用 ANSI
 * @since 0.1.0
 */
public record CliOverrides(
        Path workspace,
        String model,
        URI ollamaBaseUrl,
        Integer maxOutputTokens,
        Integer timeoutSeconds,
        Integer maxRetries,
        boolean noColor) {

    /**
     * 创建不覆盖 Provider 连接与输出上限的兼容输入。
     *
     * @param workspace 用户指定的 Workspace
     * @param model 用户指定的模型
     * @param timeoutSeconds Run 超时秒数
     * @param maxRetries 模型重试次数
     * @param noColor 是否禁用 ANSI
     */
    public CliOverrides(
            Path workspace,
            String model,
            Integer timeoutSeconds,
            Integer maxRetries,
            boolean noColor) {
        this(
                workspace,
                model,
                null,
                null,
                timeoutSeconds,
                maxRetries,
                noColor);
    }
}
