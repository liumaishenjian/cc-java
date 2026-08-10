package io.github.liumaishenjian.ccjava.tools.local.execution;

import io.github.liumaishenjian.ccjava.domain.execution.CapabilityStatus;
import io.github.liumaishenjian.ccjava.domain.execution.EnforcementDimension;
import io.github.liumaishenjian.ccjava.domain.execution.EnforcementReport;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendId;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionRequest;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 固定 Windows {@code wsl.exe → Ubuntu WSL2 → /usr/bin/bwrap} 的 Linux 隔离后端。
 *
 * <p>根文件系统从空视图构造，只挂载执行所需的 Linux runtime 目录以及映射到固定
 * {@code /workspace} 的当前 Workspace；不会把 WSL 根或其他 {@code /mnt} 主机盘带入
 * namespace。Workspace 控制面由空目录或空文件覆盖，因而既不能读取宿主内容也不能写回。
 * 后端只接受显式 {@link ExecutionShell#LINUX_SH}。</p>
 *
 * @since 0.13.0
 */
public final class WslBwrapExecutionBackend extends AbstractProcessExecutionBackend {
    private static final String SANDBOX_WORKSPACE = "/workspace";
    private static final List<String> RUNTIME_MOUNTS = List.of("/usr");
    private static final Map<String, String> RUNTIME_LINKS = Map.of(
            "/bin", "usr/bin",
            "/sbin", "usr/sbin",
            "/lib", "usr/lib",
            "/lib64", "usr/lib64");

    private final Path workspace;
    private final Path wsl;
    private final String distribution;
    private final WindowsWslPathMapper mapper;

    /** 创建绑定固定 WSL distribution 与 Workspace 的后端。 */
    public WslBwrapExecutionBackend(Path workspace, Path wsl, String distribution) {
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.wsl = Objects.requireNonNull(wsl, "wsl 不能为空");
        this.distribution = Objects.requireNonNull(distribution, "distribution 不能为空");
        this.mapper = new WindowsWslPathMapper(wsl, distribution);
    }

    @Override
    public ExecutionBackendId id() {
        return ExecutionBackendId.WSL2_BWRAP;
    }

    @Override
    protected Plan plan(ExecutionRequest request) throws IOException {
        if (request.shell() != ExecutionShell.LINUX_SH) {
            throw new IOException("WSL backend 只接受显式 LINUX_SH");
        }
        if (request.arguments().size() != 1) {
            throw new IOException("LINUX_SH 请求必须有一个正文");
        }

        var policy = ExecutionPolicyCompiler.compile(request.policy(), workspace);
        WindowsWslPathMapper.Mapping mapping = mapper.map(policy.workspace());
        List<String> argv = new ArrayList<>(List.of(
                wsl.toString(),
                "--distribution", distribution,
                "--exec", "/usr/bin/bwrap",
                "--unshare-user",
                "--unshare-pid",
                "--unshare-net",
                "--unshare-ipc",
                "--unshare-uts",
                "--die-with-parent",
                "--new-session"));

        for (String runtime : RUNTIME_MOUNTS) {
            if (linuxPathExists(runtime)) {
                argv.addAll(List.of("--ro-bind", runtime, runtime));
            }
        }
        for (Map.Entry<String, String> link : RUNTIME_LINKS.entrySet()) {
            argv.addAll(List.of("--symlink", link.getValue(), link.getKey()));
        }
        argv.addAll(List.of(
                "--bind", mapping.linuxCanonical(), SANDBOX_WORKSPACE,
                "--tmpfs", "/tmp",
                "--proc", "/proc",
                "--dev", "/dev"));
        addProtectedMasks(argv, policy.workspace());
        argv.addAll(List.of(
                "--chdir", SANDBOX_WORKSPACE,
                "/usr/bin/env", "-i",
                "HOME=/tmp",
                "PATH=/usr/bin:/bin"));
        for (Map.Entry<String, String> entry : policy.environment().entrySet()) {
            argv.add(entry.getKey() + "=" + entry.getValue());
        }
        argv.addAll(List.of("/bin/sh", "-c", request.arguments().getFirst()));
        return new Plan(argv, policy.workspace(), Map.of(), new byte[0]);
    }

    private void addProtectedMasks(List<String> argv, Path canonicalWorkspace) throws IOException {
        for (String protectedPath : ExecutionPolicyCompiler.PROTECTED_PATHS) {
            Path host = canonicalWorkspace.resolve(protectedPath).normalize();
            if (!host.startsWith(canonicalWorkspace)
                    || !Files.exists(host, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String target = SANDBOX_WORKSPACE + "/" + protectedPath.replace('\\', '/');
            if (Files.isDirectory(host, LinkOption.NOFOLLOW_LINKS)) {
                argv.addAll(List.of("--tmpfs", target, "--remount-ro", target));
            } else if (Files.isRegularFile(host, LinkOption.NOFOLLOW_LINKS)) {
                argv.addAll(List.of("--ro-bind", "/dev/null", target));
            } else {
                throw new IOException("PROTECTED_PATH_TYPE_UNSUPPORTED");
            }
        }
    }

    private boolean linuxPathExists(String path) throws IOException {
        List<String> command = List.of(
                wsl.toString(),
                "--distribution", distribution,
                "--exec", "/usr/bin/test", "-e", path);
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor() == 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("WSL_RUNTIME_CHECK_INTERRUPTED", interrupted);
        }
    }

    @Override
    protected EnforcementReport report(boolean fallback) {
        var dimensions = new EnumMap<EnforcementDimension, CapabilityStatus>(
                EnforcementDimension.class);
        for (EnforcementDimension dimension : EnforcementDimension.values()) {
            dimensions.put(dimension, CapabilityStatus.ENFORCED);
        }
        return new EnforcementReport(
                id(),
                dimensions,
                fallback,
                "WSL2_BWRAP_ENFORCED");
    }
}
