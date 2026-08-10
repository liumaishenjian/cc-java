package io.github.liumaishenjian.ccjava.tools.local.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.execution.ExecutionBackend;
import io.github.liumaishenjian.ccjava.core.execution.ExecutionBackendSelector;
import io.github.liumaishenjian.ccjava.core.execution.ExecutionFallbackApprover;
import io.github.liumaishenjian.ccjava.domain.execution.CapabilityStatus;
import io.github.liumaishenjian.ccjava.domain.execution.EnforcementDimension;
import io.github.liumaishenjian.ccjava.domain.execution.EnforcementReport;
import io.github.liumaishenjian.ccjava.domain.execution.EnvironmentPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendId;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionFallbackDecision;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionOutcome;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionBackendSelectorTest {
    @TempDir
    Path workspace;

    @Test
    void windowsShellNeverImplicitlyChangesToLinux() {
        var local = new LocalExecutionBackend(workspace);
        var selector = new ExecutionBackendSelector(
                List.of(local),
                id -> snapshot(id, CapabilityStatus.ENFORCED, "stable"),
                ExecutionFallbackApprover.denyAll());

        assertThat(selector.select(
                request(ExecutionShell.WINDOWS_PLATFORM, true),
                ExecutionBackendId.WSL2_BWRAP).backend()).isEmpty();
    }

    @Test
    void requireIsolationNeverFallsBack() {
        var local = new LocalExecutionBackend(workspace);
        var selector = new ExecutionBackendSelector(
                List.of(local),
                id -> snapshot(id, CapabilityStatus.UNAVAILABLE, "stable"),
                (request, snapshot) -> new ExecutionFallbackDecision(
                        request.callId(), true, "explicit"));

        assertThat(selector.select(
                request(ExecutionShell.LINUX_SH, true),
                ExecutionBackendId.WSL2_BWRAP).backend()).isEmpty();
    }

    @Test
    void fallbackMustMatchCurrentCallId() {
        var local = new LocalExecutionBackend(workspace);
        var selector = new ExecutionBackendSelector(
                List.of(local),
                id -> snapshot(id, CapabilityStatus.UNAVAILABLE, "stable"),
                (request, snapshot) -> new ExecutionFallbackDecision(
                        "other", true, "explicit"));

        assertThat(selector.select(
                request(ExecutionShell.LINUX_SH, false),
                ExecutionBackendId.WSL2_BWRAP).backend()).isEmpty();
    }

    @Test
    void localIsTruthfullyUnsandboxed() throws Exception {
        var backend = new LocalExecutionBackend(workspace);

        var outcome = backend.execute(
                request(ExecutionShell.WINDOWS_PLATFORM, false),
                CancellationToken.none(),
                (stream, chunk) -> { });

        assertThat(outcome.enforcement().reasonCode()).isEqualTo("UNSANDBOXED_LOCAL");
        assertThat(outcome.enforcement().dimensions()).allSatisfy(
                (dimension, status) -> assertThat(status)
                        .isEqualTo(CapabilityStatus.DEGRADED));
    }

    @Test
    void identityDriftRejectsBeforeDelegateExecution() {
        AtomicInteger probeCount = new AtomicInteger();
        AtomicInteger executeCount = new AtomicInteger();
        ExecutionBackend delegate = new CountingBackend(executeCount);
        var selector = new ExecutionBackendSelector(
                List.of(delegate),
                id -> snapshot(
                        id,
                        CapabilityStatus.ENFORCED,
                        probeCount.incrementAndGet() == 1 ? "before" : "after"),
                ExecutionFallbackApprover.denyAll());
        var selection = selector.select(
                request(ExecutionShell.LINUX_SH, true),
                ExecutionBackendId.WSL2_BWRAP);

        assertThatThrownBy(() -> selection.backend().orElseThrow().execute(
                request(ExecutionShell.LINUX_SH, true),
                CancellationToken.none(),
                (stream, chunk) -> { }))
                .isInstanceOf(IOException.class)
                .hasMessage("BACKEND_IDENTITY_DRIFT");
        assertThat(executeCount).hasValue(0);
    }

    private ExecutionRequest request(ExecutionShell shell, boolean required) {
        var policy = new ExecutionPolicy(
                new FileAccessPolicy(
                        List.of(workspace.toString()),
                        List.of(workspace.toString()),
                        ExecutionPolicyCompiler.PROTECTED_PATHS),
                ProcessPolicy.restricted(),
                NetworkPolicy.denyAllNetwork(),
                EnvironmentPolicy.empty(),
                SecretPolicy.common(),
                required,
                List.of(new PolicyProvenance(PolicyProvenance.Kind.HOST, "test")));
        String command = System.getProperty("os.name").toLowerCase().contains("win")
                ? "exit 0"
                : "true";
        return new ExecutionRequest(
                "call-1",
                shell,
                "",
                List.of(command),
                workspace.toString(),
                Duration.ofSeconds(2),
                1024,
                policy);
    }

    private static PlatformCapabilitySnapshot snapshot(
            ExecutionBackendId backend,
            CapabilityStatus status,
            String identity) {
        var dimensions = new EnumMap<EnforcementDimension, CapabilityStatus>(
                EnforcementDimension.class);
        for (EnforcementDimension dimension : EnforcementDimension.values()) {
            dimensions.put(dimension, status);
        }
        return new PlatformCapabilitySnapshot(backend, dimensions, identity, "TEST");
    }

    private record CountingBackend(AtomicInteger executions) implements ExecutionBackend {
        @Override
        public ExecutionBackendId id() {
            return ExecutionBackendId.WSL2_BWRAP;
        }

        @Override
        public ExecutionOutcome execute(
                ExecutionRequest request,
                CancellationToken cancellation,
                io.github.liumaishenjian.ccjava.core.ToolOutputSink outputSink) {
            executions.incrementAndGet();
            return new ExecutionOutcome(
                    0,
                    false,
                    false,
                    "",
                    "",
                    false,
                    0,
                    new EnforcementReport(
                            id(),
                            snapshot(id(), CapabilityStatus.ENFORCED, "delegate").dimensions(),
                            false,
                            "TEST"),
                    Optional.empty());
        }
    }
}
