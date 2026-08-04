package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryCatalogTest {

    @Test
    void rejectsDuplicateTopicNameEvenWhenOtherHeaderFieldsDiffer() {
        MemoryTopicHeader first = header(
                "same-topic",
                MemoryKind.USER_PROFILE,
                "first",
                "0".repeat(64));
        MemoryTopicHeader second = header(
                "same-topic",
                MemoryKind.PROJECT_STATE,
                "second",
                "1".repeat(64));

        assertThatThrownBy(() -> catalog(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复 topic name");
    }

    @Test
    void rejectsEntriesOutsideStableTopicNameOrder() {
        MemoryTopicHeader later = header(
                "z-topic",
                MemoryKind.PROJECT_STATE,
                "later",
                "0".repeat(64));
        MemoryTopicHeader earlier = header(
                "a-topic",
                MemoryKind.PROJECT_STATE,
                "earlier",
                "1".repeat(64));

        assertThatThrownBy(() -> catalog(List.of(later, earlier)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("严格升序");
    }

    private static MemoryTopicHeader header(
            String name,
            MemoryKind kind,
            String description,
            String digest) {
        return new MemoryTopicHeader(
                name,
                kind,
                description,
                LocalDate.of(2026, 8, 4),
                digest);
    }

    private static MemoryCatalog catalog(List<MemoryTopicHeader> entries) {
        return new MemoryCatalog(
                entries,
                List.of(),
                new MemoryCatalogRevision("0".repeat(64)));
    }
}
