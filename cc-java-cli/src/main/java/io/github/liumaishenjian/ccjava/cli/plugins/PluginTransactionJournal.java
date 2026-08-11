package io.github.liumaishenjian.ccjava.cli.plugins;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plugin install/uninstall/registry migration 的 append-only transaction recovery journal。
 *
 * <p>记录只含 transaction/plugin identity、固定 phase 和 digest，不含 manifest 正文或路径。
 * 每次 append 使用进程级 file lock 与 force；replay 流式读取并允许忽略一次未换行的截断尾记录，
 * 中部损坏、链接、超限或并发边界变化均 Fail Closed。Installer/Uninstaller 在真实事务边界写入
 * 本 journal，并由 {@link PluginTransactionRecovery} 在 writer fence 内完成重启对账。</p>
 *
 * @since 0.1.0
 */
public final class PluginTransactionJournal {
    static final int MAX_RECORDS = 100_000;
    static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    static final int MAX_RECORD_CHARS = 4096;

    private final Path root;
    private final Path file;
    private final Path lockFile;

    /**
     * 在固定 Plugin root 初始化 append-only transaction journal。
     *
     * @param root Plugin store root
     */
    public PluginTransactionJournal(Path root) {
        try {
            Path normalized = Objects.requireNonNull(root, "root 不能为空")
                    .toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("plugin root 不能是链接");
            }
            this.root = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
            file = child("transactions-v1.log");
            lockFile = child("transactions-v1.lock");
            verifyAbsentOrRegular(file);
            verifyAbsentOrRegular(lockFile);
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建 plugin transaction journal", failure);
        }
    }

    /**
     * Durable append 一条固定格式状态迁移。
     *
     * @param record 已校验且绑定事务 digest 的状态记录
     */
    public synchronized void append(PluginTransactionRecord record) {
        Objects.requireNonNull(record, "record 不能为空");
        String line = record.transactionId() + "\t" + record.pluginId() + "\t"
                + record.operation() + "\t" + record.phase() + "\t" + record.digest() + "\n";
        if (line.length() > MAX_RECORD_CHARS) {
            throw new IllegalArgumentException("plugin transaction record 超限");
        }
        try {
            verifyRoot();
            verifyAbsentOrRegular(file);
            verifyAbsentOrRegular(lockFile);
            try (FileChannel lockChannel = FileChannel.open(
                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = lockChannel.lock()) {
                long current = Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? Files.size(file) : 0;
                if (current + line.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                    throw new IOException("journal 文件超限");
                }
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("plugin transaction durable append 失败", failure);
        }
    }

    /**
     * 流式回放完整记录；仅允许文件末尾一个未换行的截断记录被忽略。
     *
     * @return journal 中按持久化顺序排列的完整记录
     */
    public synchronized List<PluginTransactionRecord> replay() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            verifyRoot();
            verifyRegular(file);
            boolean endsWithNewline = endsWithNewline(file);
            ArrayList<PluginTransactionRecord> result = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > MAX_RECORD_CHARS || result.size() >= MAX_RECORDS) {
                        throw new IOException("journal 记录超限");
                    }
                    try {
                        result.add(PluginTransactionRecord.parse(line));
                    } catch (RuntimeException malformed) {
                        if (!endsWithNewline && reader.readLine() == null) {
                            break;
                        }
                        throw new IOException("journal 中部记录损坏", malformed);
                    }
                }
            }
            return List.copyOf(result);
        } catch (IOException failure) {
            throw new IllegalStateException("plugin transaction replay 失败", failure);
        }
    }

    private Path child(String name) throws IOException {
        Path candidate = root.resolve(name).normalize();
        if (!root.equals(candidate.getParent())) {
            throw new IOException("plugin journal child 越界");
        }
        return candidate;
    }

    private void verifyRoot() throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(root)) {
            throw new IOException("plugin root 边界变化");
        }
    }

    private static void verifyAbsentOrRegular(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            verifyRegular(path);
        }
    }

    private static void verifyRegular(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("journal 非法");
        }
    }

    private static boolean endsWithNewline(Path path) throws IOException {
        long size = Files.size(path);
        if (size == 0) {
            return true;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            java.nio.ByteBuffer last = java.nio.ByteBuffer.allocate(1);
            channel.position(size - 1);
            channel.read(last);
            return last.array()[0] == (byte) '\n';
        }
    }
}
