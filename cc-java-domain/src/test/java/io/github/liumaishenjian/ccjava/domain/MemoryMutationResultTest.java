package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemoryMutationResultTest {

    @Test
    void rejectsSuccessWithoutPersistedTopic() {
        assertThatThrownBy(() -> new MemoryMutationResult(
                        MemoryMutationStatus.CREATED,
                        Optional.empty(),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREATED/UPDATED");
    }

    @Test
    void rejectsRejectedResultWithoutDiagnostic() {
        assertThatThrownBy(() -> new MemoryMutationResult(
                        MemoryMutationStatus.REJECTED,
                        Optional.empty(),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("诊断");
    }

    @Test
    void rejectsSuccessfulMutationWithNonIndexFailure() {
        MemoryTopic persisted = new MemoryTopic(
                "safe-topic",
                MemoryKind.PROJECT_STATE,
                "safe",
                "body",
                "0".repeat(64),
                LocalDate.of(2026, 8, 4));

        assertThatThrownBy(() -> new MemoryMutationResult(
                        MemoryMutationStatus.UPDATED,
                        Optional.of(persisted),
                        List.of(MemoryMutationDiagnostic.topic(
                                MemoryMutationDiagnosticKind.IO_FAILURE,
                                "safe-topic"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INDEX_REBUILD_FAILED");
    }
}
