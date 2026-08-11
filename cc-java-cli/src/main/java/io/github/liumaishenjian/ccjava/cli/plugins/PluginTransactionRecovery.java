package io.github.liumaishenjian.ccjava.cli.plugins;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 在单 writer fence 内对账 Plugin transaction journal 与物理 registry/package 工件。
 *
 * <p>恢复只处理宿主生成的固定内部名称：未发布 staging 可删除；registry backup 在正式索引
 * 缺失时原子恢复；索引已发布时 backup 作为 orphan 清理。无法证明归属、链接、损坏 journal
 * 或同时存在冲突事实源时 Fail Closed 并保留现场，不猜测用户文件。</p>
 *
 * @since 0.1.0
 */
public final class PluginTransactionRecovery {
    private final Path root;
    private final PluginTransactionJournal journal;
    private final SafePluginTreeOperator trees = new SafePluginTreeOperator();

    /**
     * 创建绑定固定 Plugin store 与其 durable journal 的恢复器。
     *
     * @param root Plugin store root
     */
    public PluginTransactionRecovery(Path root) {
        this.root = Objects.requireNonNull(root, "root 不能为空").toAbsolutePath().normalize();
        this.journal = new PluginTransactionJournal(this.root);
    }

    /**
     * 恢复所有未完成事务并返回隐私安全计数。
     *
     * @return 是否干净收敛、恢复/保留计数与固定状态码
     */
    public RecoveryResult recover() {
        try (PluginGlobalWriterLease lease = PluginGlobalWriterLease.acquire(root)) {
            return recover(lease);
        } catch (Exception failure) {
            return new RecoveryResult(false, 0, 1, "RECOVERY_FAILED");
        }
    }

    /** 在调用方已覆盖完整复合操作的 writer lease 内恢复。 */
    RecoveryResult recover(PluginGlobalWriterLease lease) {
        int recovered = 0;
        int preserved = 0;
        try {
            lease.requireRoot(root);
            Map<String, PluginTransactionRecord> latest = new LinkedHashMap<>();
            for (PluginTransactionRecord record : journal.replay()) latest.put(record.transactionId(), record);
            for (PluginTransactionRecord record : latest.values()) {
                if (record.phase() == PluginTransactionPhase.COMPLETED) continue;
                /* FAILED_PRESERVED 是人工处置终态；自动恢复不得把不确定事务升级为完成。 */
                if (record.phase() == PluginTransactionPhase.FAILED_PRESERVED) {
                    preserved++;
                    continue;
                }
                boolean resolved = reconcile(record);
                journal.append(new PluginTransactionRecord(record.transactionId(), record.pluginId(),
                        record.operation(), resolved ? PluginTransactionPhase.COMPLETED
                                : PluginTransactionPhase.FAILED_PRESERVED, record.digest()));
                if (resolved) recovered++; else preserved++;
            }
            return new RecoveryResult(preserved == 0, recovered, preserved,
                    preserved == 0 ? "RECOVERED" : "PRESERVED_UNCERTAIN");
        } catch (java.nio.channels.OverlappingFileLockException active) {
            return new RecoveryResult(false, 0, 1, "WRITER_ACTIVE");
        } catch (Exception failure) {
            return new RecoveryResult(false, recovered, preserved + 1, "RECOVERY_FAILED");
        }
    }

    private boolean reconcile(PluginTransactionRecord record) throws IOException {
        verifyRoot();
        String nonce = record.digest().substring(0, 16);
        Path registry = root.resolve("registry.v1");
        if (record.operation() == PluginTransactionOperation.INSTALL) {
            cleanupInternal(".staging-" + record.pluginId() + "-" + nonce);
            cleanupInternal(".registry-" + nonce + ".tmp");
            return reconcileBackup(registry, root.resolve(".registry-" + nonce + ".bak"));
        }
        if (record.operation() == PluginTransactionOperation.UNINSTALL) {
            cleanupInternal(".registry-remove-" + nonce + ".tmp");
            return reconcileBackup(registry, root.resolve(".registry-remove-" + nonce + ".bak"));
        }
        Path staged = root.resolve(".registry-migration-" + nonce + ".tmp");
        boolean targetExists = Files.exists(registry, LinkOption.NOFOLLOW_LINKS);
        boolean stagedExists = Files.exists(staged, LinkOption.NOFOLLOW_LINKS);
        return switch (record.phase()) {
            case PREPARED -> {
                if (targetExists || stagedExists) yield false;
                yield true;
            }
            case STAGED -> {
                if (targetExists || !regular(staged) || !record.digest().equals(sha256(staged))) yield false;
                cleanupInternal(staged.getFileName().toString());
                yield true;
            }
            case VERIFIED -> {
                if (targetExists || !regular(staged) || !record.digest().equals(sha256(staged))) yield false;
                Files.move(staged, registry, StandardCopyOption.ATOMIC_MOVE);
                yield true;
            }
            case PUBLISHED -> {
                if (stagedExists || !regular(registry) || !record.digest().equals(sha256(registry))) yield false;
                yield true;
            }
            default -> false;
        };
    }

    private boolean reconcileBackup(Path registry, Path backup) throws IOException {
        boolean hasRegistry = regular(registry);
        boolean hasBackup = regular(backup);
        if (hasRegistry && hasBackup) {
            Files.delete(backup);
            return true;
        }
        if (!hasRegistry && hasBackup) {
            Files.move(backup, registry, StandardCopyOption.ATOMIC_MOVE);
            return true;
        }
        return hasRegistry || !Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
    }

    private void cleanupInternal(String name) throws IOException {
        Path path = root.resolve(name).normalize();
        if (!root.equals(path.getParent()) || Files.isSymbolicLink(path)) throw new IOException();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) trees.delete(path);
    }

    private void verifyRoot() throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(root)) throw new IOException();
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65_536]; int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean regular(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        return true;
    }

    /**
     * 不暴露路径或 manifest 的恢复摘要。
     *
     * @param clean 是否所有未完成事务均由 durable facts 确定性收敛
     * @param recovered 自动恢复并补记 COMPLETED 的事务数
     * @param preserved 因冲突或证据不足保留现场的事务数
     * @param status 固定恢复状态
     */
    public record RecoveryResult(boolean clean, int recovered, int preserved, String status) { }
}
