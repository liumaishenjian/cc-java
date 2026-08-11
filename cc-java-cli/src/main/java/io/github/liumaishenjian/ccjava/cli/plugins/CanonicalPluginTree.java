package io.github.liumaishenjian.ccjava.cli.plugins;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginErrorCode;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginFingerprint;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 对 ordinary directory package 计算稳定、平台无关的 canonical tree SHA-256。
 *
 * <p>摘要编码相对路径、文件类型、长度与内容摘要，按逻辑路径排序；不编码绝对路径或 mtime。
 * 扫描同时以 NOFOLLOW、realpath containment、reparse probe 与读前/读后 identity 阻断逃逸和 TOCTOU。</p>
 *
 * @since 0.11.0
 */
public final class CanonicalPluginTree {
    /** 单个 Plugin package 的普通文件数上限。 */
    public static final int MAX_FILES = 1_024;
    /** 单个 Plugin package 的普通文件总字节上限。 */
    public static final long MAX_TOTAL_BYTES = 32L * 1_024 * 1_024;
    private static final String ZERO_DIGEST = "0".repeat(64);
    private final PathSafetyProbe pathSafetyProbe;

    /** 创建使用平台 reparse/junction 探测的扫描器。 */
    public CanonicalPluginTree() {
        this(CanonicalPluginTree::platformReparsePoint);
    }

    /**
     * 注入确定性的 Windows junction/reparse 检测 seam。
     *
     * @param pathSafetyProbe 每次目录项访问前执行的 reparse 检查
     */
    public CanonicalPluginTree(PathSafetyProbe pathSafetyProbe) {
        this.pathSafetyProbe = Objects.requireNonNull(pathSafetyProbe, "pathSafetyProbe 不能为空");
    }

    /**
     * 扫描固定目录并与 strict manifest 组合成不可变 snapshot。
     *
     * @param root ordinary directory package root
     * @param parsed 已校验 manifest 与输入摘要
     * @return 绑定 canonical tree digest 的 immutable snapshot
     */
    public PluginSnapshot scan(Path root, PluginManifestParser.ParsedPluginManifest parsed) {
        Objects.requireNonNull(root, "root 不能为空");
        Objects.requireNonNull(parsed, "parsed 不能为空");
        try {
            Path absolute = root.toAbsolutePath().normalize();
            BasicFileAttributes rootBefore = attributes(absolute);
            if (!rootBefore.isDirectory() || rejected(absolute)) {
                throw failure(PluginErrorCode.PACKAGE_NOT_DIRECTORY);
            }
            Path realRoot = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
            var entries = new ArrayList<Entry>();
            try (var paths = Files.walk(absolute)) {
                for (Path path : paths.toList()) {
                    if (path.equals(absolute)) continue;
                    Path normalized = path.toAbsolutePath().normalize();
                    if (!normalized.startsWith(absolute) || rejected(normalized)) {
                        throw failure(PluginErrorCode.LINK_OR_SPECIAL_FILE_REJECTED);
                    }
                    BasicFileAttributes before = attributes(normalized);
                    Path real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
                    if (!real.startsWith(realRoot)) throw failure(PluginErrorCode.PATH_REJECTED);
                    String logical = logicalName(absolute.relativize(normalized));
                    if (before.isDirectory()) {
                        entries.add(new Entry(logical, 'D', 0, ZERO_DIGEST));
                    } else if (before.isRegularFile()) {
                        long regularCount = entries.stream().filter(entry -> entry.type == 'F').count();
                        if (regularCount >= MAX_FILES) {
                            throw failure(PluginErrorCode.TREE_FILE_LIMIT_EXCEEDED);
                        }
                        entries.add(readFile(normalized, logical, before));
                    } else {
                        throw failure(PluginErrorCode.LINK_OR_SPECIAL_FILE_REJECTED);
                    }
                }
            }
            long total = entries.stream().filter(entry -> entry.type == 'F').mapToLong(entry -> entry.length).sum();
            if (total > MAX_TOTAL_BYTES) throw failure(PluginErrorCode.TREE_SIZE_LIMIT_EXCEEDED);
            if (!sameIdentity(rootBefore, attributes(absolute))) throw failure(PluginErrorCode.CONTENT_CHANGED);
            entries.sort(Comparator.comparing(Entry::logicalName));
            verifyDeclaredComponents(parsed, entries);
            String treeDigest = treeDigest(entries);
            var manifest = parsed.manifest();
            var fingerprint = new PluginFingerprint(
                    manifest.id(), manifest.version(), treeDigest, parsed.manifestDigest());
            return new PluginSnapshot(manifest, fingerprint, treeDigest.substring(0, 32));
        } catch (PluginBoundaryException failure) {
            throw failure;
        } catch (IOException failure) {
            throw failure(PluginErrorCode.PATH_REJECTED);
        } catch (Exception failure) {
            throw failure(PluginErrorCode.CONTENT_CHANGED);
        }
    }

    private Entry readFile(Path path, String logical, BasicFileAttributes before) throws Exception {
        if (before.size() > MAX_TOTAL_BYTES) throw failure(PluginErrorCode.TREE_SIZE_LIMIT_EXCEEDED);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long bytes = 0;
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             InputStream input = Channels.newInputStream(channel)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                bytes += read;
                if (bytes > MAX_TOTAL_BYTES) throw failure(PluginErrorCode.TREE_SIZE_LIMIT_EXCEEDED);
                digest.update(buffer, 0, read);
            }
        }
        BasicFileAttributes after = attributes(path);
        if (bytes != before.size() || !sameIdentity(before, after)) {
            throw failure(PluginErrorCode.CONTENT_CHANGED);
        }
        return new Entry(logical, 'F', bytes, HexFormat.of().formatHex(digest.digest()));
    }

    private boolean rejected(Path path) throws IOException {
        return Files.isSymbolicLink(path) || pathSafetyProbe.isReparseOrJunction(path);
    }

    private static boolean platformReparsePoint(Path path) throws IOException {
        try {
            return Files.readAttributes(path, java.nio.file.attribute.DosFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isOther();
        } catch (UnsupportedOperationException ignored) {
            return false;
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean sameIdentity(BasicFileAttributes left, BasicFileAttributes right) {
        return left.isDirectory() == right.isDirectory()
                && left.isRegularFile() == right.isRegularFile()
                && left.size() == right.size()
                && Objects.equals(left.fileKey(), right.fileKey())
                && left.lastModifiedTime().equals(right.lastModifiedTime());
    }

    private static String logicalName(Path relative) {
        String value = relative.toString().replace('\\', '/');
        if (value.isBlank() || value.startsWith("/") || value.contains("//")
                || List.of(value.split("/", -1)).contains("..")) {
            throw failure(PluginErrorCode.PATH_REJECTED);
        }
        return value;
    }

    private static void verifyDeclaredComponents(
            PluginManifestParser.ParsedPluginManifest parsed, List<Entry> entries) {
        var files = entries.stream().filter(entry -> entry.type == 'F')
                .map(Entry::logicalName).collect(java.util.stream.Collectors.toSet());
        if (parsed.manifest().components().stream()
                .anyMatch(component -> !files.contains(component.logicalPath()))) {
            throw failure(PluginErrorCode.MANIFEST_INVALID);
        }
    }

    private static String treeDigest(List<Entry> entries) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateInt(digest, entries.size());
        for (Entry entry : entries) {
            byte[] name = entry.logicalName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            updateInt(digest, name.length);
            digest.update(name);
            digest.update((byte) entry.type);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(entry.length).array());
            digest.update(HexFormat.of().parseHex(entry.contentDigest));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static PluginBoundaryException failure(PluginErrorCode code) {
        return new PluginBoundaryException(code);
    }

    private record Entry(String logicalName, char type, long length, String contentDigest) { }

    /** Windows Adapter 或测试 seam 用于拒绝 junction/reparse point。 */
    @FunctionalInterface
    public interface PathSafetyProbe {
        /**
         * 检查目录项是否为 Windows reparse point 或 junction。
         *
         * @param path 待检查的 NOFOLLOW 路径
         * @return 必须拒绝时为 true
         * @throws IOException 无法证明路径类型时
         */
        boolean isReparseOrJunction(Path path) throws IOException;
    }
}
