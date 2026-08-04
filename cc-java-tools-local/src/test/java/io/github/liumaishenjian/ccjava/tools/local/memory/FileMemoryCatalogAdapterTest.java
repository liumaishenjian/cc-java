package io.github.liumaishenjian.ccjava.tools.local.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.MemoryCatalog;
import io.github.liumaishenjian.ccjava.domain.MemoryDiagnosticKind;
import io.github.liumaishenjian.ccjava.domain.MemoryIndex;
import io.github.liumaishenjian.ccjava.domain.MemoryKind;
import io.github.liumaishenjian.ccjava.domain.MemoryTopicHeader;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileMemoryCatalogAdapterTest {

    @TempDir
    Path temporary;

    @Test
    void derivesStableIsolatedRepositoryIdsWithoutPathDisclosure() throws Exception {
        Path first = Files.createDirectory(temporary.resolve("workspace-secret-one"));
        Path second = Files.createDirectory(temporary.resolve("workspace-secret-two"));
        MemoryStorageLayout layout = new MemoryStorageLayout();

        String firstId = layout.repositoryId(first);
        String repeated = layout.repositoryId(first.resolve("."));
        String secondId = layout.repositoryId(second);
        Path root = layout.defaultMemoryRoot(temporary.resolve("home-secret"), firstId);

        assertThat(firstId).isEqualTo(repeated).matches("[0-9a-f]{64}");
        assertThat(secondId).matches("[0-9a-f]{64}").isNotEqualTo(firstId);
        assertThat(firstId).doesNotContain("workspace-secret-one");
        assertThat(root.getFileName().toString()).isEqualTo("memory");
        assertThat(root.toString()).doesNotContain("workspace-secret-one");
    }

    @Test
    void scansAllKindsInDeterministicOrderAndExcludesIndex() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("memory"));
        writeTopic(root, "z-reference", MemoryKind.REFERENCE_POINTER, "reference hook");
        writeTopic(root, "a-user", MemoryKind.USER_PROFILE, "user hook");
        writeTopic(root, "m-project", MemoryKind.PROJECT_STATE, "project hook");
        writeTopic(root, "b-guidance", MemoryKind.WORKING_GUIDANCE, "guidance hook");
        Files.writeString(root.resolve("MEMORY.md"), "ignored absolute " + temporary);
        FileMemoryCatalogAdapter adapter = new FileMemoryCatalogAdapter(root);

        MemoryCatalog first = adapter.rebuild();
        MemoryCatalog second = adapter.rebuild();
        MemoryIndex index = adapter.render(first);

        assertThat(first.entries())
                .extracting(MemoryTopicHeader::name)
                .containsExactly("a-user", "b-guidance", "m-project", "z-reference");
        assertThat(first.entries())
                .extracting(MemoryTopicHeader::kind)
                .containsExactly(
                        MemoryKind.USER_PROFILE,
                        MemoryKind.WORKING_GUIDANCE,
                        MemoryKind.PROJECT_STATE,
                        MemoryKind.REFERENCE_POINTER);
        assertThat(first).isEqualTo(second);
        assertThat(index.content()).contains("(a-user.md) — user hook");
        assertThat(index.content()).doesNotContain(temporary.toString());
        assertThat(index.includedTopics()).isEqualTo(4);
    }

    @Test
    void isolatesMalformedUtf8UnknownKindInvalidSlugAndOtherEntries() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("bad-memory"));
        writeTopic(root, "valid-topic", MemoryKind.USER_PROFILE, "valid hook");
        Files.write(root.resolve("invalid-utf8.md"), new byte[] {
            '-', '-', '-', '\n', (byte) 0xC3, (byte) 0x28
        });
        Files.writeString(root.resolve("unknown-kind.md"), topic(
                "unknown-kind", "NOT_A_KIND", "unknown"), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("Bad_Name.md"), topic(
                "Bad_Name", MemoryKind.PROJECT_STATE.name(), "bad file slug"));
        Files.writeString(root.resolve("valid-file.md"), topic(
                "Bad_Name", MemoryKind.PROJECT_STATE.name(), "bad header slug"));
        Files.createDirectory(root.resolve("directory.md"));

        MemoryCatalog catalog = new FileMemoryCatalogAdapter(root).rebuild();

        assertThat(catalog.entries())
                .extracting(MemoryTopicHeader::name)
                .containsExactly("valid-topic");
        assertThat(catalog.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .contains(
                        MemoryDiagnosticKind.INVALID_UTF8,
                        MemoryDiagnosticKind.UNKNOWN_KIND,
                        MemoryDiagnosticKind.INVALID_FILE_NAME,
                        MemoryDiagnosticKind.INVALID_SLUG,
                        MemoryDiagnosticKind.ENTRY_NOT_REGULAR_FILE);
        assertThat(catalog.diagnostics())
                .allSatisfy(diagnostic -> diagnostic.topicName()
                        .ifPresent(name -> assertThat(name).doesNotContain("/", "\\", "..")));
    }

    @Test
    void rejectsSymbolicLinkWhenPlatformAllowsCreation() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("link-memory"));
        Path outside = Files.writeString(
                temporary.resolve("outside.md"),
                topic("outside", MemoryKind.PROJECT_STATE.name(), "outside"));
        Path link = root.resolve("linked.md");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("当前环境不能创建 Symlink");
        }

        MemoryCatalog catalog = new FileMemoryCatalogAdapter(root).rebuild();

        assertThat(catalog.entries()).isEmpty();
        assertThat(catalog.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .containsExactly(MemoryDiagnosticKind.LINK_NOT_ALLOWED);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsWindowsJunctionWhenPlatformAllowsCreation() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("junction-memory"));
        Path target = Files.createDirectory(temporary.resolve("junction-target"));
        Path junction = root.resolve("linked.md");
        createJunction(junction, target);

        try {
            MemoryCatalog catalog = new FileMemoryCatalogAdapter(root).rebuild();

            assertThat(catalog.entries()).isEmpty();
            assertThat(catalog.diagnostics())
                    .extracting(diagnostic -> diagnostic.kind())
                    .containsExactly(MemoryDiagnosticKind.LINK_NOT_ALLOWED);
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    void boundsInvalidEntryFloodAndSelectsStableSmallestNames() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("flood-memory"));
        writeTopic(root, "a-valid", MemoryKind.PROJECT_STATE, "selected");
        for (int index = 0; index < 500; index++) {
            Files.createDirectory(root.resolve("invalid-" + String.format("%03d", index) + ".md"));
        }
        writeTopic(root, "z-valid", MemoryKind.PROJECT_STATE, "not selected");
        FileMemoryCatalogAdapter adapter = new FileMemoryCatalogAdapter(root);

        MemoryCatalog first = adapter.rebuild();
        MemoryCatalog second = adapter.rebuild();

        assertThat(first).isEqualTo(second);
        assertThat(first.entries())
                .extracting(MemoryTopicHeader::name)
                .containsExactly("a-valid");
        assertThat(first.diagnostics())
                .filteredOn(diagnostic -> diagnostic.kind()
                        == MemoryDiagnosticKind.ENTRY_NOT_REGULAR_FILE)
                .hasSize(199);
        assertThat(first.diagnostics())
                .filteredOn(diagnostic -> diagnostic.kind()
                        == MemoryDiagnosticKind.TOPIC_LIMIT_REACHED)
                .singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.topicName()).isEmpty());
    }

    @Test
    void failsClosedWhenNoFollowChannelOpenIsUnsupported() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("unsupported-open-memory"));
        writeTopic(root, "safe-topic", MemoryKind.PROJECT_STATE, "safe");
        int[] unsafeOpenCalls = {0};
        FileMemoryCatalogAdapter adapter = new FileMemoryCatalogAdapter(
                root,
                (path, bytesRead) -> { },
                path -> {
                    unsafeOpenCalls[0]++;
                    throw new UnsupportedOperationException("NOFOLLOW_LINKS unsupported");
                },
                FileMemoryCatalogAdapterTest::readAttributes);

        MemoryCatalog catalog = adapter.rebuild();

        assertThat(catalog.entries()).isEmpty();
        assertThat(unsafeOpenCalls[0]).isEqualTo(1);
        assertThat(catalog.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .containsExactly(MemoryDiagnosticKind.IO_FAILURE);
    }

    @Test
    void acceptsStableDoubleReadWhenFileKeyIsMissingAndTimestampsMatch() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("missing-key-memory"));
        Path topic = root.resolve("missing-key.md");
        writeTopic(root, "missing-key", MemoryKind.PROJECT_STATE, "safe");
        FileTime fixedTime = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(topic, fixedTime);
        int[] safeOpenCalls = {0};
        FileMemoryCatalogAdapter adapter = new FileMemoryCatalogAdapter(
                root,
                (path, bytesRead) -> { },
                path -> {
                    safeOpenCalls[0]++;
                    return openSafeChannel(path);
                },
                path -> withoutFileKey(readAttributes(path)));

        MemoryCatalog catalog = adapter.rebuild();

        assertThat(catalog.entries())
                .extracting(MemoryTopicHeader::name)
                .containsExactly("missing-key");
        assertThat(catalog.diagnostics()).isEmpty();
        assertThat(safeOpenCalls[0]).isEqualTo(2);
        assertThat(Files.getLastModifiedTime(topic)).isEqualTo(fixedTime);
    }

    @Test
    void rejectsContentChangedBeforeSecondReadWhenFileKeyIsMissing() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("second-read-change-memory"));
        Path topic = root.resolve("changing-topic.md");
        String before = topic(
                "changing-topic", MemoryKind.PROJECT_STATE.name(), "before");
        String after = topic(
                "changing-topic", MemoryKind.PROJECT_STATE.name(), "after-");
        assertThat(after.getBytes(StandardCharsets.UTF_8))
                .hasSameSizeAs(before.getBytes(StandardCharsets.UTF_8));
        Files.writeString(topic, before);
        int[] attributeReads = {0};
        FileMemoryCatalogAdapter adapter = new FileMemoryCatalogAdapter(
                root,
                (path, bytesRead) -> { },
                FileMemoryCatalogAdapterTest::openSafeChannel,
                path -> {
                    attributeReads[0]++;
                    if (attributeReads[0] == 3) {
                        Files.writeString(path, after);
                    }
                    return withoutFileKey(readAttributes(path));
                });

        MemoryCatalog catalog = adapter.rebuild();

        assertThat(catalog.entries()).isEmpty();
        assertThat(catalog.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .containsExactly(MemoryDiagnosticKind.FILE_CHANGED_DURING_READ);
    }

    @Test
    void rejectsSecondSafeOpenFailureWhenFileKeyIsMissing() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("second-open-failure-memory"));
        writeTopic(root, "second-open-failure", MemoryKind.PROJECT_STATE, "safe");
        int[] safeOpenCalls = {0};
        FileMemoryCatalogAdapter adapter = new FileMemoryCatalogAdapter(
                root,
                (path, bytesRead) -> { },
                path -> {
                    safeOpenCalls[0]++;
                    if (safeOpenCalls[0] == 2) {
                        throw new IOException("second safe open failed");
                    }
                    return openSafeChannel(path);
                },
                path -> withoutFileKey(readAttributes(path)));

        MemoryCatalog catalog = adapter.rebuild();

        assertThat(catalog.entries()).isEmpty();
        assertThat(safeOpenCalls[0]).isEqualTo(2);
        assertThat(catalog.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .containsExactly(MemoryDiagnosticKind.IO_FAILURE);
    }

    @Test
    void rejectsFileReplacedAfterBoundedReadWithoutCommittingHeader() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("replace-memory"));
        Path topic = root.resolve("replace-topic.md");
        String before = topic(
                "replace-topic", MemoryKind.PROJECT_STATE.name(), "before");
        String after = topic(
                "replace-topic", MemoryKind.PROJECT_STATE.name(), "after-");
        assertThat(after.getBytes(StandardCharsets.UTF_8))
                .hasSameSizeAs(before.getBytes(StandardCharsets.UTF_8));
        Files.writeString(topic, before);
        FileMemoryCatalogAdapter adapter = new FileMemoryCatalogAdapter(
                root,
                (path, bytesRead) -> {
                    Path replacement = root.resolve("replacement.tmp");
                    Files.writeString(replacement, after);
                    Files.move(
                            replacement,
                            path,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                });

        MemoryCatalog catalog = adapter.rebuild();

        assertThat(catalog.entries()).isEmpty();
        assertThat(catalog.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .containsExactly(MemoryDiagnosticKind.FILE_CHANGED_DURING_READ);
    }

    @Test
    void rejectsFileThatGrowsBeyondLimitDuringBoundedRead() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("grow-memory"));
        Path topic = root.resolve("grow-topic.md");
        Files.writeString(topic, topic(
                "grow-topic", MemoryKind.PROJECT_STATE.name(), "before"));
        FileMemoryCatalogAdapter adapter = new FileMemoryCatalogAdapter(
                root,
                (path, bytesRead) -> Files.write(
                        path,
                        new byte[FileMemoryCatalogAdapter.MAX_TOPIC_BYTES + 1],
                        java.nio.file.StandardOpenOption.APPEND));

        MemoryCatalog catalog = adapter.rebuild();

        assertThat(catalog.entries()).isEmpty();
        assertThat(catalog.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .containsExactly(MemoryDiagnosticKind.FILE_CHANGED_DURING_READ);
    }

    @Test
    void capsCatalogAtTwoHundredTopicsWithDiagnostic() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("many-memory"));
        for (int index = 0; index < 201; index++) {
            writeTopic(
                    root,
                    "topic-" + String.format("%03d", index),
                    MemoryKind.PROJECT_STATE,
                    "hook " + index);
        }

        MemoryCatalog catalog = new FileMemoryCatalogAdapter(root).rebuild();

        assertThat(catalog.entries()).hasSize(200);
        assertThat(catalog.entries().getFirst().name()).isEqualTo("topic-000");
        assertThat(catalog.entries().getLast().name()).isEqualTo("topic-199");
        assertThat(catalog.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .containsExactly(MemoryDiagnosticKind.TOPIC_LIMIT_REACHED);
    }

    @Test
    void indexHonorsTwoHundredLinesAndTwentyFiveKilobytesFirst() {
        FileMemoryCatalogAdapter adapter = new FileMemoryCatalogAdapter(temporary);
        List<MemoryTopicHeader> lineEntries = headers(201, "hook");
        MemoryIndex lineLimited = adapter.render(catalog(lineEntries));

        List<MemoryTopicHeader> byteEntries = headers(200, "界".repeat(160));
        MemoryIndex byteLimited = adapter.render(catalog(byteEntries));

        assertThat(lineLimited.includedTopics()).isEqualTo(200);
        assertThat(lineLimited.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .containsExactly(MemoryDiagnosticKind.INDEX_LINE_LIMIT_REACHED);
        assertThat(lineLimited.content().lines()).hasSize(200);
        assertThat(byteLimited.includedTopics()).isLessThan(200);
        assertThat(byteLimited.content().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(FileMemoryCatalogAdapter.MAX_INDEX_BYTES);
        assertThat(byteLimited.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .containsExactly(MemoryDiagnosticKind.INDEX_BYTE_LIMIT_REACHED);
        assertThat(byteLimited.content()).doesNotContain(temporary.toString());
    }

    private static SeekableByteChannel openSafeChannel(Path path) throws IOException {
        return Files.newByteChannel(
                path,
                java.util.Set.of(
                        java.nio.file.StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS));
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static BasicFileAttributes withoutFileKey(
            BasicFileAttributes delegate) {
        return new BasicFileAttributes() {
            @Override
            public FileTime lastModifiedTime() {
                return delegate.lastModifiedTime();
            }

            @Override
            public FileTime lastAccessTime() {
                return delegate.lastAccessTime();
            }

            @Override
            public FileTime creationTime() {
                return delegate.creationTime();
            }

            @Override
            public boolean isRegularFile() {
                return delegate.isRegularFile();
            }

            @Override
            public boolean isDirectory() {
                return delegate.isDirectory();
            }

            @Override
            public boolean isSymbolicLink() {
                return delegate.isSymbolicLink();
            }

            @Override
            public boolean isOther() {
                return delegate.isOther();
            }

            @Override
            public long size() {
                return delegate.size();
            }

            @Override
            public Object fileKey() {
                return null;
            }
        };
    }

    private static void createJunction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        byte[] outputBytes = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            String output = decodeOutput(outputBytes);
            if (isAccessDenied(output)) {
                Assumptions.abort(
                        "当前 Windows 策略禁止创建 Junction，跳过 Memory Junction 证据："
                                + output);
            }
            throw new AssertionError(
                    "Junction 创建出现非权限类错误：exit=" + exit + ", output=" + output);
        }
        assertThat(Files.isDirectory(link)).isTrue();
    }

    private static String decodeOutput(byte[] bytes) {
        LinkedHashSet<Charset> candidates = new LinkedHashSet<>();
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null) {
            candidates.add(Charset.forName(nativeEncoding));
        }
        candidates.add(Charset.defaultCharset());
        candidates.add(Charset.forName("GBK"));
        candidates.add(StandardCharsets.UTF_8);
        return candidates.stream()
                .map(charset -> new String(bytes, charset).trim())
                .filter(value -> !value.isEmpty())
                .min(java.util.Comparator.comparingLong(value -> value.chars()
                        .filter(character -> character == '�').count()))
                .orElse("");
    }

    private static boolean isAccessDenied(String output) {
        String normalized = output.toLowerCase(Locale.ROOT);
        return normalized.contains("access is denied")
                || normalized.contains("access denied")
                || normalized.contains("拒绝访问")
                || normalized.contains("客户端没有所需的特权")
                || normalized.contains("privilege is not held");
    }

    private void writeTopic(
            Path root,
            String name,
            MemoryKind kind,
            String description) throws IOException {
        Files.writeString(
                root.resolve(name + ".md"),
                topic(name, kind.name(), description),
                StandardCharsets.UTF_8);
    }

    private String topic(String name, String kind, String description) {
        return "---\n"
                + "kind: " + kind + "\n"
                + "name: " + name + "\n"
                + "description: " + description + "\n"
                + "updated-at: 2026-08-04\n"
                + "---\n\nbody\n";
    }

    private List<MemoryTopicHeader> headers(int count, String hook) {
        List<MemoryTopicHeader> headers = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            headers.add(new MemoryTopicHeader(
                    "topic-" + String.format("%03d", index),
                    MemoryKind.PROJECT_STATE,
                    hook,
                    LocalDate.of(2026, 8, 4),
                    "0".repeat(64)));
        }
        return headers;
    }

    private MemoryCatalog catalog(List<MemoryTopicHeader> headers) {
        return new MemoryCatalog(
                headers,
                List.of(),
                new io.github.liumaishenjian.ccjava.domain.MemoryCatalogRevision(
                        "0".repeat(64)));
    }
}
