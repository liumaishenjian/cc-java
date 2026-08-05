package io.github.liumaishenjian.ccjava.core.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.settings.EffectiveSettings;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettingsSnapshotStoreTest {

    @Test
    void publishesInitialSnapshotOnlyForAnEmptyExpectedRevision() {
        SettingsSnapshotStore store = new SettingsSnapshotStore();
        EffectiveSettingsSnapshot initial = snapshot(1);

        assertThat(store.replaceIfCurrent(Optional.of(1L), initial)).isFalse();
        assertThat(store.replaceIfCurrent(Optional.empty(), initial)).isTrue();
        assertThat(store.current()).contains(initial);
    }

    @Test
    void rejectsStaleOrNonMonotonicReplacementWithoutChangingLastKnownGood() {
        SettingsSnapshotStore store = new SettingsSnapshotStore();
        EffectiveSettingsSnapshot initial = snapshot(1);
        assertThat(store.replaceIfCurrent(Optional.empty(), initial)).isTrue();

        assertThat(store.replaceIfCurrent(Optional.empty(), snapshot(2))).isFalse();
        assertThat(store.replaceIfCurrent(Optional.of(1L), snapshot(1))).isFalse();
        assertThat(store.current()).contains(initial);
    }

    @Test
    void permitsExactlyOneRacingCandidateToPublish() {
        SettingsSnapshotStore store = new SettingsSnapshotStore();
        assertThat(store.replaceIfCurrent(Optional.empty(), snapshot(1))).isTrue();

        boolean first = store.replaceIfCurrent(Optional.of(1L), snapshot(2));
        boolean second = store.replaceIfCurrent(Optional.of(1L), snapshot(2));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(store.current().orElseThrow().revision()).isEqualTo(2);
    }

    private static EffectiveSettingsSnapshot snapshot(long revision) {
        return new EffectiveSettingsSnapshot(revision, new EffectiveSettings(Optional.empty(), Optional.empty(), List.of(),
                Optional.empty(), java.util.Map.of(), List.of(), java.util.Map.of(), Optional.empty(), List.of()));
    }
}
