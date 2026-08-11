package io.github.liumaishenjian.ccjava.cli.daemon;

import io.github.liumaishenjian.ccjava.protocol.CapabilityToken;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * 本机 Daemon 的单实例 ownership 与 capability token 资源。
 *
 * <p>文件锁只解决本机单实例，不是账户认证、分布式锁或 OS Sandbox。</p>
 *
 * @since 0.1.0
 */
public final class DaemonOwnership implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final CapabilityToken token;

    private DaemonOwnership(FileChannel channel, FileLock lock, CapabilityToken token) {
        this.channel = channel;
        this.lock = lock;
        this.token = token;
    }

    /**
     * 原子取得固定 root 下的本机单实例 ownership。
     *
     * @param root 固定 daemon 状态根
     * @return 持有 FileLock 与进程内 token 的资源
     * @throws IOException root 不安全、锁已被持有或锁文件不可用时
     */
    public static DaemonOwnership acquire(Path root) throws IOException {
        Path checked = Objects.requireNonNull(root, "root 不能为空").toAbsolutePath().normalize();
        Files.createDirectories(checked);
        if (Files.isSymbolicLink(checked)) {
            throw new IOException("daemon root 不能是链接");
        }
        FileChannel channel = FileChannel.open(
                checked.resolve("daemon.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock();
        if (lock == null) {
            channel.close();
            throw new IOException("daemon 已在运行");
        }
        return new DaemonOwnership(channel, lock, CapabilityToken.generate());
    }

    /**
     * 查询与当前 ownership 生命周期绑定的认证 token。
     *
     * @return 当前进程独占持有且不会持久化的高熵 capability token
     */
    public CapabilityToken token() {
        return token;
    }

    /** 释放本机单实例锁，使后续进程能够重新取得 ownership。 */
    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
