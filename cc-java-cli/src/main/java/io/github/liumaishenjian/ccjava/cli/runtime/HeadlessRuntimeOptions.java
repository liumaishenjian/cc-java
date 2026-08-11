package io.github.liumaishenjian.ccjava.cli.runtime;

import java.nio.file.Path;
import java.time.Duration;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.cli.session.SessionStorage;
import io.github.liumaishenjian.ccjava.core.ContextPreparationConfig;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PermissionRule;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 * @param contextPreparation S07 显式启动容量配置；空表示保持 Canonical no-op 路径
 * @param diagnosticMode 本机模型诊断模式
 * @param diagnosticDirectory 可选可信诊断目录
 * @param executionBackend 可信 Composition Root 显式选择的执行后端
 * @param executionShell 不得被后端隐式转换的 shell 语义
 * @since 0.1.0
 */
public record HeadlessRuntimeOptions(
        Path workspace,
        String model,
        Duration timeout,
        PermissionMode permissionMode,
        List<PermissionRule> startupPermissionRules,
        SessionOpenRequest sessionOpenRequest,
        Path sessionStoreRoot,
        Optional<ContextPreparationConfig> contextPreparation,
        ModelDiagnosticMode diagnosticMode,
        Optional<Path> diagnosticDirectory,
        ExecutionBackendPreference executionBackend,
        ExecutionShell executionShell) {

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
                SessionStorage.defaultRoot(),
                Optional.empty(),
                ModelDiagnosticMode.OFF,
                Optional.empty(),
                ExecutionBackendPreference.LOCAL,
                platformShell());
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
                SessionStorage.defaultRoot(),
                Optional.empty(),
                ModelDiagnosticMode.OFF,
                Optional.empty(),
                ExecutionBackendPreference.LOCAL,
                platformShell());
    }

    /**
     * 使用显式 Session Store 且不启用 S07 Projection 的兼容构造器。
     *
     * @param workspace 已解析的真实 Workspace 目录
     * @param model Provider 模型标识
     * @param timeout 每个 Run 的墙钟限制
     * @param permissionMode 当前 Permission Mode
     * @param startupPermissionRules 可信启动规则
     * @param sessionOpenRequest 创建、继续、恢复或分叉的 Session 选择
     * @param sessionStoreRoot Workspace 外的 Session Store 根目录
     */
    public HeadlessRuntimeOptions(
            Path workspace,
            String model,
            Duration timeout,
            PermissionMode permissionMode,
            List<PermissionRule> startupPermissionRules,
            SessionOpenRequest sessionOpenRequest,
            Path sessionStoreRoot) {
        this(
                workspace,
                model,
                timeout,
                permissionMode,
                startupPermissionRules,
                sessionOpenRequest,
                sessionStoreRoot,
                Optional.empty(),
                ModelDiagnosticMode.OFF,
                Optional.empty(),
                ExecutionBackendPreference.LOCAL,
                platformShell());
    }

    /**
     * 使用显式 S07 配置且保持诊断关闭的兼容构造器。
     *
     * @param workspace 已解析 Workspace
     * @param model Provider 模型标识
     * @param timeout Run 墙钟限制
     * @param permissionMode 权限模式
     * @param startupPermissionRules 启动规则
     * @param sessionOpenRequest Session 选择
     * @param sessionStoreRoot Session Store
     * @param contextPreparation Context 配置
     */
    public HeadlessRuntimeOptions(
            Path workspace,
            String model,
            Duration timeout,
            PermissionMode permissionMode,
            List<PermissionRule> startupPermissionRules,
            SessionOpenRequest sessionOpenRequest,
            Path sessionStoreRoot,
            Optional<ContextPreparationConfig> contextPreparation) {
        this(workspace, model, timeout, permissionMode, startupPermissionRules,
                sessionOpenRequest, sessionStoreRoot, contextPreparation,
                ModelDiagnosticMode.OFF, Optional.empty(),
                ExecutionBackendPreference.LOCAL, platformShell());
    }

    /**
     * 使用默认 Local/platform execution 配置的兼容构造器。
     *
     * @param workspace 已解析 Workspace
     * @param model 模型 identity
     * @param timeout Run deadline
     * @param permissionMode 权限模式
     * @param startupPermissionRules 可信启动规则
     * @param sessionOpenRequest Session 选择
     * @param sessionStoreRoot Session Store root
     * @param contextPreparation Context 容量配置
     * @param diagnosticMode 模型诊断模式
     * @param diagnosticDirectory 可选诊断目录
     */
    public HeadlessRuntimeOptions(
            Path workspace,
            String model,
            Duration timeout,
            PermissionMode permissionMode,
            List<PermissionRule> startupPermissionRules,
            SessionOpenRequest sessionOpenRequest,
            Path sessionStoreRoot,
            Optional<ContextPreparationConfig> contextPreparation,
            ModelDiagnosticMode diagnosticMode,
            Optional<Path> diagnosticDirectory) {
        this(
                workspace,
                model,
                timeout,
                permissionMode,
                startupPermissionRules,
                sessionOpenRequest,
                sessionStoreRoot,
                contextPreparation,
                diagnosticMode,
                diagnosticDirectory,
                ExecutionBackendPreference.LOCAL,
                platformShell());
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
     * @param contextPreparation 显式启动容量配置；不会从 Workspace 或模型名推断
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
        contextPreparation = Objects.requireNonNull(
                contextPreparation, "contextPreparation 不能为空");
        diagnosticMode = Objects.requireNonNull(diagnosticMode, "diagnosticMode 不能为空");
        diagnosticDirectory = Objects.requireNonNull(
                diagnosticDirectory, "diagnosticDirectory 不能为空")
                .map(path -> path.toAbsolutePath().normalize());
        executionBackend = Objects.requireNonNull(
                executionBackend, "executionBackend 不能为空");
        executionShell = Objects.requireNonNull(executionShell, "executionShell 不能为空");
        if (executionBackend != ExecutionBackendPreference.LOCAL
                && executionShell != ExecutionShell.LINUX_SH) {
            throw new IllegalArgumentException(
                    "Sandbox/Container 必须显式选择 LINUX_SH");
        }
        if (diagnosticMode == ModelDiagnosticMode.OFF && diagnosticDirectory.isPresent()) {
            throw new IllegalArgumentException("OFF 模式不能指定诊断目录");
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空白");
        }
        String selectedModel = model;
        contextPreparation = contextPreparation.map(config -> bindModel(config, selectedModel));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
    }

    private static ExecutionShell platformShell() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win")
                ? ExecutionShell.WINDOWS_PLATFORM
                : ExecutionShell.POSIX_PLATFORM;
    }

    private static ContextPreparationConfig bindModel(
            ContextPreparationConfig config,
            String model) {
        ContextCapacity source = Objects.requireNonNull(config, "config 不能为空").capacity();
        return new ContextPreparationConfig(
                new ContextCapacity(
                        model,
                        source.maximumInputTokens(),
                        source.reservedOutputTokens(),
                        source.safetyMarginTokens()),
                config.largePayloadTokenThreshold(),
                config.protectedMessageCount(),
                config.maxSummaryUtf8Bytes(),
                config.maxSummaryTokens());
    }
}
