package io.github.liumaishenjian.ccjava.tools.local.memory;

import io.github.liumaishenjian.ccjava.core.MemoryRepository;
import io.github.liumaishenjian.ccjava.domain.MemoryCatalog;
import io.github.liumaishenjian.ccjava.domain.MemoryIndex;
import io.github.liumaishenjian.ccjava.domain.MemoryKind;
import io.github.liumaishenjian.ccjava.domain.MemoryMutationDiagnostic;
import io.github.liumaishenjian.ccjava.domain.MemoryMutationDiagnosticKind;
import io.github.liumaishenjian.ccjava.domain.MemoryMutationResult;
import io.github.liumaishenjian.ccjava.domain.MemoryMutationStatus;
import io.github.liumaishenjian.ccjava.domain.MemoryTopic;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 在固定 memory root 内实现摘要保护的 M1 mutation 与 M2 原子持久重建。
 *
 * <p>所有读取使用 {@code NOFOLLOW_LINKS} 有界 channel；写入只在真实 root 中创建随机暂存文件，
 * Move 前重检 root、目标和 expected digest，并且只接受 {@code ATOMIC_MOVE}。安全或原子语义不可用时
 * Fail Closed，不回退普通读取、跨目录暂存或非原子覆盖。M1 成功后 Index 失败不会回滚已提交 topic，
 * 而是由 mutation result 报告结构化诊断。</p>
 *
 * <p>这是应用层持久化边界，不是 Permission、Session grant 或 OS Sandbox。</p>
 *
 * @since 0.7.0
 */
public final class FileMemoryRepository implements MemoryRepository {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Set<String> REQUIRED_FIELDS =
            Set.of("kind", "name", "description", "updated-at");
    private static final Set<java.nio.file.OpenOption> READ_NOFOLLOW =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    static final String STAGED_PREFIX = ".cc-java-memory-";
    static final String STAGED_SUFFIX = ".tmp";
    private static final Pattern STAGED_NAME = Pattern.compile(
            "\\.cc-java-memory-[0-9a-f]{32}\\.tmp");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;
    private final FileMemoryCatalogAdapter catalogAdapter;
    private final SecretCandidatePolicy secretPolicy;
    private final MutationObserver observer;
    private final AtomicMover mover;
    private final CreateLinker linker;
    private final StagedCleaner cleaner;

    /**
     * 固定一个已经存在、非链接且可解析为自身的 memory root。
     *
     * @param memoryRoot 由 Composition 提供、后续每次读写仍会验证的 M1 根目录
     */
    public FileMemoryRepository(Path memoryRoot) {
        this(
                memoryRoot,
                new SecretCandidatePolicy(),
                (phase, target) -> { },
                (source, target, replace) -> {
                    if (replace) {
                        Files.move(
                                source,
                                target,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                    }
                },
                Files::createLink,
                Files::deleteIfExists);
    }

    FileMemoryRepository(
            Path memoryRoot,
            SecretCandidatePolicy secretPolicy,
            MutationObserver observer,
            AtomicMover mover) {
        this(
                memoryRoot,
                secretPolicy,
                observer,
                mover,
                Files::createLink,
                Files::deleteIfExists);
    }

    FileMemoryRepository(
            Path memoryRoot,
            SecretCandidatePolicy secretPolicy,
            MutationObserver observer,
            AtomicMover mover,
            CreateLinker linker) {
        this(memoryRoot, secretPolicy, observer, mover, linker, Files::deleteIfExists);
    }

    FileMemoryRepository(
            Path memoryRoot,
            SecretCandidatePolicy secretPolicy,
            MutationObserver observer,
            AtomicMover mover,
            CreateLinker linker,
            StagedCleaner cleaner) {
        this.root = Objects.requireNonNull(memoryRoot, "memoryRoot 不能为空")
                .toAbsolutePath()
                .normalize();
        this.secretPolicy = Objects.requireNonNull(secretPolicy, "secretPolicy 不能为空");
        this.observer = Objects.requireNonNull(observer, "observer 不能为空");
        this.mover = Objects.requireNonNull(mover, "mover 不能为空");
        this.linker = Objects.requireNonNull(linker, "linker 不能为空");
        this.cleaner = Objects.requireNonNull(cleaner, "cleaner 不能为空");
        validateRoot();
        this.catalogAdapter = new FileMemoryCatalogAdapter(root);
    }

    @Override
    public Optional<MemoryTopic> loadTopic(String name) {
        if (!isSlug(name)) {
            return Optional.empty();
        }
        Path target = topicPath(name);
        try {
            return Optional.of(parseTopic(stableSnapshot(target), name));
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    @Override
    public MemoryMutationResult saveTopic(
            MemoryTopic topic,
            Optional<String> expectedDigest) {
        Objects.requireNonNull(topic, "topic 不能为空");
        expectedDigest = Objects.requireNonNull(expectedDigest, "expectedDigest 不能为空");
        boolean create = expectedDigest.isEmpty();
        if (create != topic.contentDigest().isEmpty()
                || expectedDigest.isPresent()
                        && !expectedDigest.get().equals(topic.contentDigest())) {
            return rejected(MemoryMutationDiagnosticKind.DIGEST_CONFLICT, topic.name());
        }
        if (expectedDigest.isPresent() && !isDigest(expectedDigest.get())) {
            return rejected(MemoryMutationDiagnosticKind.DIGEST_CONFLICT, topic.name());
        }

        byte[] serialized;
        try {
            serialized = serialize(topic);
        } catch (IllegalArgumentException limit) {
            return rejected(MemoryMutationDiagnosticKind.CONTENT_LIMIT_EXCEEDED, topic.name());
        }
        if (secretPolicy.isSecretCandidate(new String(serialized, StandardCharsets.UTF_8))) {
            return rejected(MemoryMutationDiagnosticKind.SECRET_CANDIDATE_REJECTED, topic.name());
        }

        Path target = topicPath(topic.name());
        Path staged = null;
        StableSnapshot before = null;
        try {
            validateRoot();
            if (create) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    return rejected(MemoryMutationDiagnosticKind.TOPIC_ALREADY_EXISTS, topic.name());
                }
                if (directoryEntryCountAtLimit()) {
                    return rejected(MemoryMutationDiagnosticKind.TOPIC_LIMIT_REACHED, topic.name());
                }
            } else {
                before = stableSnapshot(target);
                if (!sha256(before.bytes()).equals(expectedDigest.orElseThrow())) {
                    return rejected(MemoryMutationDiagnosticKind.DIGEST_CONFLICT, topic.name());
                }
            }

            staged = stage(serialized);
            observer.beforeCommit(create ? MutationPhase.CREATE : MutationPhase.UPDATE, target);
            validateRoot();
            if (create) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    return rejected(MemoryMutationDiagnosticKind.TOPIC_ALREADY_EXISTS, topic.name());
                }
            } else {
                StableSnapshot current = stableSnapshot(target);
                if (!sameStableFile(before, current)
                        || !sha256(current.bytes()).equals(expectedDigest.orElseThrow())) {
                    return rejected(MemoryMutationDiagnosticKind.FILE_CHANGED_DURING_COMMIT, topic.name());
                }
            }
            validateStaged(staged, serialized);
            if (create) {
                linker.link(target, staged);
                StableSnapshot published = stableSnapshot(target);
                StableSnapshot source = stableSnapshot(staged);
                if (!sameStableFile(source, published)
                        || !Arrays.equals(published.bytes(), serialized)) {
                    throw new UnsafePath();
                }
                deleteStaged(staged);
            } else {
                moveAtomic(staged, target, true);
            }
            staged = null;
            MemoryTopic persisted = new MemoryTopic(
                    topic.name(),
                    topic.kind(),
                    topic.description(),
                    topic.body(),
                    sha256(serialized),
                    topic.updatedAt());
            boolean indexFailed = !rebuildAndPersistIndex();
            return MemoryMutationResult.saved(
                    create ? MemoryMutationStatus.CREATED : MemoryMutationStatus.UPDATED,
                    persisted,
                    indexFailed);
        } catch (FileAlreadyExistsException conflict) {
            return rejected(MemoryMutationDiagnosticKind.TOPIC_ALREADY_EXISTS, topic.name());
        } catch (AtomicMoveNotSupportedException unsupported) {
            return rejected(MemoryMutationDiagnosticKind.ATOMIC_MOVE_UNAVAILABLE, topic.name());
        } catch (MissingTopic missing) {
            return rejected(MemoryMutationDiagnosticKind.TOPIC_NOT_FOUND, topic.name());
        } catch (UnsafePath unsafe) {
            return rejected(MemoryMutationDiagnosticKind.UNSAFE_PATH, topic.name());
        } catch (IOException | RuntimeException failure) {
            return rejected(MemoryMutationDiagnosticKind.IO_FAILURE, topic.name());
        } finally {
            deleteStaged(staged);
        }
    }

    @Override
    public MemoryMutationResult deleteTopic(String name, String expectedDigest) {
        if (!isSlug(name) || !isDigest(expectedDigest)) {
            return rejected(
                    isSlug(name) ? MemoryMutationDiagnosticKind.DIGEST_CONFLICT
                            : MemoryMutationDiagnosticKind.UNSAFE_PATH,
                    isSlug(name) ? name : null);
        }
        Path target = topicPath(name);
        try {
            StableSnapshot before = stableSnapshot(target);
            if (!sha256(before.bytes()).equals(expectedDigest)) {
                return rejected(MemoryMutationDiagnosticKind.DIGEST_CONFLICT, name);
            }
            observer.beforeCommit(MutationPhase.DELETE, target);
            validateRoot();
            StableSnapshot current = stableSnapshot(target);
            if (!sameStableFile(before, current)
                    || !sha256(current.bytes()).equals(expectedDigest)) {
                return rejected(MemoryMutationDiagnosticKind.FILE_CHANGED_DURING_COMMIT, name);
            }
            Path tombstone = reserveTombstone();
            Files.delete(tombstone);
            try {
                mover.move(target, tombstone, false);
            } catch (IOException | RuntimeException failure) {
                deleteStaged(tombstone);
                throw failure;
            }
            StableSnapshot claimed = stableSnapshot(tombstone);
            if (!sameStableFile(current, claimed)
                    || !sha256(claimed.bytes()).equals(expectedDigest)) {
                restoreClaimedDelete(tombstone, target);
                return rejected(MemoryMutationDiagnosticKind.FILE_CHANGED_DURING_COMMIT, name);
            }
            Files.delete(tombstone);
            boolean indexFailed = !rebuildAndPersistIndex();
            return MemoryMutationResult.deleted(name, indexFailed);
        } catch (MissingTopic missing) {
            return rejected(MemoryMutationDiagnosticKind.TOPIC_NOT_FOUND, name);
        } catch (RestoreCollision collision) {
            return rejected(MemoryMutationDiagnosticKind.FILE_CHANGED_DURING_COMMIT, name);
        } catch (AtomicMoveNotSupportedException unsupported) {
            return rejected(MemoryMutationDiagnosticKind.ATOMIC_MOVE_UNAVAILABLE, name);
        } catch (UnsafePath unsafe) {
            return rejected(MemoryMutationDiagnosticKind.UNSAFE_PATH, name);
        } catch (IOException | RuntimeException failure) {
            return rejected(MemoryMutationDiagnosticKind.IO_FAILURE, name);
        }
    }

    @Override
    public MemoryIndex loadIndex() {
        MemoryCatalog catalog = catalogAdapter.rebuild();
        MemoryIndex index = catalogAdapter.render(catalog);
        Path staged = null;
        try {
            staged = stage(index.content().getBytes(StandardCharsets.UTF_8));
            observer.beforeCommit(MutationPhase.INDEX, root.resolve("MEMORY.md"));
            validateRoot();
            validateIndexTarget();
            moveAtomic(staged, root.resolve("MEMORY.md"), true);
            staged = null;
            return index;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("无法安全持久化 Memory Index", failure);
        } finally {
            deleteStaged(staged);
        }
    }

    private boolean rebuildAndPersistIndex() {
        try {
            loadIndex();
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private StableSnapshot stableSnapshot(Path target) throws IOException {
        validateExistingTarget(target);
        BasicFileAttributes before = attributes(target);
        byte[] first = readBounded(target);
        BasicFileAttributes after = attributes(target);
        validateExistingTarget(target);
        if (before.size() != first.length || after.size() != first.length) {
            throw new UnsafePath();
        }
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        if ((beforeKey == null) != (afterKey == null)) {
            throw new UnsafePath();
        }
        if (beforeKey != null) {
            if (!beforeKey.equals(afterKey)) {
                throw new UnsafePath();
            }
            return new StableSnapshot(first, afterKey);
        }
        validateExistingTarget(target);
        byte[] second = readBounded(target);
        BasicFileAttributes secondAfter = attributes(target);
        validateExistingTarget(target);
        if (secondAfter.fileKey() != null
                || secondAfter.size() != second.length
                || !Arrays.equals(first, second)) {
            throw new UnsafePath();
        }
        return new StableSnapshot(first, null);
    }

    private byte[] readBounded(Path target) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(FileMemoryCatalogAdapter.MAX_TOPIC_BYTES + 1);
        try (SeekableByteChannel channel = Files.newByteChannel(target, READ_NOFOLLOW)) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // 上限加一个字节用于确定性识别增长或超限。
            }
        }
        if (buffer.position() > FileMemoryCatalogAdapter.MAX_TOPIC_BYTES) {
            throw new IOException("Memory topic 超过大小上限");
        }
        byte[] bytes = new byte[buffer.position()];
        buffer.flip();
        buffer.get(bytes);
        return bytes;
    }

    private MemoryTopic parseTopic(StableSnapshot snapshot, String expectedName) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(snapshot.bytes()))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Memory topic 不是严格 UTF-8");
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length > FileMemoryCatalogAdapter.MAX_TOPIC_LINES
                || lines.length < 6
                || !"---".equals(lines[0])) {
            throw new IllegalArgumentException("Memory topic 格式无效");
        }
        java.util.HashMap<String, String> fields = new java.util.HashMap<>();
        int closing = -1;
        int limit = Math.min(lines.length, FileMemoryCatalogAdapter.MAX_FRONTMATTER_LINES + 1);
        for (int index = 1; index < limit; index++) {
            if ("---".equals(lines[index])) {
                closing = index;
                break;
            }
            int separator = lines[index].indexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException("Memory frontmatter 无效");
            }
            String key = lines[index].substring(0, separator).trim();
            String value = lines[index].substring(separator + 1).trim();
            if (!REQUIRED_FIELDS.contains(key)
                    || value.isEmpty()
                    || fields.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Memory frontmatter 无效");
            }
        }
        if (closing < 0 || !fields.keySet().equals(REQUIRED_FIELDS)) {
            throw new IllegalArgumentException("Memory frontmatter 无效");
        }
        String name = fields.get("name");
        if (!expectedName.equals(name) || !isSlug(name)) {
            throw new IllegalArgumentException("Memory topic name 无效");
        }
        MemoryKind kind;
        LocalDate updatedAt;
        try {
            kind = MemoryKind.valueOf(fields.get("kind"));
            updatedAt = LocalDate.parse(fields.get("updated-at"));
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new IllegalArgumentException("Memory frontmatter 值无效");
        }
        int bodyStart = closing + 1;
        if (bodyStart < lines.length && lines[bodyStart].isEmpty()) {
            bodyStart++;
        }
        String body = String.join("\n", Arrays.copyOfRange(lines, bodyStart, lines.length));
        return new MemoryTopic(
                name,
                kind,
                fields.get("description"),
                body,
                sha256(snapshot.bytes()),
                updatedAt);
    }

    private byte[] serialize(MemoryTopic topic) {
        String content = "---\n"
                + "kind: " + topic.kind().name() + "\n"
                + "name: " + topic.name() + "\n"
                + "description: " + topic.description() + "\n"
                + "updated-at: " + topic.updatedAt() + "\n"
                + "---\n\n"
                + topic.body();
        byte[] bytes = encodeStrict(content);
        if (bytes.length > FileMemoryCatalogAdapter.MAX_TOPIC_BYTES
                || content.split("\\R", -1).length > FileMemoryCatalogAdapter.MAX_TOPIC_LINES) {
            throw new IllegalArgumentException("Memory topic 超过上限");
        }
        return bytes;
    }

    private Path stage(byte[] bytes) throws IOException {
        validateRoot();
        Path staged = null;
        for (int attempt = 0; attempt < 16 && staged == null; attempt++) {
            byte[] nonce = new byte[16];
            RANDOM.nextBytes(nonce);
            Path candidate = root.resolve(
                    STAGED_PREFIX + HexFormat.of().formatHex(nonce) + STAGED_SUFFIX);
            try (FileChannel channel = FileChannel.open(
                    candidate,
                    Set.of(
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
                staged = candidate;
            } catch (FileAlreadyExistsException collision) {
                // 重新生成不可预测名称；固定次数后 Fail Closed。
            }
        }
        if (staged == null) {
            throw new IOException("无法创建唯一 Memory 暂存文件");
        }
        try {
            validateStaged(staged, bytes);
            return staged;
        } catch (IOException | RuntimeException failure) {
            deleteStaged(staged);
            throw failure;
        }
    }

    private void validateStaged(Path staged, byte[] expected) throws IOException {
        if (!isInternalTemporaryName(staged.getFileName().toString())) {
            throw new UnsafePath();
        }
        validateDirectChild(staged);
        BasicFileAttributes attributes = attributes(staged);
        if (!attributes.isRegularFile() || attributes.size() != expected.length) {
            throw new UnsafePath();
        }
        byte[] actual = readBounded(staged);
        if (!Arrays.equals(actual, expected)) {
            throw new UnsafePath();
        }
    }

    private void moveAtomic(Path source, Path target, boolean replace) throws IOException {
        mover.move(source, target, replace);
    }

    private Path reserveTombstone() throws IOException {
        return stage(new byte[0]);
    }

    private void restoreClaimedDelete(Path tombstone, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new RestoreCollision();
        }
        mover.move(tombstone, target, false);
    }

    private void validateIndexTarget() throws IOException {
        Path index = root.resolve("MEMORY.md");
        if (Files.exists(index, LinkOption.NOFOLLOW_LINKS)) {
            validateExistingTarget(index);
        } else {
            validateDirectChild(index);
        }
    }

    private void validateExistingTarget(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MissingTopic();
        }
        validateDirectChild(target);
        BasicFileAttributes attributes = attributes(target);
        if (!attributes.isRegularFile()) {
            throw new UnsafePath();
        }
    }

    private void validateDirectChild(Path path) throws IOException {
        validateRoot();
        if (Files.isSymbolicLink(path)) {
            throw new UnsafePath();
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (!root.equals(absolute.getParent())) {
            throw new UnsafePath();
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = attributes(path);
            if (attributes.isOther()) {
                throw new UnsafePath();
            }
            Path direct = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!direct.equals(absolute) || !root.equals(direct.getParent())) {
                throw new UnsafePath();
            }
        }
    }

    private void validateRoot() {
        try {
            if (Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new UnsafePath();
            }
            BasicFileAttributes attributes = attributes(root);
            Path real = root.toRealPath();
            if (attributes.isOther() || !real.equals(root)) {
                throw new UnsafePath();
            }
        } catch (IOException failure) {
            throw new UnsafePath();
        }
    }

    private boolean directoryEntryCountAtLimit() throws IOException {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if ("MEMORY.md".equals(name) || isInternalTemporaryName(name)) {
                    continue;
                }
                count++;
                if (count >= FileMemoryCatalogAdapter.MAX_TOPICS) {
                    return true;
                }
            }
        }
        return false;
    }

    private Path topicPath(String name) {
        if (!isSlug(name)) {
            throw new UnsafePath();
        }
        return root.resolve(name + ".md");
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(
                    path,
                    DosFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException unsupported) {
            return Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        }
    }

    static boolean isInternalTemporaryName(String name) {
        return name != null && STAGED_NAME.matcher(name).matches();
    }

    private static byte[] encodeStrict(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Memory 文本不能严格编码为 UTF-8", invalid);
        }
    }

    private static boolean sameStableFile(StableSnapshot before, StableSnapshot after) {
        if (before.fileKey() != null || after.fileKey() != null) {
            return Objects.equals(before.fileKey(), after.fileKey());
        }
        return Arrays.equals(before.bytes(), after.bytes());
    }

    private static boolean isSlug(String value) {
        return value != null
                && value.codePointCount(0, value.length()) <= 64
                && SLUG.matcher(value).matches();
    }

    private static boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static MemoryMutationResult rejected(
            MemoryMutationDiagnosticKind kind,
            String validTopic) {
        MemoryMutationDiagnostic diagnostic = validTopic == null
                ? MemoryMutationDiagnostic.repository(kind)
                : MemoryMutationDiagnostic.topic(kind, validTopic);
        return MemoryMutationResult.rejected(diagnostic);
    }

    private void deleteStaged(Path staged) {
        if (staged == null || !isInternalTemporaryName(staged.getFileName().toString())) {
            return;
        }
        try {
            cleaner.delete(staged);
        } catch (IOException | RuntimeException ignored) {
            // 清理失败不覆盖主结果；M3 只忽略严格内部随机名工件。
        }
    }

    @FunctionalInterface
    interface MutationObserver {
        void beforeCommit(MutationPhase phase, Path target) throws IOException;
    }

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path target, boolean replace) throws IOException;
    }

    @FunctionalInterface
    interface CreateLinker {
        void link(Path target, Path existing) throws IOException;
    }

    @FunctionalInterface
    interface StagedCleaner {
        boolean delete(Path staged) throws IOException;
    }

    enum MutationPhase {
        CREATE,
        UPDATE,
        DELETE,
        INDEX
    }

    private record StableSnapshot(byte[] bytes, Object fileKey) {
        private StableSnapshot {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static final class UnsafePath extends RuntimeException {
    }

    private static final class MissingTopic extends RuntimeException {
    }

    private static final class RestoreCollision extends RuntimeException {
    }
}
