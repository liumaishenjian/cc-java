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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 通过 fixed argv Docker CLI 使用固定 digest 镜像的可选 Container 后端。
 *
 * <p>容器使用 fixed name/label、network none、只读根、非 root、cap-drop all 与
 * no-new-privileges。Workspace 是唯一宿主 bind；已有控制面路径以 {@code /dev/null} 或
 * 独立空 volume 覆盖。命令终态后显式执行 stop/kill/rm，{@code --rm} 只是额外兜底。</p>
 *
 * @since 0.13.0
 */
public final class DockerExecutionBackend extends AbstractProcessExecutionBackend {
    private static final String WORKSPACE_TARGET = "/workspace";
    private static final String CONTAINER_PREFIX = "cc-java-s13-";

    private final Path workspace;
    private final Path docker;
    private final String image;

    /** 创建绑定固定 Docker executable、Workspace 与 image digest 的后端。 */
    public DockerExecutionBackend(Path workspace, Path docker, String pinnedImageDigest) {
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.docker = Objects.requireNonNull(docker, "docker 不能为空");
        this.image = Objects.requireNonNull(pinnedImageDigest, "image 不能为空");
        if (!image.contains("@sha256:")) {
            throw new IllegalArgumentException("镜像必须固定 digest");
        }
    }

    @Override
    public ExecutionBackendId id() {
        return ExecutionBackendId.DOCKER_CONTAINER;
    }

    @Override
    protected Plan plan(ExecutionRequest request) throws IOException {
        if (request.shell() != ExecutionShell.LINUX_SH) {
            throw new IOException("Container 只接受显式 LINUX_SH");
        }
        if (request.arguments().size() != 1) {
            throw new IOException("LINUX_SH 请求必须有一个正文");
        }

        var policy = ExecutionPolicyCompiler.compile(request.policy(), workspace);
        String containerName = CONTAINER_PREFIX + shortIdentity(request.callId());
        List<String> argv = new ArrayList<>(List.of(
                docker.toString(),
                "run",
                "--rm",
                "--name", containerName,
                "--label", "io.github.liumaishenjian.ccjava.owner=s13",
                "--network", "none",
                "--read-only",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--user", "65534:65534",
                "--pids-limit", "128",
                "--mount", "type=bind,source=" + policy.workspace() + ",target=" + WORKSPACE_TARGET,
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                "--workdir", WORKSPACE_TARGET));
        addProtectedMasks(argv, policy.workspace());
        for (Map.Entry<String, String> entry : policy.environment().entrySet()) {
            argv.addAll(List.of("--env", entry.getKey() + "=" + entry.getValue()));
        }
        argv.addAll(List.of(image, "/bin/sh", "-c", request.arguments().getFirst()));
        return new Plan(
                argv,
                policy.workspace(),
                Map.of(),
                new byte[0],
                Optional.of(containerName));
    }

    private static void addProtectedMasks(List<String> argv, Path canonicalWorkspace)
            throws IOException {
        for (String protectedPath : ExecutionPolicyCompiler.PROTECTED_PATHS) {
            Path host = canonicalWorkspace.resolve(protectedPath).normalize();
            if (!host.startsWith(canonicalWorkspace)
                    || !Files.exists(host, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String target = WORKSPACE_TARGET + "/" + protectedPath.replace('\\', '/');
            if (Files.isDirectory(host, LinkOption.NOFOLLOW_LINKS)) {
                argv.addAll(List.of(
                        "--mount",
                        "type=tmpfs,target=" + target + ",readonly,tmpfs-size=4096"));
            } else if (Files.isRegularFile(host, LinkOption.NOFOLLOW_LINKS)) {
                argv.addAll(List.of(
                        "--mount",
                        "type=bind,source=/dev/null,target=" + target + ",readonly"));
            } else {
                throw new IOException("PROTECTED_PATH_TYPE_UNSUPPORTED");
            }
        }
    }

    @Override
    protected void cleanup(Plan plan) {
        plan.cleanupIdentity().ifPresent(name -> {
            runCleanup("stop", "--time", "1", name);
            runCleanup("kill", name);
            runCleanup("rm", "--force", name);
        });
    }

    private void runCleanup(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(docker.toString());
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String shortIdentity(String callId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(callId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
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
                "DOCKER_CONTAINER_ENFORCED");
    }
}
