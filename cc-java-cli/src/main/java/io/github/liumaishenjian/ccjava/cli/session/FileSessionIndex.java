package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.core.session.InMemorySessionIndex;
import io.github.liumaishenjian.ccjava.core.session.SessionIndex;
import io.github.liumaishenjian.ccjava.core.session.SessionIndexEntry;
import io.github.liumaishenjian.ccjava.core.session.SessionLifecycleStatus;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 普通文件持久化的 SessionIndex projection；文件损坏时可从 canonical metadata 重建。
 *
 * <p>格式是 cc-java 内部派生 TSV v1，仅编码 Base64 metadata，不包含正文或绝对路径。每次
 * publish 都持有进程级 file lock，重新验证 root/file/tmp 的 NOFOLLOW 与 realpath 边界，并使用
 * 同目录 staging + atomic move；不支持 atomic move 的文件系统使用已加锁的 replace fallback。
 * 记录和文件大小有显式上限，超过边界时 Fail Closed。</p>
 *
 * @since 0.1.0
 */
public final class FileSessionIndex implements SessionIndex {
    static final int MAX_RECORDS = 100_000;
    static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    static final int MAX_RECORD_CHARS = 16 * 1024;
    private static final int PAGE_SIZE = 1000;
    private static final AtomicLong STAGING_NONCE = new AtomicLong();

    private final Path root;
    private final Path file;
    private final Path lockFile;
    private final InMemorySessionIndex delegate = new InMemorySessionIndex();

    /**
     * 打开固定索引目录并加载既有派生投影。
     *
     * @param root Session index 专用根目录
     */
    public FileSessionIndex(Path root) {
        try {
            this.root = prepareRoot(root);
            file = child("session-index-v1.tsv");
            lockFile = child("session-index-v1.lock");
            load();
        } catch (IOException failure) {
            throw new IllegalStateException("无法打开 Session index", failure);
        }
    }

    @Override
    public synchronized void upsert(SessionIndexEntry entry) {
        boolean existed = delegate.find(entry.sessionId()).isPresent();
        if (!existed && countEntries() >= MAX_RECORDS) {
            throw new IllegalStateException("Session index 记录超限");
        }
        delegate.upsert(entry);
        persist();
    }

    @Override
    public synchronized void remove(String sessionId) {
        delegate.remove(sessionId);
        persist();
    }

    @Override
    public synchronized List<SessionIndexEntry> list(int offset, int limit) {
        return delegate.list(offset, limit);
    }

    @Override
    public synchronized List<SessionIndexEntry> search(String query, int limit) {
        return delegate.search(query, limit);
    }

    @Override
    public synchronized Optional<SessionIndexEntry> find(String sessionId) {
        return delegate.find(sessionId);
    }

    @Override
    public synchronized void rebuild(Iterable<SessionIndexEntry> entries) {
        ArrayList<SessionIndexEntry> checked = new ArrayList<>();
        for (SessionIndexEntry entry : entries) {
            if (checked.size() >= MAX_RECORDS) {
                throw new IllegalArgumentException("Session index 重建记录超限");
            }
            checked.add(entry);
        }
        delegate.rebuild(checked);
        persist();
    }

    private void load() throws IOException {
        verifyRoot();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        verifyRegularFile(file, MAX_FILE_BYTES);
        ArrayList<SessionIndexEntry> loaded = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_RECORD_CHARS || loaded.size() >= MAX_RECORDS) {
                    throw new IOException("index 记录超限");
                }
                loaded.add(parse(line));
            }
        }
        delegate.rebuild(loaded);
    }

    private SessionIndexEntry parse(String line) throws IOException {
        try {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 5) {
                throw new IOException("index record 非法");
            }
            return new SessionIndexEntry(
                    decode(fields[0]), decode(fields[1]), decode(fields[2]),
                    Instant.parse(fields[3]), SessionLifecycleStatus.valueOf(fields[4]));
        } catch (IllegalArgumentException failure) {
            throw new IOException("index record 非法", failure);
        }
    }

    private void persist() {
        Path staged = child("session-index-v1.tmp-" + ProcessHandle.current().pid()
                + "-" + STAGING_NONCE.incrementAndGet());
        try {
            verifyRoot();
            verifyAbsentOrRegular(file);
            verifyAbsentOrRegular(lockFile);
            try (FileChannel lockChannel = FileChannel.open(
                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = lockChannel.lock()) {
                verifyRoot();
                verifyAbsentOrRegular(file);
                writeSnapshot(staged);
                publish(staged);
                forceDirectory(root);
            }
        } catch (IOException failure) {
            safeDelete(staged);
            throw new IllegalStateException("无法持久化 Session index", failure);
        }
    }

    private void writeSnapshot(Path staged) throws IOException {
        if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("index staging 已存在");
        }
        int records = 0;
        long estimatedBytes = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(
                staged, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (int offset = 0; ; offset += PAGE_SIZE) {
                List<SessionIndexEntry> page = delegate.list(offset, PAGE_SIZE);
                if (page.isEmpty()) {
                    break;
                }
                for (SessionIndexEntry entry : page) {
                    if (++records > MAX_RECORDS) {
                        throw new IOException("index 记录超限");
                    }
                    String line = serialize(entry);
                    estimatedBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                    if (line.length() > MAX_RECORD_CHARS || estimatedBytes > MAX_FILE_BYTES) {
                        throw new IOException("index 文件超限");
                    }
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
        verifyRegularFile(staged, MAX_FILE_BYTES);
        try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void publish(Path staged) throws IOException {
        try {
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private int countEntries() {
        int count = 0;
        for (int offset = 0; ; offset += PAGE_SIZE) {
            int size = delegate.list(offset, PAGE_SIZE).size();
            count += size;
            if (size < PAGE_SIZE) {
                return count;
            }
        }
    }

    private static String serialize(SessionIndexEntry entry) {
        return encode(entry.sessionId()) + '\t' + encode(entry.workspaceIdentity()) + '\t'
                + encode(entry.displayName()) + '\t' + entry.updatedAt() + '\t' + entry.status();
    }

    private Path child(String name) {
        Path candidate = root.resolve(name).normalize();
        if (!candidate.getParent().equals(root)) {
            throw new IllegalArgumentException("Session index child 越界");
        }
        return candidate;
    }

    private void verifyRoot() throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(root)) {
            throw new IOException("index root 边界变化");
        }
    }

    private static Path prepareRoot(Path value) throws IOException {
        Path normalized = java.util.Objects.requireNonNull(value, "root 不能为空")
                .toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("index root 不能是链接");
        }
        return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static void verifyAbsentOrRegular(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            verifyRegularFile(path, MAX_FILE_BYTES);
        }
    }

    private static void verifyRegularFile(Path path, long maxBytes) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > maxBytes) {
            throw new IOException("index 文件非法");
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
            // 部分 Windows 文件系统不支持 directory force；文件本身已 force。
        }
    }

    private static void safeDelete(Path path) {
        try {
            if (!Files.isSymbolicLink(path)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // 同目录 orphan 不会被 loader 读取，留待维护。
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
