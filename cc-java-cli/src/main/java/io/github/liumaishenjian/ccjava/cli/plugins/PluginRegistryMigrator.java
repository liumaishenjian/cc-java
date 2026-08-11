package io.github.liumaishenjian.ccjava.cli.plugins;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 在 Plugin global writer 内把 legacy registry 快照迁移为严格 {@code registry.v1}。
 *
 * <p>迁移源保持只读；目标发布前依次 durable PREPARED/STAGED/VERIFIED，校验严格 registry
 * 语法与 SHA-256，再以 create-only atomic move 发布。已存在目标只在 journal 的 PUBLISHED
 * digest 能证明正是本事务产物时视为恢复完成，其他冲突一律 {@code FAILED_PRESERVED}，绝不覆盖。</p>
 *
 * @since 0.1.0
 */
public final class PluginRegistryMigrator {
    private final Path root;
    private final FaultInjector faults;

    /**
     * 创建绑定固定 Plugin store root 的迁移器。
     *
     * @param root Plugin store root
     */
    public PluginRegistryMigrator(Path root) {
        this(root, point -> { });
    }

    PluginRegistryMigrator(Path root, FaultInjector faults) {
        this.root = Objects.requireNonNull(root, "root 不能为空").toAbsolutePath().normalize();
        this.faults = Objects.requireNonNull(faults, "faults 不能为空");
    }

    /**
     * 执行或恢复一次 create-only registry migration。
     *
     * @param legacy 调用方已经确认来源身份的只读 legacy registry
     * @return 固定且不泄漏路径/正文的终态；失败时保留冲突现场
     */
    public MigrationResult migrate(Path legacy) {
        try (PluginGlobalWriterLease lease = PluginGlobalWriterLease.acquire(root)) {
            var recovery = new PluginTransactionRecovery(root).recover(lease);
            if (!recovery.clean()) {
                return new MigrationResult(false, "RECOVERY_UNCERTAIN");
            }
            return migrateLocked(legacy);
        } catch (Exception failure) {
            return new MigrationResult(false, "MIGRATION_FAILED");
        }
    }

    private MigrationResult migrateLocked(Path legacy) throws Exception {
        Path source = regularSource(legacy);
        byte[] canonical = PluginRegistryIndex.canonicalize(source);
        String digest = sha256(canonical);
        String nonce = digest.substring(0, 16);
        String tx = "registry-migration-" + nonce;
        Path staged = child(".registry-migration-" + nonce + ".tmp");
        Path target = child("registry.v1");
        PluginTransactionJournal journal = new PluginTransactionJournal(root);
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                /* 目标摘要相同也不能证明归属；只有 recovery journal 才能证明已发布事务。 */
                return preserve(journal, tx, digest, "TARGET_CONFLICT");
            }
            journal.append(record(tx, PluginTransactionPhase.PREPARED, digest));
            faults.at(CrashPoint.AFTER_PREPARED);
            writeStage(staged, canonical);
            journal.append(record(tx, PluginTransactionPhase.STAGED, digest));
            faults.at(CrashPoint.AFTER_STAGED);
            PluginRegistryIndex.read(staged);
            if (!digest.equals(sha256(Files.readAllBytes(staged)))) {
                throw new IOException("staged registry digest 不匹配");
            }
            journal.append(record(tx, PluginTransactionPhase.VERIFIED, digest));
            faults.at(CrashPoint.AFTER_VERIFIED);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            journal.append(record(tx, PluginTransactionPhase.PUBLISHED, digest));
            faults.at(CrashPoint.AFTER_PUBLISHED);
            journal.append(record(tx, PluginTransactionPhase.COMPLETED, digest));
            return new MigrationResult(true, "PUBLISHED");
        } catch (CrashSimulation crash) {
            /* 模拟进程在 durable phase 后立即死亡：不得追加一个现实中不可能出现的失败终态。 */
            throw crash;
        } catch (Exception failure) {
            try {
                journal.append(record(tx, PluginTransactionPhase.FAILED_PRESERVED, digest));
            } catch (RuntimeException ignored) {
                // 原失败仍是权威；journal append 失败不得隐藏它。
            }
            throw failure;
        }
    }

    private static MigrationResult preserve(
            PluginTransactionJournal journal,
            String tx,
            String digest,
            String status) {
        journal.append(record(tx, PluginTransactionPhase.FAILED_PRESERVED, digest));
        return new MigrationResult(false, status);
    }

    private static PluginTransactionRecord record(String tx, PluginTransactionPhase phase, String digest) {
        return new PluginTransactionRecord(
                tx,
                "registry",
                PluginTransactionOperation.REGISTRY_MIGRATION,
                phase,
                digest);
    }

    private Path child(String name) throws IOException {
        Path value = root.resolve(name).normalize();
        if (!root.equals(value.getParent())) {
            throw new IOException("migration child 越界");
        }
        return value;
    }

    private static Path regularSource(Path value) throws IOException {
        Path source = value.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || !source.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(source)) {
            throw new IOException("legacy registry 不是稳定普通文件");
        }
        return source;
    }

    private static void writeStage(Path target, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    enum CrashPoint {
        AFTER_PREPARED,
        AFTER_STAGED,
        AFTER_VERIFIED,
        AFTER_PUBLISHED
    }

    @FunctionalInterface
    interface FaultInjector {
        void at(CrashPoint point) throws IOException;
    }
    /** 测试专用的进程死亡模拟；故意绕过普通失败终态写入。 */
    static final class CrashSimulation extends IOException {
        CrashSimulation() {
            super("simulated process death");
        }
    }

    /**
     * 不暴露路径或 registry 正文的迁移终态。
     *
     * @param success 是否完成 create-only publish 或 journal-proven recovery
     * @param status 固定迁移状态
     */
    public record MigrationResult(boolean success, String status) {
    }
}
