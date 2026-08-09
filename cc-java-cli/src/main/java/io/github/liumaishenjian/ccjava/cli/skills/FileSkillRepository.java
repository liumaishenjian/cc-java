package io.github.liumaishenjian.ccjava.cli.skills;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.skill.SkillCatalog;
import io.github.liumaishenjian.ccjava.core.skill.SkillCatalogLoader;
import io.github.liumaishenjian.ccjava.core.skill.SkillContentLoader;
import io.github.liumaishenjian.ccjava.core.skill.SkillLoadingException;
import io.github.liumaishenjian.ccjava.core.skill.SkillResourceReader;
import io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillContentSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDiagnostic;
import io.github.liumaishenjian.ccjava.domain.skill.SkillErrorCode;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationPolicy;
import io.github.liumaishenjian.ccjava.domain.skill.SkillResourceSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 固定 User/Project roots 的 metadata scanner 与 lazy content/resource Adapter。
 *
 * <p>扫描只流式读取 frontmatter，随后流式计算完整文件 digest；不会把正文解码或保存在内存，
 * {@link #metadataBodyMaterializedBytes()} 因而始终为 0。所有实际正文和资源只在调用时读取，
 * 并在读取前后验证普通文件 identity、真实路径、大小和 digest。</p>
 *
 * @since 0.11.0
 */
public final class FileSkillRepository implements SkillCatalogLoader, SkillCatalog,
        SkillContentLoader, SkillResourceReader {
    public static final int MAX_SKILLS_PER_ROOT = 128;
    public static final int MAX_SKILLS_TOTAL = 256;
    public static final int MAX_SKILL_BYTES = 128 * 1024;
    public static final int MAX_SKILL_LINES = 4_000;
    public static final int MAX_RESOURCE_BYTES = 256 * 1024;
    public static final int MAX_RESOURCES_BYTES = 1024 * 1024;
    private static final int MAX_FRONTMATTER_BYTES = 32 * 1024;
    private static final Set<String> FIELDS = Set.of("name", "description", "invocation", "allowed-tools", "resources", "hooks");

    private final List<Root> roots;
    private SkillCatalogSnapshot snapshot;

    /**
     * 建立固定 roots；每个 root 必须存在、为真实普通目录且不为链接/reparse point。
     *
     * @param userRoot 固定用户 Skill root，可不存在
     * @param projectRoot 固定项目 Skill root，可不存在
     */
    public FileSkillRepository(Path userRoot, Path projectRoot) {
        roots = List.of(new Root(SkillSource.PROJECT, projectRoot, 0), new Root(SkillSource.USER, userRoot, 1));
    }

    @Override
    public synchronized SkillCatalogSnapshot load(CancellationToken cancellationToken) {
        List<Candidate> candidates = new ArrayList<>();
        List<SkillDiagnostic> diagnostics = new ArrayList<>();
        for (Root root : roots) scanRoot(root, candidates, diagnostics, cancellationToken);
        Map<SkillId, List<Candidate>> grouped = new HashMap<>();
        for (Candidate candidate : candidates) grouped.computeIfAbsent(candidate.descriptor.id(), unused -> new ArrayList<>()).add(candidate);
        List<Candidate> accepted = new ArrayList<>();
        for (var group : grouped.entrySet()) {
            if (group.getValue().size() != 1) diagnostics.add(new SkillDiagnostic(group.getKey(), SkillErrorCode.CONFLICT));
            else accepted.add(group.getValue().getFirst());
        }
        accepted.sort(Comparator.comparingInt((Candidate c) -> c.root.precedence).thenComparing(c -> c.descriptor.id()));
        if (accepted.size() > MAX_SKILLS_TOTAL) {
            for (Candidate excess : accepted.subList(MAX_SKILLS_TOTAL, accepted.size())) diagnostics.add(new SkillDiagnostic(excess.descriptor.id(), SkillErrorCode.LIMIT_EXCEEDED));
            accepted = new ArrayList<>(accepted.subList(0, MAX_SKILLS_TOTAL));
        }
        List<SkillDescriptor> descriptors = accepted.stream().map(Candidate::descriptor).toList();
        snapshot = new SkillCatalogSnapshot(catalogDigest(descriptors), descriptors, diagnostics);
        return snapshot;
    }

    @Override
    public synchronized SkillCatalogSnapshot snapshot() {
        if (snapshot == null) throw new IllegalStateException("Catalog 尚未加载");
        return snapshot;
    }

    /** @return metadata scan 解码/保留的正文 byte 数；契约固定为 0 */
    public long metadataBodyMaterializedBytes() { return 0L; }

    @Override
    public SkillContentSnapshot load(SkillDescriptor descriptor, String snapshotId, CancellationToken token) {
        Path file = resolveDescriptor(descriptor);
        try {
            FileIdentity before = identity(file, true);
            byte[] bytes = readBounded(file, MAX_SKILL_BYTES);
            if (lineCount(bytes) > MAX_SKILL_LINES || !sha256(bytes).equals(descriptor.contentDigest())) throw failure(SkillErrorCode.IDENTITY_CHANGED);
            String all = decode(bytes);
            String body = splitFrontmatter(all).body;
            FileIdentity after = identity(file, true);
            if (!before.same(after)) throw failure(SkillErrorCode.IDENTITY_CHANGED);
            return new SkillContentSnapshot(descriptor.id(), snapshotId, descriptor.contentDigest(), body);
        } catch (SkillLoadingException exception) { throw exception; }
        catch (IOException exception) { throw failure(SkillErrorCode.UNREADABLE); }
    }

    @Override
    public List<SkillResourceSnapshot> read(SkillDescriptor descriptor, CancellationToken token) {
        if (descriptor.resources().isEmpty()) return List.of();
        Path skillFile = resolveDescriptor(descriptor);
        Path skillDir = skillFile.getParent();
        List<SkillResourceSnapshot> result = new ArrayList<>();
        int total = 0;
        try {
            Path realSkillDir = requireDirectory(skillDir);
            for (String logical : descriptor.resources()) {
                if (token.isCancellationRequested()) throw failure(SkillErrorCode.CANCELLED);
                Path relative = safeRelative(logical);
                Path target = skillDir.resolve(relative).normalize();
                if (!target.startsWith(skillDir)) throw failure(SkillErrorCode.RESOURCE_REJECTED);
                FileIdentity before = identity(target, true);
                if (!before.realPath.startsWith(realSkillDir)) throw failure(SkillErrorCode.RESOURCE_REJECTED);
                byte[] bytes = readBounded(target, MAX_RESOURCE_BYTES);
                total += bytes.length;
                if (total > MAX_RESOURCES_BYTES) throw failure(SkillErrorCode.LIMIT_EXCEEDED);
                String text = decode(bytes);
                FileIdentity after = identity(target, true);
                if (!before.same(after)) throw failure(SkillErrorCode.IDENTITY_CHANGED);
                result.add(new SkillResourceSnapshot(logical, sha256(bytes), text));
            }
            return List.copyOf(result);
        } catch (SkillLoadingException exception) { throw exception; }
        catch (IOException exception) { throw failure(SkillErrorCode.RESOURCE_REJECTED); }
    }

    private void scanRoot(Root root, List<Candidate> candidates, List<SkillDiagnostic> diagnostics, CancellationToken token) {
        if (root.path == null || !Files.exists(root.path, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Path realRoot = requireDirectory(root.path);
            List<Path> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root.path)) { for (Path entry : stream) entries.add(entry); }
            entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
            int seen = 0;
            for (Path entry : entries) {
                if (token.isCancellationRequested()) return;
                if (++seen > MAX_SKILLS_PER_ROOT) { diagnostics.add(new SkillDiagnostic(null, SkillErrorCode.LIMIT_EXCEEDED)); break; }
                try {
                    Path realDir = requireDirectory(entry);
                    if (!realDir.startsWith(realRoot)) throw failure(SkillErrorCode.INVALID_METADATA);
                    Path file = entry.resolve("SKILL.md");
                    FileIdentity identity = identity(file, true);
                    if (!identity.realPath.startsWith(realDir)) throw failure(SkillErrorCode.INVALID_METADATA);
                    ParsedMetadata metadata = scanMetadata(file);
                    SkillId id = new SkillId(metadata.scalar("name"));
                    if (!entry.getFileName().toString().equals(id.value())) throw failure(SkillErrorCode.INVALID_METADATA);
                    SkillDescriptor descriptor = new SkillDescriptor(id, metadata.scalar("description"),
                            SkillInvocationPolicy.valueOf(metadata.scalarOr("invocation", "both").toUpperCase()),
                            root.source, root.source.name().toLowerCase() + "/" + id.value(), metadata.digest,
                            metadata.list("allowed-tools", 32), metadata.list("resources", 32), metadata.list("hooks", 16));
                    candidates.add(new Candidate(root, descriptor));
                } catch (IllegalArgumentException | IOException | SkillLoadingException exception) {
                    diagnostics.add(new SkillDiagnostic(safeId(entry), exception instanceof SkillLoadingException loading ? loading.code() : SkillErrorCode.INVALID_METADATA));
                }
            }
        } catch (IOException ignored) { diagnostics.add(new SkillDiagnostic(null, SkillErrorCode.UNREADABLE)); }
    }

    private ParsedMetadata scanMetadata(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            ByteArrayOutputStream front = new ByteArrayOutputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            MessageDigest digest = digest();
            boolean first = true, closed = false;
            int lines = 0, total = 0;
            int value;
            while ((value = input.read()) != -1) {
                digest.update((byte) value); total++;
                if (total > MAX_SKILL_BYTES) throw failure(SkillErrorCode.LIMIT_EXCEEDED);
                if (value == '\n') {
                    lines++;
                    String current = decode(line.toByteArray()).replace("\r", ""); line.reset();
                    if (first && !current.equals("---")) throw failure(SkillErrorCode.INVALID_METADATA);
                    if (!first && current.equals("---")) { closed = true; drainDigest(input, digest, total, lines); break; }
                    if (!first) { if (front.size() + current.length() + 1 > MAX_FRONTMATTER_BYTES) throw failure(SkillErrorCode.LIMIT_EXCEEDED); front.writeBytes((current + "\n").getBytes(StandardCharsets.UTF_8)); }
                    first = false;
                } else line.write(value);
            }
            if (!closed) throw failure(SkillErrorCode.INVALID_METADATA);
            return parseFrontmatter(decode(front.toByteArray()));
        }
    }

    private static void drainDigest(InputStream input, MessageDigest digest, int initialBytes, int initialLines) throws IOException {
        int total = initialBytes, lines = initialLines, value;
        while ((value = input.read()) != -1) { digest.update((byte) value); if (++total > MAX_SKILL_BYTES) throw failure(SkillErrorCode.LIMIT_EXCEEDED); if (value == '\n' && ++lines > MAX_SKILL_LINES) throw failure(SkillErrorCode.LIMIT_EXCEEDED); }
        DIGEST_HOLDER.set(HexFormat.of().formatHex(digest.digest()));
    }
    private static final ThreadLocal<String> DIGEST_HOLDER = new ThreadLocal<>();

    private ParsedMetadata parseFrontmatter(String text) {
        Map<String, String> scalars = new LinkedHashMap<>(); Map<String, List<String>> lists = new LinkedHashMap<>();
        String activeList = null;
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) continue;
            if (line.startsWith("  - ")) { if (activeList == null) throw failure(SkillErrorCode.INVALID_METADATA); lists.get(activeList).add(line.substring(4).trim()); continue; }
            int colon = line.indexOf(':'); if (colon <= 0) throw failure(SkillErrorCode.INVALID_METADATA);
            String key = line.substring(0, colon).trim(); String value = line.substring(colon + 1).trim();
            if (!FIELDS.contains(key) || scalars.containsKey(key) || lists.containsKey(key)) throw failure(SkillErrorCode.INVALID_METADATA);
            if (value.isEmpty() && Set.of("allowed-tools", "resources", "hooks").contains(key)) { lists.put(key, new ArrayList<>()); activeList = key; }
            else { scalars.put(key, unquote(value)); activeList = null; }
        }
        if (!scalars.containsKey("name") || !scalars.containsKey("description")) throw failure(SkillErrorCode.INVALID_METADATA);
        return new ParsedMetadata(scalars, lists, Objects.requireNonNull(DIGEST_HOLDER.get(), "digest missing"));
    }

    private Path resolveDescriptor(SkillDescriptor descriptor) {
        Root root = roots.stream().filter(r -> r.source == descriptor.source()).findFirst().orElseThrow(() -> failure(SkillErrorCode.UNREADABLE));
        return root.path.resolve(descriptor.id().value()).resolve("SKILL.md");
    }
    private static Path safeRelative(String value) { Path path = Path.of(value); if (value.isBlank() || path.isAbsolute() || value.contains("\\") || value.contains(":") || path.normalize().startsWith("..")) throw failure(SkillErrorCode.RESOURCE_REJECTED); return path; }
    private static String unquote(String value) { return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length()-1) : value; }
    private static SkillId safeId(Path path) { try { return new SkillId(path.getFileName().toString()); } catch (RuntimeException ignored) { return null; } }
    private static SkillLoadingException failure(SkillErrorCode code) { return new SkillLoadingException(code); }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static String catalogDigest(List<SkillDescriptor> entries) { MessageDigest digest = digest(); for (var e : entries) digest.update((e.id().value()+"\0"+e.source()+"\0"+e.contentDigest()+"\n").getBytes(StandardCharsets.UTF_8)); return HexFormat.of().formatHex(digest.digest()); }
    private static byte[] readBounded(Path path, int max) throws IOException { long size = Files.size(path); if (size > max) throw failure(SkillErrorCode.LIMIT_EXCEEDED); byte[] bytes = Files.readAllBytes(path); if (bytes.length > max) throw failure(SkillErrorCode.LIMIT_EXCEEDED); return bytes; }
    private static int lineCount(byte[] bytes) { int lines = bytes.length == 0 ? 0 : 1; for (byte value : bytes) if (value == '\n') lines++; return lines; }
    private static String decode(byte[] bytes) { try { CharBuffer chars = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)); return chars.toString(); } catch (CharacterCodingException e) { throw failure(SkillErrorCode.INVALID_METADATA); } }
    private static Path requireDirectory(Path path) throws IOException { BasicFileAttributes attrs = attributes(path); if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("unsafe directory"); Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS); if (!real.equals(path.toAbsolutePath().normalize())) throw new IOException("linked directory"); return real; }
    private static BasicFileAttributes attributes(Path path) throws IOException { if (Files.isSymbolicLink(path)) throw new IOException("link"); try { return Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS); } catch (UnsupportedOperationException e) { return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); } }
    private static FileIdentity identity(Path path, boolean regular) throws IOException { BasicFileAttributes attrs = attributes(path); if (regular && (!attrs.isRegularFile() || attrs.isOther())) throw new IOException("not regular"); Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS); if (!real.equals(path.toAbsolutePath().normalize())) throw new IOException("linked"); return new FileIdentity(real, attrs.fileKey(), attrs.size(), attrs.lastModifiedTime().toMillis()); }
    private static FrontmatterBody splitFrontmatter(String text) { int start = text.indexOf('\n'); int end = text.indexOf("\n---", start + 1); if (!text.startsWith("---") || end < 0) throw failure(SkillErrorCode.INVALID_METADATA); int body = text.indexOf('\n', end + 1); return new FrontmatterBody(body < 0 ? "" : text.substring(body + 1)); }

    private record Root(SkillSource source, Path path, int precedence) {}
    private record Candidate(Root root, SkillDescriptor descriptor) {}
    private record FileIdentity(Path realPath, Object key, long size, long modified) { boolean same(FileIdentity other) { return realPath.equals(other.realPath) && Objects.equals(key, other.key) && size == other.size && modified == other.modified; } }
    private record FrontmatterBody(String body) {}
    private record ParsedMetadata(Map<String,String> scalars, Map<String,List<String>> lists, String digest) {
        String scalar(String key) { String value = scalars.get(key); if (value == null || value.isBlank()) throw failure(SkillErrorCode.INVALID_METADATA); return value; }
        String scalarOr(String key, String fallback) { return scalars.getOrDefault(key, fallback); }
        List<String> list(String key, int max) { List<String> value = lists.getOrDefault(key, List.of()); if (value.size() > max || new HashSet<>(value).size() != value.size()) throw failure(SkillErrorCode.LIMIT_EXCEEDED); return List.copyOf(value); }
    }
}
