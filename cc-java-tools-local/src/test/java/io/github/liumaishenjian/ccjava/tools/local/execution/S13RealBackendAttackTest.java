package io.github.liumaishenjian.ccjava.tools.local.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.execution.ExecutionBackend;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.execution.CapabilityStatus;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference;
import io.github.liumaishenjian.ccjava.tools.local.LocalWorkspaceBootstrap;
import io.github.liumaishenjian.ccjava.tools.local.tool.RunCommandTool;
import io.github.liumaishenjian.ccjava.domain.execution.EnforcementDimension;
import io.github.liumaishenjian.ccjava.domain.execution.EnvironmentPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendId;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionRequest;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell;
import io.github.liumaishenjian.ccjava.domain.execution.FileAccessPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.NetworkPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.PlatformCapabilitySnapshot;
import io.github.liumaishenjian.ccjava.domain.execution.PolicyProvenance;
import io.github.liumaishenjian.ccjava.domain.execution.ProcessPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.SecretPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "CC_JAVA_S13_REAL_BACKENDS", matches = "true")
class S13RealBackendAttackTest {
    private static final String IMAGE = ExecutionBackendFactory.PINNED_IMAGE;

    @TempDir
    Path temp;
    private Path workspace;
    private Path outside;
    private Path wsl;
    private Path docker;

    @BeforeEach
    void setup() throws Exception {
        workspace = Files.createDirectories(temp.resolve("fixture-workspace")).toRealPath();
        outside = Files.writeString(
                temp.resolve("outside-host-marker"),
                "outside-secret-marker",
                StandardCharsets.UTF_8).toRealPath();
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve(".git/config"), "protected-git-marker");
        Files.createDirectories(workspace.resolve(".cc-java"));
        Files.writeString(workspace.resolve(".cc-java/settings.json"), "protected-settings-marker");
        Files.createDirectories(workspace.resolve("config"));
        Files.writeString(
                workspace.resolve("config/provider.local.properties"),
                "protected-provider-marker");
        Files.writeString(workspace.resolve("ordinary.txt"), "ordinary");
        wsl = Path.of(
                System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32",
                "wsl.exe");
        docker = Path.of(
                System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"),
                "Docker", "Docker", "resources", "bin", "docker.exe");
    }

    @Test
    void truthfulProbeBindsIdentityAndKeepsNativeUnknowns() {
        var probe = new LocalPlatformCapabilityProbe(workspace, wsl, "Ubuntu", docker, IMAGE);
        PlatformCapabilitySnapshot wslResult = probe.probe(ExecutionBackendId.WSL2_BWRAP);
        PlatformCapabilitySnapshot dockerResult = probe.probe(ExecutionBackendId.DOCKER_CONTAINER);

        assertThat(wslResult).as(wslResult.reasonCode())
                .matches(PlatformCapabilitySnapshot::fullyEnforced);
        assertThat(dockerResult).as(dockerResult.reasonCode())
                .matches(PlatformCapabilitySnapshot::fullyEnforced);
        assertThat(wslResult.hostIdentity()).hasSize(64).doesNotContain(workspace.toString());
        assertThat(dockerResult.hostIdentity()).hasSize(64).doesNotContain(workspace.toString());
        PlatformCapabilitySnapshot windows = probe.probe(ExecutionBackendId.NATIVE_WINDOWS);
        assertThat(windows.dimensions().get(EnforcementDimension.FILE))
                .isEqualTo(CapabilityStatus.UNKNOWN);
        assertThat(windows.dimensions().get(EnforcementDimension.NETWORK))
                .isEqualTo(CapabilityStatus.UNKNOWN);
    }

    @Test
    void bwrapExposesOnlyWorkspaceAndMasksControlPlane() throws Exception {
        var backend = new WslBwrapExecutionBackend(workspace, wsl, "Ubuntu");
        String outsideLinux = new WindowsWslPathMapper(wsl, "Ubuntu").map(outside).linuxCanonical();
        String command = "test ! -e /etc/os-release"
                + " && test ! -e '" + shellQuote(outsideLinux) + "'"
                + " && ! (test -f .git/config && grep -q protected-git-marker .git/config)"
                + " && ! touch .git/escaped"
                + " && ! mkdir .git/escaped-dir"
                + " && ! (test -f .cc-java/settings.json && grep -q protected-settings-marker .cc-java/settings.json)"
                + " && ! (test -f config/provider.local.properties && grep -q protected-provider-marker config/provider.local.properties)"
                + " && printf changed > ordinary.txt"
                + " && test -r /proc/net/dev"
                + " && test $(grep -c ':' /proc/net/dev) -eq 1"
                + " && grep -q '^[[:space:]]*lo:' /proc/net/dev"
                + " && test -z \"${OPENAI_API_KEY:-}\"";

        var outcome = backend.execute(request("bwrap-attack", command, Duration.ofSeconds(30)),
                CancellationToken.none(), (stream, chunk) -> { });

        assertThat(outcome.exitCode())
                .as(outcome.stdout() + "\n" + outcome.stderr())
                .isZero();
        assertThat(Files.readString(workspace.resolve("ordinary.txt"))).isEqualTo("changed");
        assertThat(workspace.resolve(".git/escaped")).doesNotExist();
        assertThat(Files.readString(outside)).isEqualTo("outside-secret-marker");
        assertThat(outcome.stdout() + outcome.stderr()).doesNotContain(
                "protected-git-marker",
                "protected-settings-marker",
                "protected-provider-marker",
                "outside-secret-marker");
    }

    @Test
    void dockerMasksControlPlaneDeniesNetworkAndLeavesNoContainer() throws Exception {
        var backend = new DockerExecutionBackend(workspace, docker, IMAGE);
        String callId = "docker-residue-attack";
        String command = "test ! -e /outside-host-marker"
                + " && ! (test -f .git/config && grep -q protected-git-marker .git/config)"
                + " && ! touch .git/escaped"
                + " && ! mkdir .git/escaped-dir"
                + " && ! (test -f .cc-java/settings.json && grep -q protected-settings-marker .cc-java/settings.json)"
                + " && ! (test -f config/provider.local.properties && grep -q protected-provider-marker config/provider.local.properties)"
                + " && printf changed > ordinary.txt"
                + " && test $(id -u) -ne 0"
                + " && ! touch /denied"
                + " && test -r /proc/net/dev"
                + " && test $(grep -c ':' /proc/net/dev) -eq 1"
                + " && grep -q '^[[:space:]]*lo:' /proc/net/dev"
                + " && test -z \"${OPENAI_API_KEY:-}\"";

        var outcome = backend.execute(request(callId, command, Duration.ofSeconds(30)),
                CancellationToken.none(), (stream, chunk) -> { });

        assertThat(outcome.exitCode())
                .as(outcome.stdout() + "\n" + outcome.stderr())
                .isZero();
        assertThat(workspace.resolve(".git/escaped")).doesNotExist();
        assertThat(Files.readString(workspace.resolve("ordinary.txt"))).isEqualTo("changed");
        assertThat(containerCount()).isZero();
    }

    @Test
    void dockerTimeoutCleansContainerAndDescendants() throws Exception {
        var backend = new DockerExecutionBackend(workspace, docker, IMAGE);

        var outcome = backend.execute(
                request("docker-timeout", "sleep 60 & wait", Duration.ofMillis(500)),
                CancellationToken.none(),
                (stream, chunk) -> { });

        assertThat(outcome.timedOut()).isTrue();
        assertThat(containerCount()).isZero();
    }

    @Test
    void cancellationAndWslTimeoutLeaveNoHostMarker() throws Exception {
        var backend = new WslBwrapExecutionBackend(workspace, wsl, "Ubuntu");
        Path timeoutMarker = workspace.resolve("timeout-orphan-marker");
        var timedOut = backend.execute(
                request(
                        "wsl-timeout",
                        "(sleep 2; touch timeout-orphan-marker) & wait",
                        Duration.ofMillis(300)),
                CancellationToken.none(),
                (stream, chunk) -> { });
        assertThat(timedOut.timedOut()).isTrue();
        Thread.sleep(2_500);
        assertThat(timeoutMarker).doesNotExist();

        var cancellation = new CancellationSource();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            cancellation.cancel();
        });
        Path cancelMarker = workspace.resolve("cancel-orphan-marker");
        var cancelled = backend.execute(
                request(
                        "wsl-cancel",
                        "(sleep 2; touch cancel-orphan-marker) & wait",
                        Duration.ofSeconds(10)),
                cancellation.token(),
                (stream, chunk) -> { });
        assertThat(cancelled.cancelled()).isTrue();
        Thread.sleep(2_500);
        assertThat(cancelMarker).doesNotExist();
    }

    @Test
    void dockerPidsLimitRejectsProcessFanoutAndCleansContainer() throws Exception {
        var backend = new DockerExecutionBackend(workspace, docker, IMAGE);
        String command = "seq 1 200 | xargs -P 100 -n 1 /bin/sh -c 'sleep 0.1'; "
                + "code=$?; if [ $code -ne 0 ]; then printf PIDS_BLOCKED; exit 23; fi; exit 0";

        var outcome = backend.execute(
                request("docker-pids-limit", command, Duration.ofSeconds(20)),
                CancellationToken.none(),
                (stream, chunk) -> { });

        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.cancelled()).isFalse();
        assertThat(outcome.exitCode()).isEqualTo(23);
        assertThat(outcome.stdout()).contains("PIDS_BLOCKED");
        assertThat(containerCount()).isZero();
        assertThat(runWithoutPidsLimit(command)).isZero();
    }

    @Test
    void productionBootstrapRunsSandboxAndContainerThroughRunCommandTool() throws Exception {
        for (ExecutionBackendPreference preference : List.of(
                ExecutionBackendPreference.SANDBOX,
                ExecutionBackendPreference.CONTAINER)) {
            LocalWorkspaceBootstrap bootstrap = LocalWorkspaceBootstrap.open(
                    workspace,
                    ExecutionBackendFactory.create(workspace, preference),
                    ExecutionShell.LINUX_SH);
            RunCommandTool tool = (RunCommandTool) bootstrap.tools().stream()
                    .filter(candidate -> candidate.definition().name().equals("run_command"))
                    .findFirst()
                    .orElseThrow();
            String callId = "production-" + preference.name().toLowerCase();
            ToolCall call = new ToolCall(
                    callId,
                    "run_command",
                    new JsonObject(Map.of(
                            "command", "printf PRODUCTION_COMPOSITION_OK",
                            "timeoutSeconds", 30)));
            var outcome = tool.execute(new io.github.liumaishenjian.ccjava.core.ToolInvocation(
                    new SessionId("session-s13-production"),
                    new RunId("run-s13-production"),
                    1,
                    call));

            assertThat(outcome.successful()).isTrue();
            assertThat(outcome.content()).contains("PRODUCTION_COMPOSITION_OK");
            assertThat(outcome.content()).contains(preference == ExecutionBackendPreference.SANDBOX
                    ? "WSL2_BWRAP"
                    : "DOCKER_CONTAINER");
        }
        assertThat(containerCount()).isZero();
    }

    @Test
    void unsupportedPolicyFailsBeforeBackendStarts() {
        AtomicInteger starts = new AtomicInteger();
        ExecutionBackend backend = new LocalExecutionBackend(workspace);
        ExecutionRequest unsupported = new ExecutionRequest(
                "unsupported",
                platformShell(),
                "",
                List.of(platformShell() == ExecutionShell.WINDOWS_PLATFORM ? "exit 0" : "true"),
                workspace.toString(),
                Duration.ofSeconds(2),
                1024,
                new ExecutionPolicy(
                        new FileAccessPolicy(
                                List.of(workspace.toString(), outside.toString()),
                                List.of(workspace.toString()),
                                ExecutionPolicyCompiler.PROTECTED_PATHS),
                        ProcessPolicy.restricted(),
                        new NetworkPolicy(false, List.of("tcp://127.0.0.1:9")),
                        new EnvironmentPolicy(Map.of("UNSAFE", "value")),
                        SecretPolicy.common(),
                        false,
                        List.of(new PolicyProvenance(PolicyProvenance.Kind.HOST, "test"))));

        assertThatThrownBy(() -> backend.execute(
                unsupported,
                CancellationToken.none(),
                (stream, chunk) -> starts.incrementAndGet()))
                .isInstanceOf(IOException.class);
        assertThat(starts).hasValue(0);
    }

    private ExecutionRequest request(String callId, String command, Duration timeout) {
        var policy = new ExecutionPolicy(
                new FileAccessPolicy(
                        List.of(workspace.toString()),
                        List.of(workspace.toString()),
                        ExecutionPolicyCompiler.PROTECTED_PATHS),
                ProcessPolicy.restricted(),
                NetworkPolicy.denyAllNetwork(),
                EnvironmentPolicy.empty(),
                SecretPolicy.common(),
                true,
                List.of(new PolicyProvenance(PolicyProvenance.Kind.HOST, "s13-real")));
        return new ExecutionRequest(
                callId,
                ExecutionShell.LINUX_SH,
                "",
                List.of(command),
                workspace.toString(),
                timeout,
                8_192,
                policy);
    }

    private int runWithoutPidsLimit(String command) throws Exception {
        Process process = new ProcessBuilder(
                docker.toString(),
                "run", "--rm",
                IMAGE,
                "/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        process.getInputStream().readAllBytes();
        return process.exitValue();
    }

    private long containerCount() throws Exception {
        Process process = new ProcessBuilder(
                docker.toString(),
                "ps", "--all", "--quiet",
                "--filter", "label=io.github.liumaishenjian.ccjava.owner=s13")
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .lines().filter(line -> !line.isBlank()).count();
    }

    private static String shellQuote(String value) {
        return value.replace("'", "'\\''");
    }

    private static ExecutionShell platformShell() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? ExecutionShell.WINDOWS_PLATFORM
                : ExecutionShell.POSIX_PLATFORM;
    }
}
