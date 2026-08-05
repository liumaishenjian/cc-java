package io.github.liumaishenjian.ccjava.core.instructions;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionActivation;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionCandidate;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionScopeKind;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionSourceKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 S08 指令发现的确定性顺序、限额、去重和取消原子性。
 *
 * @since 0.8.0
 */
class DeterministicInstructionDiscoveryTest {

    @Test
    void ordersUserProjectAndVerifiedDirectoryWithoutRepeatingRoot() {
        DeterministicInstructionDiscovery discovery = new DeterministicInstructionDiscovery(loader(Map.of(
                "user", loaded("u", "user instructions"),
                "project", loaded("p", "project instructions"),
                "directory-src", loaded("d", "directory instructions"))));

        var result = discovery.discover(new InstructionDiscoveryRequest(List.of(
                candidate(InstructionSourceKind.DIRECTORY, "directory-src", 2,
                        InstructionActivation.VERIFIED_TARGET),
                candidate(InstructionSourceKind.PROJECT, "project", 1, InstructionActivation.STARTUP),
                candidate(InstructionSourceKind.USER, "user", 0, InstructionActivation.STARTUP))),
                CancellationToken.none());

        assertThat(result.items()).extracting(item -> item.provenance().safeSourceId())
                .containsExactly("user", "project", "directory-src");
    }

    @Test
    void deduplicatesOnlyMatchingCanonicalIdentityAndDigestAndProducesStableRevision() {
        InstructionLoader loader = loader(Map.of(
                "one", loaded("same-identity", "first"),
                "two", loaded("same-identity", "second"),
                "three", loaded("other-identity", "first"),
                "four", loaded("same-identity", "first")));
        DeterministicInstructionDiscovery discovery = new DeterministicInstructionDiscovery(loader);
        InstructionDiscoveryRequest request = new InstructionDiscoveryRequest(List.of(
                candidate(InstructionSourceKind.USER, "one", 0, InstructionActivation.STARTUP),
                candidate(InstructionSourceKind.PROJECT, "two", 1, InstructionActivation.STARTUP),
                candidate(InstructionSourceKind.DIRECTORY, "three", 2, InstructionActivation.VERIFIED_TARGET),
                candidate(InstructionSourceKind.DIRECTORY, "four", 3, InstructionActivation.VERIFIED_TARGET)));

        var first = discovery.discover(request, CancellationToken.none());
        var second = discovery.discover(request, CancellationToken.none());

        assertThat(first.items()).hasSize(3);
        assertThat(loaded("internal-path", "secret sentinel").toString())
                .doesNotContain("internal-path", "secret sentinel");
        assertThat(first.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsOnly(InstructionDiagnosticCode.DUPLICATE_SUPPRESSED);
        assertThat(first.revision()).isEqualTo(second.revision());
    }

    @Test
    void collectsSafeDiagnosticsAndKeepsOtherValidItems() {
        DeterministicInstructionDiscovery discovery = new DeterministicInstructionDiscovery((candidate, token) -> {
            if (candidate.safeSourceId().equals("bad")) {
                return InstructionLoadResult.failure(InstructionDiagnosticCode.UNREADABLE);
            }
            return InstructionLoadResult.success(loaded("ok", "safe"));
        });

        var result = discovery.discover(new InstructionDiscoveryRequest(List.of(
                candidate(InstructionSourceKind.USER, "bad", 0, InstructionActivation.STARTUP),
                candidate(InstructionSourceKind.PROJECT, "ok", 1, InstructionActivation.STARTUP))),
                CancellationToken.none());

        assertThat(result.items()).hasSize(1);
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(InstructionDiagnosticCode.UNREADABLE);
            assertThat(diagnostic.safeSourceId()).isEqualTo("bad");
        });
    }

    @Test
    void acceptsFirstSixteenValidResultsAndDiagnosesLaterCandidates() {
        DeterministicInstructionDiscovery discovery = new DeterministicInstructionDiscovery(
                (candidate, token) -> InstructionLoadResult.success(loaded(candidate.safeSourceId(), "x")));
        List<InstructionCandidate> files = java.util.stream.IntStream.range(0, 18)
                .mapToObj(index -> candidate(InstructionSourceKind.PROJECT, "file-" + index,
                        index, InstructionActivation.STARTUP)).toList();

        var result = discovery.discover(new InstructionDiscoveryRequest(files), CancellationToken.none());

        assertThat(result.items()).hasSize(16);
        assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(InstructionDiagnosticCode.COUNT_LIMIT, InstructionDiagnosticCode.COUNT_LIMIT);
    }

    @Test
    void countsFailedLoadsAgainstAttemptLimit() {
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        DeterministicInstructionDiscovery discovery = new DeterministicInstructionDiscovery((candidate, token) -> {
            loads.incrementAndGet();
            return InstructionLoadResult.failure(InstructionDiagnosticCode.UNREADABLE);
        });
        List<InstructionCandidate> candidates = java.util.stream.IntStream.range(0, 18)
                .mapToObj(index -> candidate(InstructionSourceKind.PROJECT, "failed-" + index,
                        index, InstructionActivation.STARTUP)).toList();

        var result = discovery.discover(new InstructionDiscoveryRequest(candidates), CancellationToken.none());

        assertThat(loads).hasValue(DeterministicInstructionDiscovery.MAX_FILES);
        assertThat(result.items()).isEmpty();
        assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(
                        InstructionDiagnosticCode.UNREADABLE, InstructionDiagnosticCode.UNREADABLE,
                        InstructionDiagnosticCode.UNREADABLE, InstructionDiagnosticCode.UNREADABLE,
                        InstructionDiagnosticCode.UNREADABLE, InstructionDiagnosticCode.UNREADABLE,
                        InstructionDiagnosticCode.UNREADABLE, InstructionDiagnosticCode.UNREADABLE,
                        InstructionDiagnosticCode.UNREADABLE, InstructionDiagnosticCode.UNREADABLE,
                        InstructionDiagnosticCode.UNREADABLE, InstructionDiagnosticCode.UNREADABLE,
                        InstructionDiagnosticCode.UNREADABLE, InstructionDiagnosticCode.UNREADABLE,
                        InstructionDiagnosticCode.UNREADABLE, InstructionDiagnosticCode.UNREADABLE,
                        InstructionDiagnosticCode.COUNT_LIMIT, InstructionDiagnosticCode.COUNT_LIMIT);
    }

    @Test
    void enforcesWholeFileTotalAndLineLimitsWithoutTruncating() {
        String prefix = "a".repeat(32 * 1024 - 1);
        String overflow = "b";
        DeterministicInstructionDiscovery discovery = new DeterministicInstructionDiscovery(loader(Map.of(
                "one", loaded("one", prefix + "1"),
                "two", loaded("two", prefix + "2"),
                "three", loaded("three", prefix + "3"),
                "four", loaded("four", prefix + "4"),
                "overflow", loaded("overflow", overflow),
                "later", loaded("later", "small"),
                "lines", loaded("lines", "line\n".repeat(1_000)))));

        var total = discovery.discover(new InstructionDiscoveryRequest(List.of(
                candidate(InstructionSourceKind.USER, "one", 0, InstructionActivation.STARTUP),
                candidate(InstructionSourceKind.PROJECT, "two", 1, InstructionActivation.STARTUP),
                candidate(InstructionSourceKind.PROJECT, "three", 2, InstructionActivation.STARTUP),
                candidate(InstructionSourceKind.PROJECT, "four", 3, InstructionActivation.STARTUP),
                candidate(InstructionSourceKind.PROJECT, "overflow", 4, InstructionActivation.STARTUP),
                candidate(InstructionSourceKind.PROJECT, "later", 5, InstructionActivation.STARTUP))),
                CancellationToken.none());
        assertThat(total.items()).hasSize(4);
        assertThat(total.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(InstructionDiagnosticCode.LIMIT_EXCEEDED, InstructionDiagnosticCode.LIMIT_EXCEEDED);

        var lines = discovery.discover(new InstructionDiscoveryRequest(List.of(
                candidate(InstructionSourceKind.USER, "lines", 0, InstructionActivation.STARTUP))),
                CancellationToken.none());
        assertThat(lines.items()).isEmpty();
        assertThat(lines.diagnostics()).singleElement()
                .extracting(diagnostic -> diagnostic.code())
                .isEqualTo(InstructionDiagnosticCode.LIMIT_EXCEEDED);
    }

    @Test
    void cancellationNeverReturnsPartialResult() {
        CancellationSource cancellation = new CancellationSource();
        DeterministicInstructionDiscovery discovery = new DeterministicInstructionDiscovery((candidate, token) -> {
            cancellation.cancel();
            return InstructionLoadResult.success(loaded(candidate.safeSourceId(), "would be partial"));
        });

        assertThatThrownBy(() -> discovery.discover(new InstructionDiscoveryRequest(List.of(
                candidate(InstructionSourceKind.USER, "user", 0, InstructionActivation.STARTUP))),
                cancellation.token())).isInstanceOf(InstructionDiscoveryCancelledException.class);
    }

    private static InstructionCandidate candidate(
            InstructionSourceKind source, String id, int precedence, InstructionActivation activation) {
        InstructionScopeKind scope = source == InstructionSourceKind.USER
                ? InstructionScopeKind.USER_GLOBAL
                : source == InstructionSourceKind.DIRECTORY
                ? InstructionScopeKind.DIRECTORY_SUBTREE : InstructionScopeKind.WORKSPACE;
        return new InstructionCandidate(source, scope, id, precedence, activation);
    }

    private static InstructionLoader loader(Map<String, LoadedInstruction> values) {
        return (candidate, token) -> InstructionLoadResult.success(values.get(candidate.safeSourceId()));
    }

    private static LoadedInstruction loaded(String identity, String text) {
        return new LoadedInstruction(identity, sha256(text), text);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
