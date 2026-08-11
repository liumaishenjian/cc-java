package io.github.liumaishenjian.ccjava.tools.local.execution;

import io.github.liumaishenjian.ccjava.core.execution.PlatformCapabilityProbe;
import io.github.liumaishenjian.ccjava.domain.execution.CapabilityStatus;
import io.github.liumaishenjian.ccjava.domain.execution.EnforcementDimension;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendId;
import io.github.liumaishenjian.ccjava.domain.execution.PlatformCapabilitySnapshot;
import io.github.liumaishenjian.ccjava.tools.local.command.ProcessTreeTerminator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 使用 fixed executable 与真实 smoke 探测 Local、WSL2+bwrap、Docker 和 native 平台能力。
 *
 * <p>probe identity 绑定 OS/arch、helper 文件身份、distribution、固定 image digest 与 probe
 * contract version；只输出 SHA-256 摘要和固定原因码。probe timeout 会清理完整进程树。
 * 快照只能证明当前 helper identity，执行前仍须由 Composition Root 重探测并比对。</p>
 *
 * @since 0.13.0
 */
public final class LocalPlatformCapabilityProbe implements PlatformCapabilityProbe {
    private static final String PROBE_VERSION = "s13-probe-v2";
    private static final long PROBE_TIMEOUT_SECONDS = 30;

    private final Path workspace;
    private final Path wsl;
    private final Path docker;
    private final String distribution;
    private final String image;

    /**
     * 创建绑定当前 Workspace 与可信 helper 身份的能力探针。
     *
     * @param workspace 被探测后端将使用的工作目录
     * @param wsl 固定的 WSL executable 路径
     * @param distribution 固定的 WSL distribution 名称
     * @param docker 固定的 Docker executable 路径
     * @param pinnedImage 带 digest 的固定容器镜像；不可用时可为空
     */
    public LocalPlatformCapabilityProbe(
            Path workspace,
            Path wsl,
            String distribution,
            Path docker,
            String pinnedImage) {
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.wsl = Objects.requireNonNull(wsl, "wsl 不能为空");
        this.distribution = Objects.requireNonNull(distribution, "distribution 不能为空");
        this.docker = Objects.requireNonNull(docker, "docker 不能为空");
        this.image = pinnedImage;
    }

    @Override
    public PlatformCapabilitySnapshot probe(ExecutionBackendId backend) {
        try {
            return switch (backend) {
                case LOCAL -> local();
                case WSL2_BWRAP -> wsl();
                case DOCKER_CONTAINER -> docker();
                case NATIVE_WINDOWS -> nativeWindows();
                case MACOS_SANDBOX -> unavailable(backend, "MACOS_NOT_PROBED");
            };
        } catch (Exception failure) {
            return unavailable(backend, "PROBE_FAILED");
        }
    }

    private PlatformCapabilitySnapshot local() throws IOException {
        return new PlatformCapabilitySnapshot(
                ExecutionBackendId.LOCAL,
                status(CapabilityStatus.DEGRADED),
                identity(ExecutionBackendId.LOCAL),
                "UNSANDBOXED_LOCAL");
    }

    private PlatformCapabilitySnapshot wsl() throws Exception {
        if (!Files.isRegularFile(wsl)
                || !run(List.of(
                        wsl.toString(),
                        "--distribution", distribution,
                        "--exec", "/bin/sh", "-c",
                        "test -x /usr/bin/bwrap && grep -qi microsoft /proc/sys/kernel/osrelease"))) {
            return unavailable(ExecutionBackendId.WSL2_BWRAP, "WSL_OR_BWRAP_UNAVAILABLE");
        }
        new WindowsWslPathMapper(wsl, distribution).map(workspace);
        if (!run(List.of(
                wsl.toString(),
                "--distribution", distribution,
                "--exec", "/usr/bin/bwrap",
                "--unshare-user",
                "--unshare-pid",
                "--unshare-net",
                "--die-with-parent",
                "--ro-bind", "/usr", "/usr",
                "--symlink", "usr/bin", "/bin",
                "--symlink", "usr/sbin", "/sbin",
                "--symlink", "usr/lib", "/lib",
                "--symlink", "usr/lib64", "/lib64",
                "--tmpfs", "/tmp",
                "--proc", "/proc",
                "--dev", "/dev",
                "/usr/bin/env", "-i", "PATH=/usr/bin:/bin",
                "/bin/sh", "-c",
                "test ! -e /etc/os-release && touch /tmp/ok"
                        + " && test -r /proc/net/dev"
                        + " && test $(grep -c ':' /proc/net/dev) -eq 1"
                        + " && grep -q '^[[:space:]]*lo:' /proc/net/dev"))) {
            return unavailable(ExecutionBackendId.WSL2_BWRAP, "BWRAP_SELF_TEST_FAILED");
        }
        return new PlatformCapabilitySnapshot(
                ExecutionBackendId.WSL2_BWRAP,
                status(CapabilityStatus.ENFORCED),
                identity(ExecutionBackendId.WSL2_BWRAP),
                "WSL2_BWRAP_SELF_TEST_OK");
    }

    private PlatformCapabilitySnapshot docker() throws Exception {
        if (image == null
                || !image.contains("@sha256:")
                || !Files.isRegularFile(docker)) {
            return unavailable(ExecutionBackendId.DOCKER_CONTAINER, "DOCKER_CONFIG_UNAVAILABLE");
        }
        if (!run(List.of(docker.toString(), "image", "inspect", image))) {
            return unavailable(
                    ExecutionBackendId.DOCKER_CONTAINER,
                    "DOCKER_DAEMON_OR_IMAGE_UNAVAILABLE");
        }
        if (!run(List.of(
                docker.toString(),
                "run", "--rm",
                "--network", "none",
                "--read-only",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--user", "65534:65534",
                image,
                "/bin/sh", "-c",
                "test $(id -u) -ne 0 && ! touch /denied"
                        + " && test -r /proc/net/dev"
                        + " && test $(grep -c ':' /proc/net/dev) -eq 1"
                        + " && grep -q '^[[:space:]]*lo:' /proc/net/dev"))) {
            return unavailable(ExecutionBackendId.DOCKER_CONTAINER, "DOCKER_SELF_TEST_FAILED");
        }
        return new PlatformCapabilitySnapshot(
                ExecutionBackendId.DOCKER_CONTAINER,
                status(CapabilityStatus.ENFORCED),
                identity(ExecutionBackendId.DOCKER_CONTAINER),
                "DOCKER_SELF_TEST_OK");
    }

    private PlatformCapabilitySnapshot nativeWindows() throws IOException {
        var dimensions = status(CapabilityStatus.UNKNOWN);
        dimensions.put(EnforcementDimension.PROCESS, CapabilityStatus.DEGRADED);
        dimensions.put(EnforcementDimension.ENVIRONMENT, CapabilityStatus.ENFORCED);
        dimensions.put(EnforcementDimension.SECRET, CapabilityStatus.ENFORCED);
        return new PlatformCapabilitySnapshot(
                ExecutionBackendId.NATIVE_WINDOWS,
                dimensions,
                identity(ExecutionBackendId.NATIVE_WINDOWS),
                "WINDOWS_NATIVE_PROCESS_ENV_B");
    }

    private PlatformCapabilitySnapshot unavailable(
            ExecutionBackendId backend,
            String reason) {
        String identity;
        try {
            identity = identity(backend);
        } catch (IOException failure) {
            identity = digest(List.of(PROBE_VERSION, backend.name(), "identity-unavailable"));
        }
        return new PlatformCapabilitySnapshot(
                backend,
                status(CapabilityStatus.UNAVAILABLE),
                identity,
                reason);
    }

    private String identity(ExecutionBackendId backend) throws IOException {
        List<String> parts = new ArrayList<>(List.of(
                PROBE_VERSION,
                backend.name(),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown")));
        switch (backend) {
            case WSL2_BWRAP -> {
                parts.add(fileIdentity(wsl));
                parts.add(distribution);
                parts.add(capture(List.of(
                        wsl.toString(),
                        "--distribution", distribution,
                        "--exec", "/bin/sh", "-c",
                        "uname -r; /usr/bin/bwrap --version"), 4_096));
            }
            case DOCKER_CONTAINER -> {
                parts.add(fileIdentity(docker));
                parts.add(Objects.toString(image, "missing-image"));
                parts.add(capture(List.of(
                        docker.toString(),
                        "version", "--format",
                        "{{.Server.Version}}|{{.Server.Os}}|{{.Server.Arch}}"), 4_096));
                parts.add(capture(List.of(
                        docker.toString(),
                        "image", "inspect", image,
                        "--format", "{{.Id}}|{{json .RepoDigests}}"), 8_192));
            }
            case LOCAL, NATIVE_WINDOWS, MACOS_SANDBOX -> {
                // OS/arch 和 probe contract 已足以标识不使用外部 helper 的路径。
            }
        }
        return digest(parts);
    }

    private static String fileIdentity(Path executable) throws IOException {
        if (!Files.isRegularFile(executable)) {
            return "missing";
        }
        Path canonical = executable.toRealPath();
        return canonical + ":" + Files.size(canonical) + ":"
                + Files.getLastModifiedTime(canonical).toMillis();
    }

    private static String digest(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static EnumMap<EnforcementDimension, CapabilityStatus> status(
            CapabilityStatus status) {
        var dimensions = new EnumMap<EnforcementDimension, CapabilityStatus>(
                EnforcementDimension.class);
        for (EnforcementDimension dimension : EnforcementDimension.values()) {
            dimensions.put(dimension, status);
        }
        return dimensions;
    }

    private static String capture(List<String> arguments, int limit) throws IOException {
        Process process = new ProcessBuilder(arguments)
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                new ProcessTreeTerminator().terminate(process);
                throw new IOException("IDENTITY_PROBE_TIMED_OUT");
            }
            byte[] output = process.getInputStream().readNBytes(limit + 1);
            if (process.exitValue() != 0 || output.length > limit) {
                throw new IOException("IDENTITY_PROBE_FAILED");
            }
            return new String(output, StandardCharsets.UTF_8).strip();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            new ProcessTreeTerminator().terminate(process);
            throw new IOException("IDENTITY_PROBE_INTERRUPTED", interrupted);
        }
    }

    private static boolean run(List<String> arguments) throws IOException {
        Process process = new ProcessBuilder(arguments)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                new ProcessTreeTerminator().terminate(process);
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            new ProcessTreeTerminator().terminate(process);
            return false;
        }
    }
}
