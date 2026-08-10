package io.github.liumaishenjian.ccjava.tools.local.execution;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolOutputSink;
import io.github.liumaishenjian.ccjava.core.execution.ExecutionBackend;
import io.github.liumaishenjian.ccjava.core.execution.ExecutionBackendSelector;
import io.github.liumaishenjian.ccjava.core.execution.ExecutionFallbackApprover;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendId;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionOutcome;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 由可信 Composition Root 显式创建生产执行后端。
 *
 * <p>该工厂不读写全局 System properties。每个调用先用真实 probe 经
 * {@link ExecutionBackendSelector} 选择，再由 selector identity guard 在启动前重探测。
 * 当前生产 fallback 固定 deny-all；尚未接入 per-Call-ID 交互协议前不会降级到 Local。</p>
 *
 * @since 0.13.0
 */
public final class ExecutionBackendFactory {
    /** S13 经真实 daemon 验证的固定 image digest。 */
    public static final String PINNED_IMAGE =
            "nginx@sha256:0d17b565c37bcbd895e9d92315a05c1c3c9a29f762b011a10c54a66cd53c9b31";

    private ExecutionBackendFactory() {
    }

    /**
     * 创建显式 preference 的 probe-checked 后端。
     *
     * @param workspace 规范 Workspace
     * @param preference 可信启动配置
     * @return 每个 Call 重新选择并在启动前重检 identity 的后端
     * @throws NullPointerException Workspace 或 preference 为空时
     */
    public static ExecutionBackend create(
            Path workspace,
            ExecutionBackendPreference preference) {
        Objects.requireNonNull(workspace, "workspace 不能为空");
        Objects.requireNonNull(preference, "preference 不能为空");
        Path wsl = Path.of(
                System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32",
                "wsl.exe");
        Path docker = Path.of(
                System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"),
                "Docker",
                "Docker",
                "resources",
                "bin",
                "docker.exe");
        var local = new LocalExecutionBackend(workspace);
        var sandbox = new WslBwrapExecutionBackend(workspace, wsl, "Ubuntu");
        var container = new DockerExecutionBackend(workspace, docker, PINNED_IMAGE);
        var probe = new LocalPlatformCapabilityProbe(
                workspace,
                wsl,
                "Ubuntu",
                docker,
                PINNED_IMAGE);
        var selector = new ExecutionBackendSelector(
                List.of(local, sandbox, container),
                probe,
                ExecutionFallbackApprover.denyAll());
        ExecutionBackendId selected = switch (preference) {
            case LOCAL -> ExecutionBackendId.LOCAL;
            case SANDBOX -> ExecutionBackendId.WSL2_BWRAP;
            case CONTAINER -> ExecutionBackendId.DOCKER_CONTAINER;
        };
        return new SelectingBackend(selected, selector);
    }

    private record SelectingBackend(
            ExecutionBackendId id,
            ExecutionBackendSelector selector) implements ExecutionBackend {
        @Override
        public ExecutionOutcome execute(
                ExecutionRequest request,
                CancellationToken cancellation,
                ToolOutputSink outputSink) throws IOException {
            var selection = selector.select(request, id);
            if (selection.backend().isEmpty()) {
                throw new IOException(selection.reasonCode());
            }
            return selection.backend().orElseThrow().execute(
                    request,
                    cancellation,
                    outputSink);
        }
    }
}
