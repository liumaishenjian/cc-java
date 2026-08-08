package io.github.liumaishenjian.ccjava.core.hook;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.hook.HookDisposition;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionResult;
import io.github.liumaishenjian.ccjava.domain.hook.HookFailurePolicy;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import io.github.liumaishenjian.ccjava.domain.hook.HookMatcher;
import io.github.liumaishenjian.ccjava.domain.hook.HookSourceKind;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 证明 S09 Hook Settings/Trust 第一切片只把显式批准的项目绑定交给 Coordinator。
 *
 * @since 0.9.0
 */
class HookTrustGateTest {

    private static final HookInvocation INVOCATION = new HookInvocation(
            HookEventKind.PRE_TOOL,
            new SessionId("session-1"),
            Optional.of(new RunId("run-1")),
            "run_command",
            new JsonObject(Map.of("toolName", "run_command")));

    @Test
    void fingerprintUsesLengthPrefixedStableEncoding() {
        String first = HookFingerprint.sha256(List.of("command", "a", "bc"));
        String same = HookFingerprint.sha256(List.of("command", "a", "bc"));
        String different = HookFingerprint.sha256(List.of("command", "ab", "c"));

        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(same).isEqualTo(first);
        assertThat(different).isNotEqualTo(first);
    }

    @Test
    void projectHookRequiresWorkspaceTrustAndExplicitApproval() {
        HookTrustGate gate = new HookTrustGate();
        HookBinding binding = binding("project");
        String fingerprint = HookFingerprint.sha256(List.of("project", "command"));

        HookTrustGate.Evaluation workspaceDenied = gate.evaluate(
                binding, HookSourceKind.PROJECT_SHARED, fingerprint, false,
                HookTrustStore.single(binding.id(), HookSourceKind.PROJECT_SHARED, fingerprint));
        HookTrustGate.Evaluation approvalMissing = gate.evaluate(
                binding, HookSourceKind.PROJECT_SHARED, fingerprint, true, HookTrustStore.none());

        assertThat(workspaceDenied.status()).isEqualTo(HookTrustGate.TrustStatus.WORKSPACE_UNTRUSTED);
        assertThat(workspaceDenied.binding().trusted()).isFalse();
        assertThat(approvalMissing.status()).isEqualTo(HookTrustGate.TrustStatus.APPROVAL_REQUIRED);
        assertThat(approvalMissing.binding().trusted()).isFalse();
    }

    @Test
    void exactProjectApprovalTrustsBindingButChangedConfigDoesNot() {
        HookTrustGate gate = new HookTrustGate();
        HookBinding binding = binding("project");
        String fingerprint = HookFingerprint.sha256(List.of("project", "command"));
        HookTrustStore store = HookTrustStore.single(binding.id(), HookSourceKind.PROJECT_SHARED, fingerprint);

        HookTrustGate.Evaluation trusted = gate.evaluate(
                binding, HookSourceKind.PROJECT_SHARED, fingerprint, true, store);
        HookTrustGate.Evaluation changed = gate.evaluate(
                binding, HookSourceKind.PROJECT_SHARED,
                HookFingerprint.sha256(List.of("project", "changed-command")), true, store);

        assertThat(trusted.status()).isEqualTo(HookTrustGate.TrustStatus.TRUSTED);
        assertThat(trusted.binding().trusted()).isTrue();
        assertThat(changed.status()).isEqualTo(HookTrustGate.TrustStatus.FINGERPRINT_MISMATCH);
        assertThat(changed.binding().trusted()).isFalse();
    }

    @Test
    void malformedFingerprintIsNeverTrusted() {
        HookTrustGate.Evaluation result = new HookTrustGate().evaluate(
                binding("bad"), HookSourceKind.PROJECT_LOCAL, "not-a-fingerprint", true, HookTrustStore.none());

        assertThat(result.status()).isEqualTo(HookTrustGate.TrustStatus.INVALID_FINGERPRINT);
        assertThat(result.fingerprint()).isEmpty();
        assertThat(result.binding().trusted()).isFalse();
    }

    @Test
    void untrustedProjectBindingIsSkippedAndFailClosedByCoordinator() {
        AtomicInteger calls = new AtomicInteger();
        HookBinding original = new HookBinding(
                "project",
                HookMatcher.event(HookEventKind.PRE_TOOL),
                (invocation, token) -> {
                    calls.incrementAndGet();
                    return new HookExecutionResult(
                            "project",
                            HookDisposition.ALLOW,
                            io.github.liumaishenjian.ccjava.domain.hook.HookExecutionStatus.COMPLETED,
                            Optional.empty(),
                            Optional.empty());
                },
                HookFailurePolicy.FAIL_CLOSED,
                true,
                0);
        HookBinding evaluated = new HookTrustGate().evaluate(
                original,
                HookSourceKind.PROJECT_SHARED,
                HookFingerprint.sha256(List.of("project", "changed")),
                false,
                HookTrustStore.none()).binding();

        var executor = Executors.newSingleThreadExecutor();
        try {
            HookCoordinator coordinator = new HookCoordinator(List.of(evaluated), executor, Duration.ofSeconds(1));
            var result = coordinator.evaluate(INVOCATION, CancellationToken.none());

            assertThat(calls).hasValue(0);
            assertThat(result.disposition()).isEqualTo(HookDisposition.BLOCK);
            assertThat(result.executions().getFirst().status())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.hook.HookExecutionStatus.SKIPPED_UNTRUSTED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void userAndSessionSourcesAreTrustedAfterFingerprintValidation() {
        HookTrustGate gate = new HookTrustGate();
        HookBinding binding = binding("local");
        String fingerprint = HookFingerprint.sha256(List.of("local", "command"));

        HookTrustGate.Evaluation user = gate.evaluate(
                binding, HookSourceKind.USER, fingerprint, false, HookTrustStore.none());
        HookTrustGate.Evaluation session = gate.evaluate(
                binding, HookSourceKind.SESSION, fingerprint, false, HookTrustStore.none());

        assertThat(user.status()).isEqualTo(HookTrustGate.TrustStatus.TRUSTED);
        assertThat(user.binding().trusted()).isTrue();
        assertThat(session.status()).isEqualTo(HookTrustGate.TrustStatus.TRUSTED);
        assertThat(session.binding().trusted()).isTrue();
    }

    private static HookBinding binding(String id) {
        return new HookBinding(
                id,
                HookMatcher.event(HookEventKind.PRE_TOOL),
                (invocation, token) -> HookExecutionResult.continued("ok"),
                HookFailurePolicy.FAIL_CLOSED,
                false,
                0);
    }
}
