package io.github.liumaishenjian.ccjava.cli.plugins;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * 串行化 Plugin recovery、install、uninstall 与 registry migration 的机器本地 writer lease。
 *
 * <p>调用方必须在读取 transaction journal 之前取得 lease，并持有到 registry、package、内存
 * generation 与最终 transaction phase 全部收敛之后。该 lease 不替代 transaction journal，
 * 也不允许在无法取得锁时降级为无锁执行。</p>
 *
 * @since 0.1.0
 */
public final class PluginGlobalWriterLease implements AutoCloseable {
    private final Path root;
    private final FileChannel channel;
    private final FileLock lock;

    private PluginGlobalWriterLease(Path root, FileChannel channel, FileLock lock) {
        this.root = root;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * 非阻塞取得全局 writer；已有本进程或其他进程 writer 时 Fail Closed。
     *
     * @param value Plugin store root
     * @return 必须关闭的独占 lease
     * @throws IOException root/lock 身份无效或无法取得全局锁时
     */
    public static PluginGlobalWriterLease acquire(Path value) throws IOException {
        Path normalized = Objects.requireNonNull(value, "root 不能为空").toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("plugin root 非法");
        }
        Path root = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path file = root.resolve("plugin-writer.lock").normalize();
        if (!root.equals(file.getParent()) || Files.isSymbolicLink(file)) throw new IOException("writer path 非法");
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException active) {
                lock = null;
            }
            if (lock == null) throw new IOException("PLUGIN_WRITER_ACTIVE");
            return new PluginGlobalWriterLease(root, channel, lock);
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }
    }

    Path root() { return root; }

    void requireRoot(Path expected) throws IOException {
        if (!root.equals(expected.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("writer root 不匹配");
        }
        if (!lock.isValid()) throw new IOException("writer lease 已失效");
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
