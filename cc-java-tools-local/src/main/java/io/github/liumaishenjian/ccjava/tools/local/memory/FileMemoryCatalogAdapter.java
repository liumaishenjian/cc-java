package io.github.liumaishenjian.ccjava.tools.local.memory;

import io.github.liumaishenjian.ccjava.core.MemoryCatalogBuilder;
import io.github.liumaishenjian.ccjava.core.MemoryIndexRenderer;
import io.github.liumaishenjian.ccjava.domain.MemoryCatalog;
import io.github.liumaishenjian.ccjava.domain.MemoryCatalogRevision;
import io.github.liumaishenjian.ccjava.domain.MemoryDiagnostic;
import io.github.liumaishenjian.ccjava.domain.MemoryDiagnosticKind;
import io.github.liumaishenjian.ccjava.domain.MemoryIndex;
import io.github.liumaishenjian.ccjava.domain.MemoryKind;
import io.github.liumaishenjian.ccjava.domain.MemoryTopicHeader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 对注入 memory root 执行 M3 单层有界扫描并渲染 M2 索引的本地 Adapter。
 *
 * <p>每个目录项独立验证链接/重解析点、真实路径、普通文件、文件名、大小、行数、严格 UTF-8 和
 * 受限 frontmatter。单文件失败只产生不回显正文或非法路径的诊断；已验证 topic 继续进入 Catalog。
 * 本类型自身只读，M1 mutation 与 M2 持久替换由相邻 {@link FileMemoryRepository} 承担；D2 仅由
 * {@link FileMemoryPrefetchAdapter} 在异步边界组合本 Adapter，不改变其文件安全契约。</p>
 *
 * @since 0.7.0
 */
public final class FileMemoryCatalogAdapter
        implements MemoryCatalogBuilder, MemoryIndexRenderer {

    /** M3 可接受的 topic 数量上限。 */
    public static final int MAX_TOPICS = 200;

    /** 单个 topic 文件的独立 UTF-8 字节上限。 */
    public static final int MAX_TOPIC_BYTES = 64 * 1024;

    /** 单个 topic 文件的独立行数上限。 */
    public static final int MAX_TOPIC_LINES = 2_000;

    /** Frontmatter 结束标记必须在此前出现。 */
    public static final int MAX_FRONTMATTER_LINES = 16;

    /** Topic slug 最大 Unicode code point 数。 */
    public static final int MAX_SLUG_CHARACTERS = 64;

    /** Description 最大 Unicode code point 数。 */
    public static final int MAX_DESCRIPTION_CHARACTERS = 512;

    /** M2 Index 行数上限。 */
    public static final int MAX_INDEX_LINES = 200;

    /** M2 Index UTF-8 字节上限。 */
    public static final int MAX_INDEX_BYTES = 25 * 1024;

    private static final Pattern SLUG =
            Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Set<String> REQUIRED_FIELDS =
            Set.of("kind", "name", "description", "updated-at");

    private static final Set<OpenOption> READ_NOFOLLOW =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private final Path root;
    private final ReadObserver readObserver;
    private final ChannelOpener channelOpener;
    private final AttributeReader attributeReader;

    /**
     * 固定一个已经存在的真实 memory root。
     *
     * @param memoryRoot 注入的 M1 根目录
     * @throws IllegalArgumentException root 不存在、不是普通目录或属于链接/重解析路径时
     */
    public FileMemoryCatalogAdapter(Path memoryRoot) {
        this(
                memoryRoot,
                (path, bytesRead) -> { },
                path -> Files.newByteChannel(path, READ_NOFOLLOW),
                FileMemoryCatalogAdapter::readIdentityAttributes);
    }

    FileMemoryCatalogAdapter(Path memoryRoot, ReadObserver readObserver) {
        this(
                memoryRoot,
                readObserver,
                path -> Files.newByteChannel(path, READ_NOFOLLOW),
                FileMemoryCatalogAdapter::readIdentityAttributes);
    }

    FileMemoryCatalogAdapter(
            Path memoryRoot,
            ReadObserver readObserver,
            ChannelOpener channelOpener,
            AttributeReader attributeReader) {
        Path input = Objects.requireNonNull(memoryRoot, "memoryRoot 不能为空")
                .toAbsolutePath()
                .normalize();
        this.readObserver = Objects.requireNonNull(readObserver, "readObserver 不能为空");
        this.channelOpener = Objects.requireNonNull(channelOpener, "channelOpener 不能为空");
        this.attributeReader = Objects.requireNonNull(attributeReader, "attributeReader 不能为空");
        try {
            rejectLinkOrReparse(input);
            if (!Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("memoryRoot 必须是已存在的普通目录");
            }
            Path real = input.toRealPath();
            if (!real.equals(input)) {
                throw new IllegalArgumentException("memoryRoot 不接受链接或重解析目录");
            }
            this.root = real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法验证 memoryRoot", exception);
        }
    }

    /**
     * 单层扫描 M1，固定按文件名排序后逐项隔离解析。
     *
     * @return 无路径、无正文的有界 Catalog
     */
    @Override
    public MemoryCatalog rebuild() {
        PriorityQueue<Path> selected = new PriorityQueue<>(
                MAX_TOPICS,
                Comparator.comparing(FileMemoryCatalogAdapter::fileName).reversed());
        List<MemoryDiagnostic> diagnostics = new ArrayList<>();
        boolean exceeded = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                if ("MEMORY.md".equals(fileName(path))
                        || FileMemoryRepository.isInternalTemporaryName(fileName(path))) {
                    continue;
                }
                if (selected.size() < MAX_TOPICS) {
                    selected.add(path);
                } else {
                    exceeded = true;
                    if (fileName(path).compareTo(fileName(selected.peek())) < 0) {
                        selected.remove();
                        selected.add(path);
                    }
                }
            }
        } catch (IOException exception) {
            diagnostics.add(MemoryDiagnostic.catalog(MemoryDiagnosticKind.IO_FAILURE));
            return catalog(List.of(), diagnostics);
        }
        List<Path> candidates = new ArrayList<>(selected);
        candidates.sort(Comparator.comparing(FileMemoryCatalogAdapter::fileName));
        if (exceeded) {
            diagnostics.add(MemoryDiagnostic.catalog(MemoryDiagnosticKind.TOPIC_LIMIT_REACHED));
        }

        List<MemoryTopicHeader> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Path candidate : candidates) {
            Parsed parsed = parse(candidate);
            if (parsed.header() == null) {
                diagnostics.add(parsed.diagnostic());
                continue;
            }
            if (!names.add(parsed.header().name())) {
                diagnostics.add(MemoryDiagnostic.topic(
                        MemoryDiagnosticKind.DUPLICATE_TOPIC,
                        parsed.header().name()));
                continue;
            }
            entries.add(parsed.header());
        }
        entries.sort(Comparator.comparing(MemoryTopicHeader::name));
        return catalog(entries, diagnostics);
    }

    /**
     * 按 Catalog 顺序渲染一行一个相对链接与 hook；先触发的行数或字节上限终止后续条目。
     *
     * @param catalog 已验证 Catalog
     * @return 有界 UTF-8 Index
     */
    @Override
    public MemoryIndex render(MemoryCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog 不能为空");
        StringBuilder content = new StringBuilder();
        List<MemoryDiagnostic> diagnostics = new ArrayList<>();
        int included = 0;
        for (MemoryTopicHeader entry : catalog.entries()) {
            if (included >= MAX_INDEX_LINES) {
                diagnostics.add(MemoryDiagnostic.catalog(
                        MemoryDiagnosticKind.INDEX_LINE_LIMIT_REACHED));
                break;
            }
            String line = "- [" + entry.name() + "](" + entry.name() + ".md) — "
                    + entry.description() + "\n";
            int nextBytes = content.toString().getBytes(StandardCharsets.UTF_8).length
                    + line.getBytes(StandardCharsets.UTF_8).length;
            if (nextBytes > MAX_INDEX_BYTES) {
                diagnostics.add(MemoryDiagnostic.catalog(
                        MemoryDiagnosticKind.INDEX_BYTE_LIMIT_REACHED));
                break;
            }
            content.append(line);
            included++;
        }
        return new MemoryIndex(content.toString(), included, diagnostics);
    }

    Parsed parseTopic(Path candidate) {
        return parse(candidate);
    }

    private Parsed parse(Path candidate) {
        String validTopic = validatedFileSlug(fileName(candidate));
        try {
            Validation before = validateCandidate(candidate, validTopic);
            if (before.failure() != null) {
                return before.failure();
            }
            if (validTopic == null) {
                return failure(MemoryDiagnosticKind.INVALID_FILE_NAME, null);
            }
            if (before.attributes().size() > MAX_TOPIC_BYTES) {
                return failure(MemoryDiagnosticKind.FILE_TOO_LARGE, validTopic);
            }

            ReadResult read = readBounded(candidate);
            if (read.tooLarge()) {
                return failure(MemoryDiagnosticKind.FILE_TOO_LARGE, validTopic);
            }
            readObserver.afterRead(candidate, read.bytes().length);

            Validation after = validateCandidate(candidate, validTopic);
            if (after.failure() != null
                    || before.attributes().size() != read.bytes().length
                    || after.attributes().size() != read.bytes().length) {
                return failure(MemoryDiagnosticKind.FILE_CHANGED_DURING_READ, validTopic);
            }

            Object beforeKey = before.attributes().fileKey();
            Object afterKey = after.attributes().fileKey();
            if ((beforeKey == null) != (afterKey == null)) {
                return failure(MemoryDiagnosticKind.FILE_CHANGED_DURING_READ, validTopic);
            }
            if (beforeKey != null) {
                if (!beforeKey.equals(afterKey)) {
                    return failure(MemoryDiagnosticKind.FILE_CHANGED_DURING_READ, validTopic);
                }
            } else if (!stableWithoutFileKey(candidate, validTopic, read)) {
                return failure(MemoryDiagnosticKind.FILE_CHANGED_DURING_READ, validTopic);
            }

            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(read.bytes()))
                        .toString();
            } catch (CharacterCodingException exception) {
                return failure(MemoryDiagnosticKind.INVALID_UTF8, validTopic);
            }
            String[] lines = text.split("\\R", -1);
            if (lines.length > MAX_TOPIC_LINES) {
                return failure(MemoryDiagnosticKind.TOO_MANY_LINES, validTopic);
            }
            return parseFrontmatter(lines, validTopic, read.bytes());
        } catch (IOException | RuntimeException exception) {
            return failure(MemoryDiagnosticKind.IO_FAILURE, validTopic);
        }
    }

    private Validation validateCandidate(Path candidate, String validTopic) throws IOException {
        if (Files.isSymbolicLink(candidate) || isReparsePoint(candidate)) {
            return Validation.failed(failure(MemoryDiagnosticKind.LINK_NOT_ALLOWED, validTopic));
        }
        BasicFileAttributes attributes = attributeReader.read(candidate);
        if (!attributes.isRegularFile()) {
            return Validation.failed(failure(
                    MemoryDiagnosticKind.ENTRY_NOT_REGULAR_FILE,
                    validTopic));
        }
        Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.getParent().equals(root) || !real.equals(candidate.toAbsolutePath().normalize())) {
            return Validation.failed(failure(MemoryDiagnosticKind.PATH_OUTSIDE_ROOT, validTopic));
        }
        return new Validation(attributes, null);
    }

    private ReadResult readBounded(Path candidate) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(MAX_TOPIC_BYTES + 1);
        try (SeekableByteChannel channel = channelOpener.open(candidate)) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // 最多保留上限加一个判定字节，避免被增长文件驱动无界分配。
            }
        }
        boolean tooLarge = buffer.position() > MAX_TOPIC_BYTES;
        byte[] bytes = new byte[Math.min(buffer.position(), MAX_TOPIC_BYTES)];
        buffer.flip();
        buffer.get(bytes);
        return new ReadResult(bytes, tooLarge);
    }

    private boolean stableWithoutFileKey(
            Path candidate,
            String validTopic,
            ReadResult firstRead) throws IOException {
        Validation secondBefore = validateCandidate(candidate, validTopic);
        if (secondBefore.failure() != null
                || secondBefore.attributes().fileKey() != null
                || secondBefore.attributes().size() != firstRead.bytes().length) {
            return false;
        }

        ReadResult secondRead = readBounded(candidate);
        if (secondRead.tooLarge()
                || secondRead.bytes().length != firstRead.bytes().length) {
            return false;
        }

        Validation secondAfter = validateCandidate(candidate, validTopic);
        return secondAfter.failure() == null
                && secondAfter.attributes().fileKey() == null
                && secondAfter.attributes().size() == secondRead.bytes().length
                && Arrays.equals(firstRead.bytes(), secondRead.bytes());
    }

    private static BasicFileAttributes readIdentityAttributes(Path path) throws IOException {
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

    private Parsed parseFrontmatter(
            String[] lines,
            String fileSlug,
            byte[] bytes) {
        if (lines.length < 6 || !"---".equals(lines[0])) {
            return failure(MemoryDiagnosticKind.INVALID_FRONTMATTER, fileSlug);
        }
        Map<String, String> fields = new HashMap<>();
        int closing = -1;
        int limit = Math.min(lines.length, MAX_FRONTMATTER_LINES + 1);
        for (int index = 1; index < limit; index++) {
            String line = lines[index];
            if ("---".equals(line)) {
                closing = index;
                break;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                return failure(MemoryDiagnosticKind.INVALID_FRONTMATTER, fileSlug);
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!REQUIRED_FIELDS.contains(key)
                    || value.isEmpty()
                    || fields.putIfAbsent(key, value) != null) {
                return failure(MemoryDiagnosticKind.INVALID_FRONTMATTER, fileSlug);
            }
        }
        if (closing < 0 || !fields.keySet().equals(REQUIRED_FIELDS)) {
            return failure(MemoryDiagnosticKind.INVALID_FRONTMATTER, fileSlug);
        }

        String name = fields.get("name");
        if (!isSlug(name) || !name.equals(fileSlug)) {
            return failure(MemoryDiagnosticKind.INVALID_SLUG, fileSlug);
        }
        String description = fields.get("description");
        if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION_CHARACTERS
                || description.chars().anyMatch(character -> Character.isISOControl(character))) {
            return failure(MemoryDiagnosticKind.FIELD_LIMIT_EXCEEDED, fileSlug);
        }
        MemoryKind kind;
        try {
            kind = MemoryKind.valueOf(fields.get("kind"));
        } catch (IllegalArgumentException unknown) {
            return failure(MemoryDiagnosticKind.UNKNOWN_KIND, fileSlug);
        }
        LocalDate updatedAt;
        try {
            updatedAt = LocalDate.parse(fields.get("updated-at"));
        } catch (DateTimeException invalid) {
            return failure(MemoryDiagnosticKind.INVALID_FRONTMATTER, fileSlug);
        }
        MemoryTopicHeader header = new MemoryTopicHeader(
                name,
                kind,
                description,
                updatedAt,
                sha256(bytes));
        return new Parsed(header, null);
    }

    private static String fileName(Path path) {
        return path.getFileName().toString();
    }

    private String validatedFileSlug(String fileName) {
        if (!fileName.endsWith(".md") || fileName.equals("MEMORY.md")) {
            return null;
        }
        String slug = fileName.substring(0, fileName.length() - 3);
        return isSlug(slug) ? slug : null;
    }

    private boolean isSlug(String value) {
        return value != null
                && value.codePointCount(0, value.length()) <= MAX_SLUG_CHARACTERS
                && SLUG.matcher(value).matches()
                && !value.contains("..")
                && !value.contains("/")
                && !value.contains("\\");
    }

    private MemoryCatalog catalog(
            List<MemoryTopicHeader> entries,
            List<MemoryDiagnostic> diagnostics) {
        List<MemoryTopicHeader> copiedEntries = List.copyOf(entries);
        List<MemoryDiagnostic> copiedDiagnostics = List.copyOf(diagnostics);
        MessageDigest digest = sha256Digest();
        for (MemoryTopicHeader entry : copiedEntries) {
            update(digest, entry.name());
            update(digest, entry.kind().name());
            update(digest, entry.description());
            update(digest, entry.updatedAt().toString());
            update(digest, entry.contentDigest());
        }
        for (MemoryDiagnostic diagnostic : copiedDiagnostics) {
            update(digest, diagnostic.kind().name());
            update(digest, diagnostic.topicName().orElse(""));
        }
        return new MemoryCatalog(
                copiedEntries,
                copiedDiagnostics,
                new MemoryCatalogRevision(HexFormat.of().formatHex(digest.digest())));
    }

    private static Parsed failure(
            MemoryDiagnosticKind kind,
            String validTopic) {
        MemoryDiagnostic diagnostic = validTopic == null
                ? MemoryDiagnostic.catalog(kind)
                : MemoryDiagnostic.topic(kind, validTopic);
        return new Parsed(null, diagnostic);
    }

    private static void rejectLinkOrReparse(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || isReparsePoint(path)) {
            throw new IllegalArgumentException("memoryRoot 不接受链接或重解析目录");
        }
    }

    private static boolean isReparsePoint(Path path) {
        try {
            BasicFileAttributes basic = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (basic.isOther()) {
                return true;
            }
            Path absolute = path.toAbsolutePath().normalize();
            Path real = path.toRealPath();
            return !real.equals(absolute);
        } catch (UnsupportedOperationException | IOException exception) {
            return false;
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    @FunctionalInterface
    interface ReadObserver {
        void afterRead(Path path, int bytesRead) throws IOException;
    }

    @FunctionalInterface
    interface ChannelOpener {
        SeekableByteChannel open(Path path) throws IOException;
    }

    @FunctionalInterface
    interface AttributeReader {
        BasicFileAttributes read(Path path) throws IOException;
    }

    private record ReadResult(byte[] bytes, boolean tooLarge) {
    }

    private record Validation(
            BasicFileAttributes attributes,
            Parsed failure) {

        private static Validation failed(Parsed failure) {
            return new Validation(null, failure);
        }
    }

    record Parsed(
            MemoryTopicHeader header,
            MemoryDiagnostic diagnostic) {
    }
}
