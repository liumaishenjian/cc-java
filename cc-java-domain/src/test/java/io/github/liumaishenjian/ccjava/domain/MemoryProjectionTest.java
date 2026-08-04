package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryProjectionTest {

    private static final MemoryCatalogRevision REVISION =
            new MemoryCatalogRevision("0".repeat(64));

    @Test
    void rejectsWrongByteAccountingAndBudgetOverflow() {
        MemoryProjectionItem item = new MemoryProjectionItem(
                "safe-topic", MemoryKind.PROJECT_STATE, "hook", "正文",
                "1".repeat(64), "正文".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        assertThatThrownBy(() -> new MemoryProjection(
                        List.of(item), 1, 100, REVISION, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryProjection(
                        List.of(item), item.utf8Bytes(), item.utf8Bytes() - 1,
                        REVISION, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateProjectedTopic() {
        MemoryProjectionItem item = new MemoryProjectionItem(
                "safe-topic", MemoryKind.PROJECT_STATE, "hook", "body",
                "1".repeat(64), 4);

        assertThatThrownBy(() -> new MemoryProjection(
                        List.of(item, item), 8, 10, REVISION, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void rejectsPlanWithMoreThanTwentyCandidates() {
        MemoryTopicHeader header = new MemoryTopicHeader(
                "safe-topic", MemoryKind.PROJECT_STATE, "hook",
                java.time.LocalDate.of(2026, 8, 5), "1".repeat(64));

        assertThatThrownBy(() -> new MemoryRecallPlan(
                        java.util.Collections.nCopies(21, header), REVISION, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
    }

    @Test
    void rejectsInvalidQueryBudgetsAndDuplicateKeywords() {
        assertThatThrownBy(() -> new RecallQuery(
                        "task", List.of("same", "same"), 2, 100, REVISION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecallQuery(
                        "task", List.of("one"), 0, 100, REVISION))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
