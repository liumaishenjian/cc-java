package io.github.liumaishenjian.ccjava.cli.runtime;

import java.nio.file.Path;
import java.time.Duration;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.cli.session.SessionStorage;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PermissionRule;
import java.util.List;
import java.util.Objects;

/**
 * Headless Runtime 创建 Session 与 Run 所需的非 Secret 配置。
 *
 * <p>Workspace、模型名、墙钟限制和 Permission Mode 在 Composition Root 固定；
 * Startup Rule 由可信应用代码显式注入，不从 Workspace 内容或模型参数加载。</p>
 *
 * @param workspace 已解析的真实 Workspace 目录
 * @param model Provider 模型标识
 * @param timeout 每个 Run 的墙钟限制
 * @param permissionMode 当前 S05 Permission Mode
 * @param startupPermissionRules 可信启动规则
 * @param sessionOpenRequest S06 Session 选择
 * @param sessionStoreRoot Workspace 外的本机私有 Store root
 * @since 0.1.0
 */
public record HeadlessRuntimeOptions(
        Path workspace,
        String model,
        Duration timeout,
        PermissionMode permissionMode,
        List<PermissionRule> startupPermissionRules,
        SessionOpenRequest sessionOpenRequest,
        Path sessionStoreRoot) {

    /**
     * 使用 DEFAULT 且无 Startup Rule 创建兼容 S04 调用方的配置。
     *
     * @param workspace 已解析的真实 Workspace 目录
     * @param model Provider 模型标识
     * @param timeout 每个 Run 的墙钟限制
     */
    public HeadlessRuntimeOptions(Path workspace, String model, Duration timeout) {
        this(
                workspace,
                model,
                timeout,
                PermissionMode.DEFAULT,
                List.of(),
                SessionOpenRequest.create(),
                SessionStorage.defaultRoot());
    }

    /**
     * 使用默认 Create 选择和生产 Store root 创建 S05 兼容配置。
     *
     * @param workspace 已解析的真实 Workspace 目录
     * @param model Provider 模型标识
     * @param timeout 每个 Run 的墙钟限制
     * @param permissionMode 当前 Permission Mode
     * @param startupPermissionRules 可信启动规则
     */
    public HeadlessRuntimeOptions(
            Path workspace,
            String model,
            Duration timeout,
            PermissionMode permissionMode,
            List<PermissionRule> startupPermissionRules) {
        this(
                workspace,
                model,
                timeout,
                permissionMode,
                startupPermissionRules,
                SessionOpenRequest.create(),
                SessionStorage.defaultRoot());
    }

    /**
     * 规范化非 Secret Runtime 配置。
     *
     * @param workspace 已解析的真实 Workspace 目录
     * @param model Provider 模型标识
     * @param timeout 每个 Run 的墙钟限制
     * @param permissionMode 当前 Permission Mode
     * @param startupPermissionRules 可信启动规则
     * @param sessionOpenRequest Session 选择
     * @param sessionStoreRoot Workspace 外 Store root
     */
    public HeadlessRuntimeOptions {
        workspace = Objects.requireNonNull(workspace, "workspace 不能为空")
                .toAbsolutePath()
                .normalize();
        model = Objects.requireNonNull(model, "model 不能为空").trim();
        timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        permissionMode = Objects.requireNonNull(permissionMode, "permissionMode 不能为空");
        startupPermissionRules = List.copyOf(Objects.requireNonNull(
                startupPermissionRules, "startupPermissionRules 不能为空"));
        sessionOpenRequest = Objects.requireNonNull(
                sessionOpenRequest, "sessionOpenRequest 不能为空");
        sessionStoreRoot = Objects.requireNonNull(sessionStoreRoot, "sessionStoreRoot 不能为空")
                .toAbsolutePath()
                .normalize();
        if (model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空白");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
    }
}
