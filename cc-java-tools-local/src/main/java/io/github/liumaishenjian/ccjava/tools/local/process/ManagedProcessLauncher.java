package io.github.liumaishenjian.ccjava.tools.local.process;

import io.github.liumaishenjian.ccjava.tools.local.command.ProcessTreeTerminator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 为可信 fixed-argv 扩展进程提供统一的最小环境、工作目录和进程树所有权边界。
 *
 * <p>该 seam 用于 Command Hook 与 MCP stdio 等宿主控制进程，不接受模型提供 executable，
 * 不经 Shell，也不声称 OS Sandbox。调用方必须以 {@link ManagedProcess#close()}、timeout 或
 * cancellation 结束所有权；三条路径都使用同一个 {@link ProcessTreeTerminator}。</p>
 *
 * @since 0.13.0
 */
public final class ManagedProcessLauncher {
    /**
     * 启动已经由可信 Settings 或 Trust Gate 固定的进程。
     *
     * @param request fixed-argv 启动计划
     * @return 由调用方独占的进程句柄
     * @throws IOException executable、工作目录或进程启动无效时
     */
    public ManagedProcess start(LaunchRequest request) throws IOException {
        LaunchRequest checked = Objects.requireNonNull(request, "request 不能为空");
        ProcessBuilder builder = processBuilder(checked);
        return new ManagedProcess(builder.start());
    }

    /**
     * 为必须由第三方 SDK 调用 {@code start()} 的适配器构造同一安全计划。
     *
     * @param request fixed-argv 请求
     * @return 环境已清空且 cwd 已固定的 Builder
     * @throws IOException executable 或 cwd 身份无效时
     */
    public ProcessBuilder processBuilder(LaunchRequest request) throws IOException {
        LaunchRequest checked = Objects.requireNonNull(request, "request 不能为空");
        Path executable = checked.executable().toRealPath();
        if (!Files.isRegularFile(executable)) {
            throw new IOException("MANAGED_EXECUTABLE_INVALID");
        }
        Path workspace = checked.workspace().toRealPath();
        if (!Files.isDirectory(workspace)) {
            throw new IOException("MANAGED_WORKSPACE_INVALID");
        }
        List<String> command = new java.util.ArrayList<>();
        command.add(executable.toString());
        command.addAll(checked.arguments());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().putAll(checked.environment());
        return builder;
    }

    /** fixed-argv 启动计划。 */
    public record LaunchRequest(
            Path executable,
            List<String> arguments,
            Path workspace,
            Map<String, String> environment) {
        public LaunchRequest {
            executable = Objects.requireNonNull(executable, "executable 不能为空");
            if (!executable.isAbsolute()) {
                throw new IllegalArgumentException("executable 必须是绝对路径");
            }
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments 不能为空"));
            workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
            environment = Map.copyOf(Objects.requireNonNull(environment, "environment 不能为空"));
            if (arguments.size() > 256
                    || arguments.stream().anyMatch(value -> value == null || value.indexOf('\0') >= 0)
                    || environment.entrySet().stream().anyMatch(entry ->
                            entry.getKey().isBlank()
                                    || entry.getKey().indexOf('=') >= 0
                                    || entry.getValue().indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("managed process 参数无效");
            }
        }
    }

    /** 由启动方独占、关闭时清理完整树的进程句柄。 */
    public static final class ManagedProcess implements AutoCloseable {
        private static final ProcessTreeTerminator TERMINATOR = new ProcessTreeTerminator();
        private final Process process;

        private ManagedProcess(Process process) {
            this.process = process;
        }

        public OutputStream stdin() {
            return process.getOutputStream();
        }

        public InputStream stdout() {
            return process.getInputStream();
        }

        public InputStream stderr() {
            return process.getErrorStream();
        }

        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return process.waitFor(timeout, unit);
        }

        public int exitCode() {
            return process.exitValue();
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public void destroyTree() {
            TERMINATOR.terminate(process);
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                destroyTree();
            }
            try {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            } catch (IOException ignored) {
                // 关闭已终止 pipe 是幂等清理。
            }
        }
    }
}
