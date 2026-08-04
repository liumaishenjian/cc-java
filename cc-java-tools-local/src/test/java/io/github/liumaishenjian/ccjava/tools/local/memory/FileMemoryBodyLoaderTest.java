package io.github.liumaishenjian.ccjava.tools.local.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.MemoryKind;
import io.github.liumaishenjian.ccjava.domain.MemoryTopic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMemoryBodyLoaderTest {

    @TempDir
    Path temporary;

    @Test
    void loadsValidTopicAndIsolatesCorruptSibling() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("memory"));
        FileMemoryRepository repository = new FileMemoryRepository(root);
        MemoryTopic persisted = repository.saveTopic(
                MemoryTopic.candidate("good-topic", MemoryKind.PROJECT_STATE,
                        "hook", "valid body", LocalDate.of(2026, 8, 4)),
                Optional.empty()).topic().orElseThrow();
        Files.write(root.resolve("bad-topic.md"), new byte[] {(byte) 0xC3, (byte) 0x28});
        FileMemoryBodyLoader loader = new FileMemoryBodyLoader(root);

        assertThat(loader.load("bad-topic")).isEmpty();
        assertThat(loader.load("good-topic")).contains(persisted);
    }

    @Test
    void rejectsSymbolicLinkWithoutAffectingValidSibling() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("links"));
        Path outside = Files.writeString(temporary.resolve("outside.md"), "outside");
        try {
            Files.createSymbolicLink(root.resolve("linked-topic.md"), outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.abort("当前环境不能创建 Symlink");
        }
        FileMemoryRepository repository = new FileMemoryRepository(root);
        repository.saveTopic(
                MemoryTopic.candidate("safe-topic", MemoryKind.PROJECT_STATE,
                        "hook", "safe", LocalDate.of(2026, 8, 4)),
                Optional.empty());
        FileMemoryBodyLoader loader = new FileMemoryBodyLoader(root);

        assertThat(loader.load("linked-topic")).isEmpty();
        assertThat(loader.load("safe-topic")).isPresent();
    }
}
