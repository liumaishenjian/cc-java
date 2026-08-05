package io.github.liumaishenjian.ccjava.core.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnostic;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticSeverity;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredSettings;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsRevision;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceId;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceKind;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceSnapshot;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettingsRefreshCoordinatorTest {

    @Test
    void firstCompleteCandidateEstablishesLastKnownGoodAndLaterCompleteCandidateReplacesIt() {
        SettingsSnapshotStore store = new SettingsSnapshotStore();
        SettingsRefreshCoordinator coordinator = new SettingsRefreshCoordinator(new SettingsResolver(), store);

        SettingsRefreshResult first = coordinator.refresh(List.of(snapshot(SettingsSourceKind.DEFAULTS, "default")), List.of(),
                CancellationToken.none());
        SettingsRefreshResult second = coordinator.refresh(List.of(snapshot(SettingsSourceKind.DEFAULTS, "updated")), List.of(),
                CancellationToken.none());

        assertThat(first.published()).isTrue();
        assertThat(first.currentSnapshot().orElseThrow().revision()).isEqualTo(1);
        assertThat(second.published()).isTrue();
        assertThat(second.currentSnapshot().orElseThrow().revision()).isEqualTo(2);
        assertThat(store.current()).contains(second.currentSnapshot().orElseThrow());
    }

    @Test
    void sourceFailureNeverEstablishesOrReplacesLastKnownGood() {
        SettingsSnapshotStore store = new SettingsSnapshotStore();
        SettingsRefreshCoordinator coordinator = new SettingsRefreshCoordinator(new SettingsResolver(), store);
        ConfigurationDiagnostic failure = diagnostic(ConfigurationDiagnosticCode.MALFORMED_JSON);

        SettingsRefreshResult first = coordinator.refresh(List.of(snapshot(SettingsSourceKind.DEFAULTS, "default")), List.of(failure),
                CancellationToken.none());
        assertThat(first.published()).isFalse();
        assertThat(first.currentSnapshot()).isEmpty();

        SettingsRefreshResult published = coordinator.refresh(List.of(snapshot(SettingsSourceKind.DEFAULTS, "default")), List.of(),
                CancellationToken.none());
        SettingsRefreshResult rejected = coordinator.refresh(List.of(snapshot(SettingsSourceKind.DEFAULTS, "changed")), List.of(failure),
                CancellationToken.none());

        assertThat(published.published()).isTrue();
        assertThat(rejected.published()).isFalse();
        assertThat(rejected.currentSnapshot()).contains(published.currentSnapshot().orElseThrow());
        assertThat(store.current()).contains(published.currentSnapshot().orElseThrow());
    }

    @Test
    void optionalMissingFilesAreNoOpsAndDoNotBlockFirstValidPublication() {
        SettingsSnapshotStore store = new SettingsSnapshotStore();
        SettingsRefreshCoordinator coordinator = new SettingsRefreshCoordinator(new SettingsResolver(), store);

        SettingsRefreshResult result = coordinator.refresh(List.of(snapshot(SettingsSourceKind.DEFAULTS, "default")), List.of(),
                CancellationToken.none());

        assertThat(result.published()).isTrue();
        assertThat(result.currentSnapshot()).isPresent();
    }

    @Test
    void casLossDiscardsCandidateAndReportsTypedDiagnostic() {
        SettingsSnapshotStore store = new SettingsSnapshotStore();
        SettingsRefreshCoordinator coordinator = new SettingsRefreshCoordinator(new SettingsResolver(), store);
        SettingsRefreshResult initial = coordinator.refresh(List.of(snapshot(SettingsSourceKind.DEFAULTS, "default")), List.of(),
                CancellationToken.none());
        EffectiveSettingsSnapshot winner = new EffectiveSettingsSnapshot(2, initial.currentSnapshot().orElseThrow().settings());

        SettingsRefreshResult loser = coordinator.refresh(List.of(snapshot(SettingsSourceKind.DEFAULTS, "candidate")), List.of(),
                CancellationToken.none(), () -> assertThat(store.replaceIfCurrent(Optional.of(1L), winner)).isTrue());

        assertThat(loser.published()).isFalse();
        assertThat(loser.diagnostics()).extracting(ConfigurationDiagnostic::code)
                .containsExactly(ConfigurationDiagnosticCode.CAS_CONFLICT);
        assertThat(store.current()).contains(winner);
    }

    @Test
    void exhaustedRevisionPreservesLastKnownGoodWithTypedDiagnostic() {
        EffectiveSettingsSnapshot exhausted = new EffectiveSettingsSnapshot(Long.MAX_VALUE,
                new io.github.liumaishenjian.ccjava.domain.settings.EffectiveSettings(Optional.empty(), Optional.empty(), List.of(),
                        Optional.empty(), java.util.Map.of(), List.of(), java.util.Map.of(), Optional.empty(), List.of()));
        SettingsSnapshotStore store = new SettingsSnapshotStore(exhausted);
        SettingsRefreshCoordinator coordinator = new SettingsRefreshCoordinator(new SettingsResolver(), store);

        SettingsRefreshResult result = coordinator.refresh(List.of(snapshot(SettingsSourceKind.DEFAULTS, "candidate")), List.of(),
                CancellationToken.none());

        assertThat(result.published()).isFalse();
        assertThat(result.diagnostics()).extracting(ConfigurationDiagnostic::code)
                .containsExactly(ConfigurationDiagnosticCode.REVISION_EXHAUSTED);
        assertThat(store.current()).contains(exhausted);
    }

    @Test
    void cancellationBeforeRefreshPreservesLastKnownGood() {
        SettingsSnapshotStore store = new SettingsSnapshotStore();
        SettingsRefreshCoordinator coordinator = new SettingsRefreshCoordinator(new SettingsResolver(), store);
        SettingsRefreshResult published = coordinator.refresh(List.of(snapshot(SettingsSourceKind.DEFAULTS, "default")), List.of(),
                CancellationToken.none());
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel();

        SettingsRefreshResult result = coordinator.refresh(List.of(snapshot(SettingsSourceKind.DEFAULTS, "changed")), List.of(),
                cancelled.token());

        assertThat(result.published()).isFalse();
        assertThat(result.diagnostics()).extracting(ConfigurationDiagnostic::code)
                .containsExactly(ConfigurationDiagnosticCode.CANCELLED);
        assertThat(store.current()).contains(published.currentSnapshot().orElseThrow());
    }

    private static SettingsSourceSnapshot snapshot(SettingsSourceKind kind, String modelName) {
        return new SettingsSourceSnapshot(new SettingsSourceId(kind, kind.name().toLowerCase()),
                new SettingsRevision(("0".repeat(63)) + kind.ordinal()),
                new DeclaredSettings(Optional.of(modelName), Optional.empty(), List.of(), Optional.empty(), java.util.Map.of(),
                        List.of(), Optional.empty()), List.of());
    }

    private static ConfigurationDiagnostic diagnostic(ConfigurationDiagnosticCode code) {
        return new ConfigurationDiagnostic(new SettingsSourceId(SettingsSourceKind.USER, "user"), code,
                ConfigurationDiagnosticSeverity.ERROR, Optional.empty());
    }
}
