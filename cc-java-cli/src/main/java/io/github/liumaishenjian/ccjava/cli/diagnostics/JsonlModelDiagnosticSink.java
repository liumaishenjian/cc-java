package io.github.liumaishenjian.ccjava.cli.diagnostics;

import io.github.liumaishenjian.ccjava.core.ModelDiagnosticSink;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 将封闭的 {@link ModelDiagnosticEvent} 以 best-effort JSONL 写入本机私有目录。
 *
 * <p>调用线程只执行有界序列化和非阻塞入队；单写线程负责轮转和保留清理。初始化在写线程
 * 启动前完成，使 Composition Root 能把路径、权限和创建失败安全降级为 OFF。记录接纳与关闭
 * 标记使用同一把锁串行化，因此关闭标记之后不会再接纳事件，已接纳事件也不会为插入标记而丢弃。</p>
 *
 * @since 0.1.0
 */
public final class JsonlModelDiagnosticSink implements ModelDiagnosticSink, AutoCloseable {

    public static final int MAX_RECORD_BYTES = 4 * 1024;
    public static final long MAX_FILE_BYTES = 1024L * 1024L;
    public static final int MAX_FILES = 5;
    public static final int QUEUE_CAPACITY = 256;
    public static final Duration MAX_AGE = Duration.ofDays(7);

    private static final String PREFIX = "model-diagnostics-";
    private static final String SUFFIX = ".jsonl";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);
    private static final byte[] STOP = new byte[0];
    private static final long CLOSE_WAIT_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private final Path directory;
    private final Clock clock;
    private final int recordCapacity;
    private final ArrayBlockingQueue<byte[]> queue;
    private final long maxFileBytes;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final Object lifecycle = new Object();
    private final Thread worker;
    private boolean accepting = true;
    private boolean stopQueued;
    private BufferedWriter writer;
    private Path activeFile;
    private long activeBytes;
    private long ordinal;

    /**
     * 创建并启动异步 sink。
     *
     * @param directory 可信本机诊断目录，必须是规范化绝对路径且不得包含链接或 reparse component
     * @throws IllegalArgumentException 路径不满足本机安全约束
     * @throws IllegalStateException 目录创建或权限强化失败
     */
    public JsonlModelDiagnosticSink(Path directory) {
        this(directory, Clock.systemUTC(), QUEUE_CAPACITY, MAX_FILE_BYTES);
    }

    JsonlModelDiagnosticSink(Path directory, Clock clock, int queueCapacity) {
        this(directory, clock, queueCapacity, MAX_FILE_BYTES);
    }

    JsonlModelDiagnosticSink(Path directory, Clock clock, int queueCapacity, long maxFileBytes) {
        this(directory, clock, queueCapacity, maxFileBytes, new NioPathAccess());
    }

    JsonlModelDiagnosticSink(
            Path directory,
            Clock clock,
            int queueCapacity,
            long maxFileBytes,
            PathAccess pathAccess) {
        this.directory = validateTarget(directory, pathAccess);
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        if (maxFileBytes < MAX_RECORD_BYTES || maxFileBytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("maxFileBytes 超出边界");
        }
        if (queueCapacity < 1 || queueCapacity > QUEUE_CAPACITY) {
            throw new IllegalArgumentException("queueCapacity 超出边界");
        }
        this.maxFileBytes = maxFileBytes;
        this.recordCapacity = queueCapacity;
        // 为控制标记保留独立槽位；数据容量仍严格受 queueCapacity 限制。
        queue = new ArrayBlockingQueue<>(queueCapacity + 1);
        try {
            initializeDirectory(pathAccess);
        } catch (IOException | RuntimeException failure) {
            closeWriter();
            throw new IllegalStateException("model diagnostics unavailable");
        }
        worker = Thread.ofPlatform()
                .name("cc-java-model-diagnostics")
                .daemon(true)
                .unstarted(this::writeLoop);
        worker.start();
    }

    @Override
    public void record(ModelDiagnosticEvent event) {
        if (event == null) {
            return;
        }
        final byte[] line;
        try {
            line = serialize(event);
        } catch (RuntimeException ignored) {
            failures.incrementAndGet();
            return;
        }
        synchronized (lifecycle) {
            if (!accepting) {
                return;
            }
            if (line.length > MAX_RECORD_BYTES || queue.size() >= recordCapacity || !queue.offer(line)) {
                dropped.incrementAndGet();
            }
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long failureCount() {
        return failures.get();
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (accepting) {
                accepting = false;
            }
            if (!stopQueued) {
                // 保留槽位保证该操作非阻塞且不丢弃任何已接纳事件。
                if (!queue.offer(STOP)) {
                    failures.incrementAndGet();
                    worker.interrupt();
                } else {
                    stopQueued = true;
                }
            }
        }
        awaitWorker();
    }

    private void awaitWorker() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MILLIS);
        boolean interrupted = false;
        while (worker.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                failures.incrementAndGet();
                worker.interrupt();
                break;
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
            } catch (InterruptedException ignored) {
                // 关闭仍须回收写线程；完成后恢复调用线程的中断状态。
                interrupted = true;
                failures.incrementAndGet();
            }
        }
        if (worker.isAlive()) {
            try {
                worker.join(CLOSE_WAIT_MILLIS);
            } catch (InterruptedException ignored) {
                interrupted = true;
                failures.incrementAndGet();
            }
        }
        if (worker.isAlive()) {
            failures.incrementAndGet();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                byte[] line = queue.take();
                if (line == STOP) {
                    break;
                }
                append(line);
            }
            if (writer != null) {
                writer.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.incrementAndGet();
        } catch (Exception ignored) {
            failures.incrementAndGet();
            synchronized (lifecycle) {
                accepting = false;
            }
        } finally {
            closeWriter();
        }
    }

    private void initializeDirectory(PathAccess pathAccess) throws IOException {
        rejectExistingLinkComponents(directory, pathAccess);
        Files.createDirectories(directory);
        rejectExistingLinkComponents(directory, pathAccess);
        hardenDirectory(directory);
        prune();
    }

    private void append(byte[] line) throws IOException {
        if (writer == null || activeBytes + line.length > maxFileBytes) {
            rotate();
        }
        writer.write(new String(line, StandardCharsets.UTF_8));
        writer.flush();
        activeBytes += line.length;
    }

    private void rotate() throws IOException {
        closeWriter();
        prune();
        String stamp = FILE_TIME.format(clock.instant());
        Path temporary = directory.resolve("." + PREFIX + stamp + "-" + ordinal + ".tmp");
        Path destination = directory.resolve(PREFIX + stamp + "-" + ordinal++ + SUFFIX);
        Files.createFile(temporary);
        hardenFile(temporary);
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, destination);
        }
        hardenFile(destination);
        activeFile = destination;
        activeBytes = Files.size(destination);
        writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        prune();
    }

    private void prune() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, PREFIX + "*" + SUFFIX)) {
            for (Path candidate : stream) {
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(candidate)) {
                    files.add(candidate);
                }
            }
        }
        Instant cutoff = clock.instant().minus(MAX_AGE);
        for (Path file : List.copyOf(files)) {
            FileTime modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
            if (modified.toInstant().isBefore(cutoff) && !file.equals(activeFile)) {
                Files.deleteIfExists(file);
                files.remove(file);
            }
        }
        files.sort(Comparator.comparing(this::safeModified).reversed());
        int target = MAX_FILES - (activeFile == null ? 1 : 0);
        for (int index = files.size() - 1; files.size() > target && index >= 0; index--) {
            Path file = files.get(index);
            if (!file.equals(activeFile)) {
                Files.deleteIfExists(file);
                files.remove(index);
            }
        }
    }

    private FileTime safeModified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ignored) {
            return FileTime.fromMillis(0L);
        }
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                failures.incrementAndGet();
            } finally {
                writer = null;
            }
        }
    }

    private static byte[] serialize(ModelDiagnosticEvent event) {
        String json = "{\"schemaVersion\":" + event.schemaVersion()
                + ",\"kind\":\"" + event.kind().name()
                + "\",\"sessionCorrelation\":\"" + event.sessionCorrelation()
                + "\",\"runCorrelation\":\"" + event.runCorrelation()
                + "\",\"turnNumber\":" + event.turnNumber()
                + ",\"attemptNumber\":" + event.attemptNumber()
                + ",\"stage\":\"" + event.stage().name()
                + "\",\"reason\":\"" + event.reason().name()
                + "\",\"statusClass\":\"" + event.statusClass().name()
                + "\",\"receivedProviderFrame\":" + event.receivedProviderFrame()
                + ",\"emittedUserText\":" + event.emittedUserText()
                + ",\"elapsedMillis\":" + event.elapsedMillis()
                + ",\"recordedAt\":\"" + event.recordedAt() + "\"}\n";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static Path validateTarget(Path target, PathAccess pathAccess) {
        Path path = Objects.requireNonNull(target, "directory 不能为空");
        Objects.requireNonNull(pathAccess, "pathAccess 不能为空");
        if (!path.isAbsolute() || !path.equals(path.normalize()) || path.getNameCount() < 2) {
            throw new IllegalArgumentException("诊断目录必须是规范化绝对本机路径");
        }
        try {
            rejectExistingLinkComponents(path, pathAccess);
        } catch (IOException failure) {
            throw new IllegalArgumentException("诊断目录不安全");
        }
        return path;
    }

    private static void rejectExistingLinkComponents(Path path, PathAccess access) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (!access.exists(current)) {
                continue;
            }
            BasicFileAttributes attributes = access.attributes(current);
            if (access.isSymbolicLink(current) || attributes.isSymbolicLink() || attributes.isOther()
                    || !current.toAbsolutePath().normalize().equals(access.noFollowRealPath(current))) {
                throw new IOException("unsafe diagnostic path");
            }
        }
    }

    private static void hardenDirectory(Path path) throws IOException {
        harden(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), true);
    }

    private static void hardenFile(Path path) throws IOException {
        harden(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), false);
    }

    private static void harden(Path path, Set<PosixFilePermission> posixPermissions, boolean directory)
            throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(posixPermissions);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        FileOwnerAttributeView ownerView = Files.getFileAttributeView(
                path, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null || ownerView == null) {
            throw new IOException("平台不支持安全权限强化");
        }
        Set<AclEntryPermission> permissions = EnumSet.of(
                AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.READ_ACL,
                AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.WRITE_ACL, AclEntryPermission.SYNCHRONIZE);
        if (directory) {
            permissions.add(AclEntryPermission.EXECUTE);
            permissions.add(AclEntryPermission.DELETE_CHILD);
        }
        AclEntry.Builder builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(ownerView.getOwner())
                .setPermissions(permissions);
        if (directory) {
            builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        acl.setAcl(List.of(builder.build()));
    }

    /** 文件系统接缝用于确定性证伪 Windows reparse 与 no-follow realpath。 */
    interface PathAccess {
        boolean exists(Path path);
        boolean isSymbolicLink(Path path);
        BasicFileAttributes attributes(Path path) throws IOException;
        Path noFollowRealPath(Path path) throws IOException;
    }

    private static final class NioPathAccess implements PathAccess {
        @Override
        public boolean exists(Path path) {
            return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public boolean isSymbolicLink(Path path) {
            return Files.isSymbolicLink(path);
        }

        @Override
        public BasicFileAttributes attributes(Path path) throws IOException {
            try {
                return Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException unsupported) {
                return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            }
        }

        @Override
        public Path noFollowRealPath(Path path) throws IOException {
            return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        }
    }
}
