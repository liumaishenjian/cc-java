package io.github.liumaishenjian.ccjava.cli.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 registry migration 的 create-only、崩溃恢复与冲突保留。 */
class PluginRegistryMigratorTest {
    @TempDir Path temp;

    @Test void publishesCanonicalRegistryAndNeverOverwritesConflict() throws Exception {
        Path legacy = temp.resolveSibling("legacy-" + System.nanoTime());
        Files.writeString(legacy, "zeta\t2\t" + "b".repeat(64) + "\nalpha\t1\t" + "a".repeat(64) + "\n");
        try {
            var result = PluginRuntimeResources.migrateLegacyRegistry(temp, legacy);
            assertThat(result.success()).isTrue();
            assertThat(Files.readString(temp.resolve("registry.v1"))).startsWith("alpha\t1");
            Files.writeString(temp.resolve("registry.v1"), "conflict\n");
            assertThat(new PluginRegistryMigrator(temp).migrate(legacy).success()).isFalse();
        } finally { Files.deleteIfExists(legacy); }
    }

    @Test void preexistingSameDigestTargetWithoutJournalProofIsRejected() throws Exception {
        Path legacy = temp.resolveSibling("legacy-same-" + System.nanoTime());
        Files.writeString(legacy, "demo\t1\t" + "c".repeat(64) + "\n");
        try {
            Files.write(temp.resolve("registry.v1"), PluginRegistryIndex.canonicalize(legacy));
            var result = new PluginRegistryMigrator(temp).migrate(legacy);
            assertThat(result.success()).isFalse();
            assertThat(result.status()).isEqualTo("TARGET_CONFLICT");
            assertThat(new PluginTransactionJournal(temp).replay()).last()
                    .extracting(PluginTransactionRecord::phase)
                    .isEqualTo(PluginTransactionPhase.FAILED_PRESERVED);
        } finally { Files.deleteIfExists(legacy); }
    }

    @Test void everyCrashPointRecoversFromLastDurablePhaseWithoutSyntheticFailure() throws Exception {
        for (PluginRegistryMigrator.CrashPoint point : PluginRegistryMigrator.CrashPoint.values()) {
            Path root = temp.resolve(point.name()); Files.createDirectories(root);
            Path legacy = root.resolveSibling("legacy-" + point.name());
            Files.writeString(legacy, "demo\t1\t" + "c".repeat(64) + "\n");
            try {
                var crashed = new PluginRegistryMigrator(root, current -> {
                    if (current == point) throw new PluginRegistryMigrator.CrashSimulation();
                }).migrate(legacy);
                assertThat(crashed.success()).isFalse();
                List<PluginTransactionRecord> before = new PluginTransactionJournal(root).replay();
                assertThat(before).noneMatch(record -> record.phase() == PluginTransactionPhase.FAILED_PRESERVED);
                assertThat(before.getLast().phase()).isEqualTo(expectedDurablePhase(point));

                var recovery = new PluginTransactionRecovery(root).recover();
                assertThat(recovery.clean()).as(point.name()).isTrue();
                assertThat(recovery.recovered()).as(point.name()).isOne();
                assertThat(new PluginTransactionJournal(root).replay().getLast().phase())
                        .isEqualTo(PluginTransactionPhase.COMPLETED);
                if (point == PluginRegistryMigrator.CrashPoint.AFTER_VERIFIED
                        || point == PluginRegistryMigrator.CrashPoint.AFTER_PUBLISHED) {
                    assertThat(Files.readString(root.resolve("registry.v1"))).contains("demo");
                } else {
                    assertThat(root.resolve("registry.v1")).doesNotExist();
                }
            } finally { Files.deleteIfExists(legacy); }
        }
    }

    @Test void recoveryFailsClosedWhenPhaseFactsConflict() throws Exception {
        Path legacy = temp.resolveSibling("legacy-conflict-" + System.nanoTime());
        Files.writeString(legacy, "demo\t1\t" + "c".repeat(64) + "\n");
        try {
            new PluginRegistryMigrator(temp, point -> {
                if (point == PluginRegistryMigrator.CrashPoint.AFTER_STAGED) {
                    throw new PluginRegistryMigrator.CrashSimulation();
                }
            }).migrate(legacy);
            Files.writeString(temp.resolve("registry.v1"), "conflicting-fact\n");
            var recovery = new PluginTransactionRecovery(temp).recover();
            assertThat(recovery.clean()).isFalse();
            assertThat(recovery.preserved()).isOne();
            assertThat(new PluginTransactionJournal(temp).replay().getLast().phase())
                    .isEqualTo(PluginTransactionPhase.FAILED_PRESERVED);
        } finally { Files.deleteIfExists(legacy); }
    }

    private static PluginTransactionPhase expectedDurablePhase(PluginRegistryMigrator.CrashPoint point) {
        return switch (point) {
            case AFTER_PREPARED -> PluginTransactionPhase.PREPARED;
            case AFTER_STAGED -> PluginTransactionPhase.STAGED;
            case AFTER_VERIFIED -> PluginTransactionPhase.VERIFIED;
            case AFTER_PUBLISHED -> PluginTransactionPhase.PUBLISHED;
        };
    }
}
