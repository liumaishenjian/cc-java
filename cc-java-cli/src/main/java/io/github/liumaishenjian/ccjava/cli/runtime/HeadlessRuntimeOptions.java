package io.github.liumaishenjian.ccjava.cli.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Headless Runtime 创建 Session 与 Run 所需的非 Secret 配置。
 *
 * <p>Workspace、模型名和墙钟限制会进入 Session Metadata，使 CLI Override
 * 可以通过事件和 Fake Model 请求追踪；它们不改变 Permission 或文件访问边界。</p>
 *
 * @param workspace 已解析的真实 Workspace 目录
 * @param model Provider 模型标识
 * @param timeout 每个 Run 的墙钟限制
 * @since 0.1.0
 */
public record HeadlessRuntimeOptions(
        Path workspace,
        String model,
        Duration timeout) {

    /**
     * 规范化非 Secret Runtime 配置。
     *
     * @param workspace 已解析的真实 Workspace 目录
     * @param model Provider 模型标识
     * @param timeout 每个 Run 的墙钟限制
     */
    public HeadlessRuntimeOptions {
        workspace = Objects.requireNonNull(workspace, "workspace 不能为空")
                .toAbsolutePath()
                .normalize();
        model = Objects.requireNonNull(model, "model 不能为空").trim();
        timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空白");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
    }
}
