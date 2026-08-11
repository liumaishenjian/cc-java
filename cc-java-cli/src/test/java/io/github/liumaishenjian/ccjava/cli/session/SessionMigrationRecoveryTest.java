package io.github.liumaishenjian.ccjava.cli.session;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionMigrationRecoveryTest {
    @TempDir Path temp;

    @Test
    void everyCrashPointRecoversPublishedTargetOrKeepsOldCanonical() throws Exception {
        for (SessionMigrationCoordinator.CrashPoint point : SessionMigrationCoordinator.CrashPoint.values()) {
            Path dir = temp.resolve(point.name()); Files.createDirectories(dir);
            Path source = dir.resolve("source.jsonl"); Path target = dir.resolve("target.jsonl");
            Files.writeString(source, "one\ntwo\n");
            var crashing = new SessionMigrationCoordinator(current -> { if (current == point) throw new java.io.IOException("crash"); });
            crashing.migrate(source, target, 1, 2, line -> "v2:" + line);
            assertThat(Files.readString(source)).isEqualTo("one\ntwo\n");
            var recovered = new SessionMigrationCoordinator().migrate(source, target, 1, 2, line -> "v2:" + line);
            if (point == SessionMigrationCoordinator.CrashPoint.AFTER_CLEANUP) {
                /* publish 已完成但 proof journal 已清理；重试必须拒绝覆盖，而不是猜测完成。 */
                assertThat(recovered.status()).isEqualTo("TARGET_CONFLICT");
            } else {
                assertThat(recovered.success()).as(point.name()).isTrue();
            }
            assertThat(Files.readString(target)).isEqualTo("v2:one\nv2:two\n");
            assertThat(Files.readString(source)).isEqualTo("one\ntwo\n");
            assertThat(Files.exists(dir.resolve("target.jsonl.migration.staged"))).isFalse();
            assertThat(Files.exists(dir.resolve("target.jsonl.migration.journal"))).isFalse();
        }
    }

    @Test
    void existingTargetIsNeverOverwrittenWithoutTransactionProof() throws Exception {
        Path source = temp.resolve("source-existing.jsonl");
        Path target = temp.resolve("target-existing.jsonl");
        Files.writeString(source, "new\n");
        Files.writeString(target, "existing\n");

        var result = new SessionMigrationCoordinator().migrate(source, target, 1, 2, line -> "v2:" + line);

        assertThat(result.success()).isFalse();
        assertThat(Files.readString(target)).isEqualTo("existing\n");
        assertThat(Files.readString(source)).isEqualTo("new\n");
    }
}
