package io.github.liumaishenjian.ccjava.cli.plugins;

import io.github.liumaishenjian.ccjava.core.plugin.PluginRegistry;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginErrorCode;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * 将 active Plugin 转入 QUIESCING，并仅在其 generation lease 归零后安全删除固定内容目录。
 *
 * <p>完成卸载时先以 staged/backup/atomic replace 从完整 {@code registry.v1} 删除目标 ID，
 * 保留所有无关 Plugin。新索引发布后，物理目录删除失败且旧目录仍完整时，Adapter 使用仍保留的
 * backup 原子恢复旧索引；目录已经删除后，任何内存终态或 backup 清理失败都绝不恢复会悬空的旧条目，
 * 而是保留已移除目标的新索引并进入 tombstone/orphan 安全终态。本 Adapter 不取消 Run/Tool，
 * 也不声称 S14 崩溃恢复或迁移能力。</p>
 *
 * @since 0.11.0
 */
public final class DirectoryPluginUninstaller {
    private final Path storeRoot;
    private final PluginRegistry registry;
    private final SafePluginTreeOperator trees;
    private final DirectoryDurability durability;
    private final FaultInjector faults;

    /**
     * 创建绑定固定 store 与运行 registry 的 uninstaller。
     *
     * @param storeRoot Plugin store root
     * @param registry quiesce/lease/activation registry
     */
    public DirectoryPluginUninstaller(Path storeRoot, PluginRegistry registry) {
        this(storeRoot, registry, new SafePluginTreeOperator(), DirectoryPluginUninstaller::forceDirectory,
                point -> { });
    }

    DirectoryPluginUninstaller(Path storeRoot, PluginRegistry registry, SafePluginTreeOperator trees) {
        this(storeRoot, registry, trees, DirectoryPluginUninstaller::forceDirectory, point -> { });
    }

    DirectoryPluginUninstaller(Path storeRoot, PluginRegistry registry, SafePluginTreeOperator trees,
            DirectoryDurability durability) {
        this(storeRoot, registry, trees, durability, point -> { });
    }

    DirectoryPluginUninstaller(Path storeRoot, PluginRegistry registry, SafePluginTreeOperator trees,
            DirectoryDurability durability, FaultInjector faults) {
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot 不能为空").toAbsolutePath().normalize();
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.trees = Objects.requireNonNull(trees, "trees 不能为空");
        this.durability = Objects.requireNonNull(durability, "durability 不能为空");
        this.faults = Objects.requireNonNull(faults, "faults 不能为空");
    }

    /**
     * 发起卸载；已有 lease 时返回 deferred，不杀死当前工作。
     *
     * @param pluginId 待 quiesce 的 Plugin identity
     * @return completed 或 deferred 的隐私安全终态
     */
    public UninstallResult uninstall(PluginId pluginId) {
        Objects.requireNonNull(pluginId, "pluginId 不能为空");
        registry.beginQuiescing(pluginId);
        return finish(pluginId);
    }

    /**
     * lease 释放后在 global writer 内重试持久索引与物理删除。
     *
     * @param pluginId 已进入 quiescing 的 Plugin identity
     * @return 完成或仍需保留现场的终态
     */
    public UninstallResult finish(PluginId pluginId) {
        try (PluginGlobalWriterLease lease = PluginGlobalWriterLease.acquire(storeRoot)) {
            PluginTransactionRecovery.RecoveryResult recovery = new PluginTransactionRecovery(storeRoot).recover(lease);
            if (!recovery.clean()) return new UninstallResult(false, PluginErrorCode.UNINSTALL_TOMBSTONED);
            return finishLocked(pluginId);
        } catch (IOException failure) {
            return new UninstallResult(false, PluginErrorCode.UNINSTALL_TOMBSTONED);
        }
    }

    private UninstallResult finishLocked(PluginId pluginId) {
        var removable = registry.completeRemoval(Objects.requireNonNull(pluginId, "pluginId 不能为空"));
        if (removable.isEmpty()) return new UninstallResult(false, PluginErrorCode.UNINSTALL_DEFERRED);
        PluginSnapshot snapshot = removable.orElseThrow();
        Path directory = child(PluginRegistryIndex.directoryName(
                pluginId.value(), snapshot.fingerprint().treeDigest()));
        Path index = child("registry.v1");
        String nonce = snapshot.fingerprint().treeDigest().substring(0, 16);
        String transactionId = "uninstall-" + pluginId.value() + "-" + nonce;
        PluginTransactionJournal journal = new PluginTransactionJournal(storeRoot);
        Path stage = child(".registry-remove-" + nonce + ".tmp");
        Path backup = child(".registry-remove-" + nonce + ".bak");
        boolean backedUp = false;
        boolean published = false;
        boolean directoryDeleted = false;
        try {
            journal.append(new PluginTransactionRecord(transactionId, pluginId.value(),
                    PluginTransactionOperation.UNINSTALL, PluginTransactionPhase.QUIESCING,
                    snapshot.fingerprint().treeDigest()));
            writeStage(stage, PluginRegistryIndex.removing(PluginRegistryIndex.read(index), pluginId));
            journal.append(new PluginTransactionRecord(transactionId, pluginId.value(),
                    PluginTransactionOperation.UNINSTALL, PluginTransactionPhase.STAGED,
                    snapshot.fingerprint().treeDigest()));
            if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(index)) {
                throw new IOException("registry missing");
            }
            Files.move(index, backup, StandardCopyOption.ATOMIC_MOVE);
            backedUp = true;
            durability.force(storeRoot);
            Files.move(stage, index, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            durability.force(storeRoot);
            journal.append(new PluginTransactionRecord(transactionId, pluginId.value(),
                    PluginTransactionOperation.UNINSTALL, PluginTransactionPhase.REGISTRY_COMMITTED,
                    snapshot.fingerprint().treeDigest()));

            faults.at(FaultPoint.BEFORE_DIRECTORY_DELETE);
            trees.delete(directory);
            directoryDeleted = true;
            journal.append(new PluginTransactionRecord(transactionId, pluginId.value(),
                    PluginTransactionOperation.UNINSTALL, PluginTransactionPhase.TOMBSTONED,
                    snapshot.fingerprint().treeDigest()));
            faults.at(FaultPoint.AFTER_DIRECTORY_DELETE);

            try {
                registry.markDeleted(pluginId);
            } catch (RuntimeException stateFailure) {
                registry.markTombstoned(pluginId);
                cleanupBackupAfterDeletion(backup);
                return new UninstallResult(false, PluginErrorCode.UNINSTALL_TOMBSTONED);
            }
            try {
                faults.at(FaultPoint.BEFORE_BACKUP_DELETE);
                Files.delete(backup);
                backedUp = false;
                durability.force(storeRoot);
            } catch (IOException cleanupFailure) {
                safeDelete(backup);
                return new UninstallResult(false, PluginErrorCode.UNINSTALL_TOMBSTONED);
            }
            journal.append(new PluginTransactionRecord(transactionId, pluginId.value(),
                    PluginTransactionOperation.UNINSTALL, PluginTransactionPhase.COMPLETED,
                    snapshot.fingerprint().treeDigest()));
            return new UninstallResult(true, null);
        } catch (IOException | PluginBoundaryException | IllegalStateException failure) {
            if (directoryDeleted) {
                // 目录已删除：必须保留不含目标 ID 的新索引，绝不恢复悬空旧条目。
                safeDelete(stage);
                cleanupBackupAfterDeletion(backup);
            } else {
                // 目录仍完整：恢复完整旧索引，使重启行为与物理内容一致。
                rollbackIndex(index, backup, published, backedUp);
                safeDelete(stage);
            }
            registry.markTombstoned(pluginId);
            try {
                journal.append(new PluginTransactionRecord(transactionId, pluginId.value(),
                        PluginTransactionOperation.UNINSTALL, PluginTransactionPhase.FAILED_PRESERVED,
                        snapshot.fingerprint().treeDigest()));
            } catch (RuntimeException ignored) { /* 首个失败优先。 */ }
            return new UninstallResult(false, PluginErrorCode.UNINSTALL_TOMBSTONED);
        }
    }

    private void cleanupBackupAfterDeletion(Path backup) {
        safeDelete(backup);
        try { durability.force(storeRoot); }
        catch (IOException ignored) { /* orphan backup 不会被 loader 读取。 */ }
    }

    private void rollbackIndex(Path index, Path backup, boolean published, boolean backedUp) {
        try {
            if (published) Files.deleteIfExists(index);
            if (backedUp) Files.move(backup, index, StandardCopyOption.ATOMIC_MOVE);
            if (published || backedUp) durability.force(storeRoot);
        } catch (IOException ignored) {
            // 无法安全恢复时保持 tombstone；不声称 S14 崩溃修复。
        }
    }

    private static void writeStage(Path target, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private void safeDelete(Path path) {
        try { trees.delete(path); }
        catch (IOException | PluginBoundaryException ignored) { /* 留待安全维护。 */ }
    }

    private Path child(String name) {
        Path path = storeRoot.resolve(name).normalize();
        if (!path.startsWith(storeRoot) || path.equals(storeRoot)) {
            throw new PluginBoundaryException(PluginErrorCode.PATH_REJECTED);
        }
        return path;
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    enum FaultPoint {
        BEFORE_DIRECTORY_DELETE,
        AFTER_DIRECTORY_DELETE,
        BEFORE_BACKUP_DELETE
    }

    @FunctionalInterface
    interface FaultInjector { void at(FaultPoint point) throws IOException; }

    /**
     * 不暴露路径或 manifest 的卸载终态。
     *
     * @param removed 是否完成 quiesce、registry 发布与目录删除
     * @param errorCode 失败时的封闭错误；成功时为空
     */
    public record UninstallResult(boolean removed, PluginErrorCode errorCode) {
        /** 校验成功与错误码恰有一个。 */
        public UninstallResult {
            if (removed == (errorCode != null)) throw new IllegalArgumentException("卸载终态不一致");
        }
    }
}
