package io.github.liumaishenjian.ccjava.cli.plugins;

import io.github.liumaishenjian.ccjava.core.plugin.PluginActivation;
import io.github.liumaishenjian.ccjava.core.plugin.PluginRegistry;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginErrorCode;
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
 * directory-only Plugin staged install Adapter。
 *
 * <p>事务顺序为 prepare activation → immutable package publish → staged registry publish →
 * in-memory activation commit。registry replace 前先以原子 rename 保存旧 index；activation 或后续
 * durability 失败时原子恢复旧 index，再 rollback in-memory generation。任一步失败都不会留下
 * index、目录与 active snapshot 不一致；清理使用共享 NOFOLLOW/reparse 安全边界。</p>
 *
 * @since 0.11.0
 */
public final class DirectoryPluginInstaller {
    private final Path storeRoot;
    private final PluginPackageLoader loader;
    private final PluginRegistry registry;
    private final FaultInjector faults;
    private final DirectoryDurability durability;
    private final SafePluginTreeOperator trees;

    /**
     * 创建绑定 store、strict loader 与运行 registry 的 installer。
     *
     * @param storeRoot Plugin store root
     * @param loader strict package loader
     * @param registry trust 与 activation registry
     */
    public DirectoryPluginInstaller(Path storeRoot, PluginPackageLoader loader, PluginRegistry registry) {
        this(storeRoot, loader, registry, point -> { }, DirectoryPluginInstaller::forceDirectory,
                new SafePluginTreeOperator());
    }

    DirectoryPluginInstaller(Path storeRoot, PluginPackageLoader loader, PluginRegistry registry,
            FaultInjector faults) {
        this(storeRoot, loader, registry, faults, DirectoryPluginInstaller::forceDirectory,
                new SafePluginTreeOperator());
    }

    DirectoryPluginInstaller(Path storeRoot, PluginPackageLoader loader, PluginRegistry registry,
            FaultInjector faults, DirectoryDurability durability) {
        this(storeRoot, loader, registry, faults, durability, new SafePluginTreeOperator());
    }

    DirectoryPluginInstaller(Path storeRoot, PluginPackageLoader loader, PluginRegistry registry,
            FaultInjector faults, DirectoryDurability durability, SafePluginTreeOperator trees) {
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot 不能为空").toAbsolutePath().normalize();
        this.loader = Objects.requireNonNull(loader, "loader 不能为空");
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.faults = Objects.requireNonNull(faults, "faults 不能为空");
        this.durability = Objects.requireNonNull(durability, "durability 不能为空");
        this.trees = Objects.requireNonNull(trees, "trees 不能为空");
    }

    /**
     * 安装并激活；全程持有 global writer，失败不返回物理路径或异常正文。
     *
     * @param sourceDirectory 调用方选择的 ordinary directory package
     * @return 发布并激活的 immutable snapshot
     */
    public PluginSnapshot install(Path sourceDirectory) {
        try (PluginGlobalWriterLease lease = PluginGlobalWriterLease.acquire(storeRoot)) {
            PluginTransactionRecovery.RecoveryResult recovery = new PluginTransactionRecovery(storeRoot).recover(lease);
            if (!recovery.clean()) throw failure(PluginErrorCode.INSTALL_FAILED);
            return installLocked(sourceDirectory);
        } catch (IOException failure) {
            throw failure(PluginErrorCode.INSTALL_FAILED);
        }
    }

    private PluginSnapshot installLocked(Path sourceDirectory) {
        PluginSnapshot source = loader.load(sourceDirectory);
        if (!registry.isTrusted(source)) throw failure(PluginErrorCode.FINGERPRINT_UNTRUSTED);
        String id = source.manifest().id().value();
        String nonce = source.fingerprint().treeDigest().substring(0, 16);
        Path staging = child(".staging-" + id + "-" + nonce);
        Path activeDirectory = child(PluginRegistryIndex.directoryName(
                id, source.fingerprint().treeDigest()));
        Path registryFile = child("registry.v1");
        Path registryStage = child(".registry-" + nonce + ".tmp");
        Path registryBackup = child(".registry-" + nonce + ".bak");
        String transactionId = "install-" + id + "-" + nonce;
        PluginTransactionJournal journal = new PluginTransactionJournal(storeRoot);
        boolean packagePublished = false;
        boolean oldIndexBackedUp = false;
        boolean newIndexPublished = false;
        PluginActivation activation = null;
        try {
            Files.createDirectories(storeRoot);
            journal.append(new PluginTransactionRecord(transactionId, id,
                    PluginTransactionOperation.INSTALL, PluginTransactionPhase.PREPARED,
                    source.fingerprint().treeDigest()));
            activation = registry.prepareActivation(source);
            faults.at(FaultPoint.AFTER_ACTIVATION_PREPARE);
            faults.at(FaultPoint.BEFORE_STAGING_CREATE);
            Files.createDirectory(staging);
            copyTree(sourceDirectory.toAbsolutePath().normalize(), staging);
            durability.force(staging);
            journal.append(new PluginTransactionRecord(transactionId, id,
                    PluginTransactionOperation.INSTALL, PluginTransactionPhase.STAGED,
                    source.fingerprint().treeDigest()));
            PluginSnapshot staged = loader.load(staging);
            if (!staged.fingerprint().equals(source.fingerprint())) throw failure(PluginErrorCode.CONTENT_CHANGED);
            journal.append(new PluginTransactionRecord(transactionId, id,
                    PluginTransactionOperation.INSTALL, PluginTransactionPhase.VERIFIED,
                    source.fingerprint().treeDigest()));
            faults.at(FaultPoint.BEFORE_PACKAGE_RENAME);
            Files.move(staging, activeDirectory, StandardCopyOption.ATOMIC_MOVE);
            packagePublished = true;
            durability.force(storeRoot);
            journal.append(new PluginTransactionRecord(transactionId, id,
                    PluginTransactionOperation.INSTALL, PluginTransactionPhase.PUBLISHED,
                    source.fingerprint().treeDigest()));

            faults.at(FaultPoint.BEFORE_REGISTRY_STAGE);
            writeRegistryStage(registryStage,
                    PluginRegistryIndex.replacing(PluginRegistryIndex.read(registryFile), source));
            faults.at(FaultPoint.BEFORE_REGISTRY_BACKUP);
            if (Files.exists(registryFile, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(registryFile, registryBackup, StandardCopyOption.ATOMIC_MOVE);
                oldIndexBackedUp = true;
                durability.force(storeRoot);
            }
            faults.at(FaultPoint.BEFORE_REGISTRY_REPLACE);
            Files.move(registryStage, registryFile, StandardCopyOption.ATOMIC_MOVE);
            newIndexPublished = true;
            durability.force(storeRoot);
            faults.at(FaultPoint.AFTER_REGISTRY_REPLACE);

            journal.append(new PluginTransactionRecord(transactionId, id,
                    PluginTransactionOperation.INSTALL, PluginTransactionPhase.REGISTRY_COMMITTED,
                    source.fingerprint().treeDigest()));
            activation.commit();
            faults.at(FaultPoint.AFTER_ACTIVATION_COMMIT);
            if (oldIndexBackedUp) {
                Files.delete(registryBackup);
                durability.force(storeRoot);
            }
            cleanupRetiredPackages();
            journal.append(new PluginTransactionRecord(transactionId, id,
                    PluginTransactionOperation.INSTALL, PluginTransactionPhase.COMPLETED,
                    source.fingerprint().treeDigest()));
            return loader.load(activeDirectory);
        } catch (Exception failure) {
            boolean durableRolledBack = rollbackIndex(
                    registryFile, registryBackup, newIndexPublished, oldIndexBackedUp);
            if (activation != null && durableRolledBack) {
                try { activation.rollback(); } catch (RuntimeException ignored) { /* 保留首个 fail-closed 终态。 */ }
            }
            safeCleanup(staging, registryStage);
            if (packagePublished && durableRolledBack) safeCleanup(activeDirectory);
            try {
                journal.append(new PluginTransactionRecord(transactionId, id,
                        PluginTransactionOperation.INSTALL, PluginTransactionPhase.FAILED_PRESERVED,
                        source.fingerprint().treeDigest()));
            } catch (RuntimeException ignored) { /* 原失败优先，恢复器下次保守对账。 */ }
            throw failure instanceof PluginBoundaryException boundary && durableRolledBack
                    ? boundary : failure(PluginErrorCode.INSTALL_FAILED);
        } finally {
            if (activation != null) activation.close();
        }
    }

    private boolean rollbackIndex(Path index, Path backup, boolean newPublished, boolean backedUp) {
        try {
            if (newPublished) Files.deleteIfExists(index);
            if (backedUp) Files.move(backup, index, StandardCopyOption.ATOMIC_MOVE);
            if (newPublished || backedUp) durability.force(storeRoot);
            return true;
        } catch (IOException rollbackFailure) {
            return false;
        }
    }

    private void copyTree(Path source, Path staging) throws IOException {
        trees.copy(source, staging, new SafePluginTreeOperator.CopyObserver() {
            @Override public void beforeFile(Path ignored) throws IOException {
                faults.at(FaultPoint.BEFORE_FILE_COPY);
            }
            @Override public void afterFile(Path ignored) throws IOException {
                faults.at(FaultPoint.AFTER_FILE_FLUSH);
            }
        });
    }

    private void writeRegistryStage(Path target, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private void cleanupRetiredPackages() {
        for (var retired : registry.drainRetiredReady()) {
            Path directory = child(PluginRegistryIndex.directoryName(
                    retired.snapshot().manifest().id().value(),
                    retired.snapshot().fingerprint().treeDigest()));
            safeCleanup(directory);
        }
    }

    private void safeCleanup(Path... paths) {
        for (Path path : paths) {
            try { trees.delete(path); }
            catch (IOException | PluginBoundaryException ignored) {
                // Orphan 留待安全维护；绝不越过 link/reparse 继续删除。
            }
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    private Path child(String name) {
        Path path = storeRoot.resolve(name).normalize();
        if (!path.startsWith(storeRoot) || path.equals(storeRoot)) throw failure(PluginErrorCode.PATH_REJECTED);
        return path;
    }

    private static PluginBoundaryException failure(PluginErrorCode code) {
        return new PluginBoundaryException(code);
    }

    enum FaultPoint {
        AFTER_ACTIVATION_PREPARE,
        BEFORE_STAGING_CREATE,
        BEFORE_FILE_COPY,
        AFTER_FILE_FLUSH,
        BEFORE_PACKAGE_RENAME,
        BEFORE_REGISTRY_STAGE,
        BEFORE_REGISTRY_BACKUP,
        BEFORE_REGISTRY_REPLACE,
        AFTER_REGISTRY_REPLACE,
        AFTER_ACTIVATION_COMMIT
    }

    @FunctionalInterface
    interface FaultInjector { void at(FaultPoint point) throws IOException; }
}
