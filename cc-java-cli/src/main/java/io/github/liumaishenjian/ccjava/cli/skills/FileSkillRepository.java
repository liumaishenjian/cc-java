package io.github.liumaishenjian.ccjava.cli.skills;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.skill.ImmutableSkillCatalog;
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
import io.github.liumaishenjian.ccjava.domain.skill.SkillToolRestriction;
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
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.regex.Pattern;

/**
 * 固定 User/Project roots 的 metadata scanner 与 lazy content/resource Adapter。
 *
 * <p>metadata scan 只保留 frontmatter 字节并流式计算完整文件 digest，正文保留字节数恒为零。
 * 调用时重新验证普通文件 identity 与 digest 后才解码正文；资源同样执行 root containment、
 * NOFOLLOW、严格 UTF-8、单项及总量上限。该 Adapter 不执行正文或资源中的任何脚本。</p>
 *
 * <p>Plugin 候选只通过已验证 {@link SkillDescriptor} 的受控输入缝隙进入冲突解析；本类不读取
 * Plugin 目录、不激活插件，也不赋予其更高权限。</p>
 *
 * @since 0.11.0
 */
public final class FileSkillRepository implements SkillCatalogLoader, SkillContentLoader, SkillResourceReader,
        io.github.liumaishenjian.ccjava.core.skill.SkillRecoveryIdentityCatalog {
    /** 每个文件来源允许发现的最大 Skill 数。 */
    public static final int MAX_SKILLS_PER_ROOT = 128;
    /** 合并所有来源后允许发布的最大 Skill 数。 */
    public static final int MAX_SKILLS_TOTAL = 256;
    /** 单个 SKILL.md 允许读取的最大字节数。 */
    public static final int MAX_SKILL_BYTES = 128 * 1024;
    /** 单个 SKILL.md 允许包含的最大行数。 */
    public static final int MAX_SKILL_LINES = 4_000;
    /** 单个 Skill resource 允许读取的最大字节数。 */
    public static final int MAX_RESOURCE_BYTES = 256 * 1024;
    /** 一次 Skill resource 加载允许读取的总字节数。 */
    public static final int MAX_RESOURCES_BYTES = 1024 * 1024;
    private static final int MAX_FRONTMATTER_BYTES = 32 * 1024;
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");
    private static final Set<String> FIELDS = Set.of(
            "name", "description", "invocation", "allowed-tools", "resources", "hooks");
    private static final Set<String> LIST_FIELDS = Set.of("allowed-tools", "resources", "hooks");

    private final List<Root> roots;
    private final List<SkillDescriptor> pluginCandidates;
    private final Map<SkillId, PluginSkillSet.Entry> pluginEntries;
    private final Set<SkillCatalogSnapshot> issuedSnapshots =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final PathSafetyProbe pathSafetyProbe;
    private final MutableScanMetrics scanMetrics = new MutableScanMetrics();
    private SkillCatalogSnapshot latestSnapshot;
    private Map<SkillId, io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryIdentity> recoveryIdentities =
            Map.of();

    /**
     * 建立固定 roots。不存在的 root 视为空；存在 root 必须为非链接真实目录。
     *
     * @param userRoot 固定用户 Skill root
     * @param projectRoot 固定项目 Skill root
     */
    public FileSkillRepository(Path userRoot, Path projectRoot) {
        this(userRoot, projectRoot, List.of(), Map.of(), PathSafetyProbe.system());
    }

    /**
     * 建立带受控 Plugin descriptor 输入缝隙的扫描器。
     *
     * @param userRoot 固定用户 Skill root
     * @param projectRoot 固定项目 Skill root
     * @param pluginCandidates 已由未来 Plugin Adapter 验证的 metadata-only descriptors
     */
    public FileSkillRepository(Path userRoot, Path projectRoot, List<SkillDescriptor> pluginCandidates) {
        this(userRoot, projectRoot, pluginCandidates, Map.of(), PathSafetyProbe.system());
    }

    /**
     * 建立带 immutable Plugin Skill 物理绑定的扫描器。
     *
     * @param userRoot 固定用户 Skill root
     * @param projectRoot 固定项目 Skill root
     * @param pluginSkills 已经宿主验证并冻结的 Plugin Skill 集合
     */
    public FileSkillRepository(Path userRoot, Path projectRoot, PluginSkillSet pluginSkills) {
        this(userRoot, projectRoot, pluginSkills.entries().stream().map(PluginSkillSet.Entry::descriptor).toList(),
                index(pluginSkills), PathSafetyProbe.system());
    }

    FileSkillRepository(Path userRoot, Path projectRoot, List<SkillDescriptor> pluginCandidates,
            PathSafetyProbe pathSafetyProbe) {
        this(userRoot, projectRoot, pluginCandidates, Map.of(), pathSafetyProbe);
    }

    private FileSkillRepository(Path userRoot, Path projectRoot, List<SkillDescriptor> pluginCandidates,
            Map<SkillId, PluginSkillSet.Entry> pluginEntries, PathSafetyProbe pathSafetyProbe) {
        roots = List.of(new Root(SkillSource.PROJECT, projectRoot, 0), new Root(SkillSource.USER, userRoot, 1));
        this.pluginCandidates = List.copyOf(pluginCandidates == null ? List.of() : pluginCandidates);
        if (this.pluginCandidates.stream().anyMatch(candidate -> candidate.source() != SkillSource.PLUGIN)) {
            throw new IllegalArgumentException("Plugin 输入缝隙只接受 PLUGIN 来源 descriptor");
        }
        this.pluginEntries = Map.copyOf(pluginEntries);
        this.pathSafetyProbe = Objects.requireNonNull(pathSafetyProbe, "pathSafetyProbe 不能为空");
    }

    private static Map<SkillId, PluginSkillSet.Entry> index(PluginSkillSet skills) {
        var indexed = new LinkedHashMap<SkillId, PluginSkillSet.Entry>();
        Objects.requireNonNull(skills, "pluginSkills 不能为空").entries()
                .forEach(entry -> indexed.put(entry.descriptor().id(), entry));
        return Map.copyOf(indexed);
    }

    /**
     * 扫描 metadata 并冻结不可变快照。
     *
     * @param cancellationToken 取消令牌
     * @return 无冲突、稳定排序且不含正文的 catalog
     */
    @Override
    public synchronized SkillCatalogSnapshot load(CancellationToken cancellationToken) {
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        scanMetrics.reset();
        List<Candidate> candidates = new ArrayList<>();
        List<SkillDiagnostic> diagnostics = new ArrayList<>();
        for (Root root : roots) {
            scanRoot(root, candidates, diagnostics, cancellationToken);
        }
        for (SkillDescriptor descriptor : pluginCandidates) {
            candidates.add(new Candidate(2, descriptor));
        }

        Map<SkillId, List<Candidate>> grouped = new HashMap<>();
        for (Candidate candidate : candidates) {
            grouped.computeIfAbsent(candidate.descriptor().id(), unused -> new ArrayList<>()).add(candidate);
        }
        List<Candidate> accepted = new ArrayList<>();
        List<Map.Entry<SkillId, List<Candidate>>> groups = new ArrayList<>(grouped.entrySet());
        groups.sort(Map.Entry.comparingByKey());
        for (Map.Entry<SkillId, List<Candidate>> group : groups) {
            if (group.getValue().size() == 1) {
                accepted.add(group.getValue().getFirst());
            } else {
                diagnostics.add(new SkillDiagnostic(group.getKey(), SkillErrorCode.CONFLICT));
            }
        }
        accepted.sort(Comparator.comparingInt(Candidate::precedence)
                .thenComparing(candidate -> candidate.descriptor().id()));
        if (accepted.size() > MAX_SKILLS_TOTAL) {
            for (Candidate excess : accepted.subList(MAX_SKILLS_TOTAL, accepted.size())) {
                diagnostics.add(new SkillDiagnostic(excess.descriptor().id(), SkillErrorCode.LIMIT_EXCEEDED));
            }
            accepted = new ArrayList<>(accepted.subList(0, MAX_SKILLS_TOTAL));
        }
        diagnostics.sort(Comparator.comparing(
                diagnostic -> diagnostic.skillId() == null ? "" : diagnostic.skillId().value()));
        List<SkillDescriptor> descriptors = accepted.stream().map(Candidate::descriptor).toList();
        latestSnapshot = new SkillCatalogSnapshot(catalogDigest(descriptors), descriptors, diagnostics);
        recoveryIdentities = freezePluginRecoveryIdentities(descriptors);
        issuedSnapshots.add(latestSnapshot);
        return latestSnapshot;
    }

    /**
     * 为 Session 捕获当前不可变 snapshot。
     *
     * @return 不受后续 {@link #load(CancellationToken)} 影响的 Catalog
     */
    public synchronized SkillCatalog freezeCatalog() {
        if (latestSnapshot == null) {
            throw new IllegalStateException("Catalog 尚未加载");
        }
        return new ImmutableSkillCatalog(latestSnapshot);
    }

    @Override
    public synchronized java.util.Optional<io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryIdentity> find(
            SkillId skillId) {
        Objects.requireNonNull(skillId, "skillId 不能为空");
        var frozen = recoveryIdentities.get(skillId);
        if (frozen != null) return java.util.Optional.of(frozen);
        if (latestSnapshot == null) return java.util.Optional.empty();
        return latestSnapshot.entries().stream().filter(descriptor -> descriptor.id().equals(skillId))
                .findFirst().map(this::localRecoveryIdentity);
    }

    /**
     * 返回最近一次 metadata scan 的可观测读取指标。
     *
     * @return 不含正文内容的扫描字节指标
     */
    public synchronized ScanMetrics scanMetrics() {
        return scanMetrics.snapshot();
    }

    /**
     * 返回 metadata scan 解码或保留的正文 byte 数。
     *
     * @return 启动扫描中 materialize 的正文字节数，正常应为零
     */
    public synchronized long metadataBodyMaterializedBytes() {
        return scanMetrics.bodyMaterializedBytes;
    }

    /**
     * 重新校验 catalog identity 并懒加载 Markdown 正文。
     *
     * @throws SkillLoadingException 文件变化、取消、编码或限制失败时
     */
    @Override
    public SkillContentSnapshot load(
            SkillCatalogSnapshot snapshot, SkillDescriptor descriptor, CancellationToken cancellationToken) {
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        requireBoundDescriptor(snapshot, descriptor);
        if (cancellationToken.isCancellationRequested()) {
            throw failure(SkillErrorCode.CANCELLED);
        }
        PluginSkillSet.Entry plugin = pluginEntries.get(descriptor.id());
        if (plugin != null) {
            return new SkillContentSnapshot(descriptor.id(), snapshot.snapshotId(),
                    descriptor.contentDigest(), plugin.markdown());
        }
        Path file = resolveLocalDescriptor(descriptor);
        try {
            FileIdentity before = identity(file, FileKind.REGULAR_FILE);
            byte[] bytes = readBounded(file, MAX_SKILL_BYTES);
            if (countLines(bytes) > MAX_SKILL_LINES || !sha256(bytes).equals(descriptor.contentDigest())) {
                throw failure(SkillErrorCode.IDENTITY_CHANGED);
            }
            String markdown = splitFrontmatter(decode(bytes)).body();
            FileIdentity after = identity(file, FileKind.REGULAR_FILE);
            if (!before.same(after)) {
                throw failure(SkillErrorCode.IDENTITY_CHANGED);
            }
            return new SkillContentSnapshot(descriptor.id(), snapshot.snapshotId(),
                    descriptor.contentDigest(), markdown);
        } catch (SkillLoadingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(SkillErrorCode.UNREADABLE);
        }
    }

    /**
     * 懒加载 descriptor 声明的资源；资源只成为不可信文本快照，不会被执行。
     *
     * @throws SkillLoadingException 越界、链接、竞态、编码或上限失败时
     */
    @Override
    public List<SkillResourceSnapshot> read(
            SkillCatalogSnapshot snapshot, SkillDescriptor descriptor,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        requireBoundDescriptor(snapshot, descriptor);
        if (descriptor.resources().isEmpty()) {
            return List.of();
        }
        PluginSkillSet.Entry plugin = pluginEntries.get(descriptor.id());
        if (plugin != null) {
            return plugin.resources();
        }
        Path skillFile = resolveLocalDescriptor(descriptor);
        Path skillDirectory = skillFile.getParent();
        List<SkillResourceSnapshot> resources = new ArrayList<>();
        long total = 0;
        try {
            Path realSkillDirectory = identity(skillDirectory, FileKind.DIRECTORY).realPath();
            for (String logicalName : descriptor.resources()) {
                if (cancellationToken.isCancellationRequested()) {
                    throw failure(SkillErrorCode.CANCELLED);
                }
                Path relative = safeRelativeResource(logicalName);
                Path target = skillDirectory.resolve(relative).normalize();
                if (!target.startsWith(skillDirectory)) {
                    throw failure(SkillErrorCode.RESOURCE_REJECTED);
                }
                FileIdentity before = identity(target, FileKind.REGULAR_FILE);
                if (!before.realPath().startsWith(realSkillDirectory)) {
                    throw failure(SkillErrorCode.RESOURCE_REJECTED);
                }
                byte[] bytes = readBounded(target, MAX_RESOURCE_BYTES);
                total += bytes.length;
                if (total > MAX_RESOURCES_BYTES) {
                    throw failure(SkillErrorCode.LIMIT_EXCEEDED);
                }
                String text;
                try {
                    text = decode(bytes);
                } catch (SkillLoadingException exception) {
                    throw failure(SkillErrorCode.RESOURCE_REJECTED);
                }
                FileIdentity after = identity(target, FileKind.REGULAR_FILE);
                if (!before.same(after)) {
                    throw failure(SkillErrorCode.IDENTITY_CHANGED);
                }
                resources.add(new SkillResourceSnapshot(logicalName, sha256(bytes), text));
            }
            return List.copyOf(resources);
        } catch (SkillLoadingException exception) {
            throw exception;
        } catch (IOException | InvalidPathException exception) {
            throw failure(SkillErrorCode.RESOURCE_REJECTED);
        }
    }

    private Map<SkillId, io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryIdentity>
            freezePluginRecoveryIdentities(List<SkillDescriptor> descriptors) {
        Map<SkillId, io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryIdentity> result =
                new LinkedHashMap<>();
        String empty = digestStrings(List.of());
        for (SkillDescriptor descriptor : descriptors) {
            PluginSkillSet.Entry plugin = pluginEntries.get(descriptor.id());
            if (plugin == null) continue;
            result.put(descriptor.id(), new io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryIdentity(
                    descriptor.id(), plugin.manifestDigest(), plugin.bodyDigest(), descriptor.contentDigest(),
                    plugin.resourcesDigest(), plugin.toolDigest(), plugin.hookDigest(), plugin.pluginTreeDigest(),
                    plugin.pluginManifestDigest(), plugin.configDigest()));
        }
        return Map.copyOf(result);
    }

    private io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryIdentity localRecoveryIdentity(
            SkillDescriptor descriptor) {
        try {
            byte[] bytes = readBounded(resolveLocalDescriptor(descriptor), MAX_SKILL_BYTES);
            String normalized = decode(bytes).replace("\r\n", "\n");
            int end = normalized.indexOf("\n---\n", 4);
            String manifest;
            String body;
            if (end >= 0 && normalized.startsWith("---\n")) {
                manifest = normalized.substring(4, end) + "\n";
                body = normalized.substring(end + 5);
            } else if (normalized.startsWith("---\n") && normalized.endsWith("\n---")) {
                manifest = normalized.substring(4, normalized.length() - 4) + "\n";
                body = "";
            } else {
                throw failure(SkillErrorCode.INVALID_METADATA);
            }
            List<String> resourceIdentities = new ArrayList<>();
            Path directory = resolveLocalDescriptor(descriptor).getParent();
            long total = 0;
            for (String name : descriptor.resources()) {
                byte[] resource = readBounded(directory.resolve(safeRelativeResource(name)).normalize(),
                        MAX_RESOURCE_BYTES);
                total += resource.length;
                if (total > MAX_RESOURCES_BYTES) throw failure(SkillErrorCode.LIMIT_EXCEEDED);
                resourceIdentities.add(name + "\0" + sha256(resource));
            }
            String empty = digestStrings(List.of());
            return new io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryIdentity(
                    descriptor.id(), sha256(manifest.getBytes(StandardCharsets.UTF_8)),
                    sha256(body.getBytes(StandardCharsets.UTF_8)), descriptor.contentDigest(),
                    digestStrings(resourceIdentities), digestStrings(descriptor.toolRestriction().toolNames()),
                    digestStrings(descriptor.hooks()), empty, empty, empty);
        } catch (IOException exception) {
            throw failure(SkillErrorCode.UNREADABLE);
        }
    }

    private static String digestStrings(List<String> values) {
        MessageDigest digest = newDigest();
        values.stream().sorted().forEach(value -> {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private synchronized void requireBoundDescriptor(
            SkillCatalogSnapshot snapshot, SkillDescriptor descriptor) {
        if (snapshot == null || descriptor == null || !issuedSnapshots.contains(snapshot)) {
            throw failure(SkillErrorCode.IDENTITY_CHANGED);
        }
        SkillDescriptor published = snapshot.entries().stream()
                .filter(entry -> entry.id().equals(descriptor.id()))
                .findFirst().orElseThrow(() -> failure(SkillErrorCode.IDENTITY_CHANGED));
        if (!published.equals(descriptor)) {
            throw failure(SkillErrorCode.IDENTITY_CHANGED);
        }
    }

    private void scanRoot(Root root, List<Candidate> candidates, List<SkillDiagnostic> diagnostics,
            CancellationToken cancellationToken) {
        if (root.path() == null || !Files.exists(root.path(), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Path realRoot = identity(root.path(), FileKind.DIRECTORY).realPath();
            List<Path> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root.path())) {
                for (Path entry : stream) {
                    entries.add(entry);
                }
            }
            entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
            int directoryCount = 0;
            for (Path entry : entries) {
                if (cancellationToken.isCancellationRequested()) {
                    return;
                }
                BasicFileAttributes attributes;
                try {
                    attributes = readAttributes(entry);
                } catch (IOException exception) {
                    diagnostics.add(new SkillDiagnostic(safeId(entry), SkillErrorCode.UNREADABLE));
                    continue;
                }
                if (!attributes.isDirectory()) {
                    continue;
                }
                if (++directoryCount > MAX_SKILLS_PER_ROOT) {
                    diagnostics.add(new SkillDiagnostic(safeId(entry), SkillErrorCode.LIMIT_EXCEEDED));
                    continue;
                }
                try {
                    Path realDirectory = identity(entry, FileKind.DIRECTORY).realPath();
                    if (!realDirectory.startsWith(realRoot)) {
                        throw failure(SkillErrorCode.INVALID_METADATA);
                    }
                    Path skillFile = entry.resolve("SKILL.md");
                    FileIdentity before = identity(skillFile, FileKind.REGULAR_FILE);
                    if (!before.realPath().startsWith(realDirectory)) {
                        throw failure(SkillErrorCode.INVALID_METADATA);
                    }
                    ParsedMetadata metadata = scanMetadata(skillFile);
                    FileIdentity after = identity(skillFile, FileKind.REGULAR_FILE);
                    if (!before.same(after)) {
                        throw failure(SkillErrorCode.IDENTITY_CHANGED);
                    }
                    SkillId id = new SkillId(metadata.requiredScalar("name"));
                    if (!entry.getFileName().toString().equals(id.value())) {
                        throw failure(SkillErrorCode.INVALID_METADATA);
                    }
                    SkillInvocationPolicy policy = parseInvocation(metadata.scalarOr("invocation", "both"));
                    List<String> allowedTools = metadata.list("allowed-tools", 32);
                    SkillToolRestriction restriction = metadata.hasField("allowed-tools")
                            ? SkillToolRestriction.declared(allowedTools)
                            : SkillToolRestriction.unspecified();
                    List<String> resourceNames = metadata.list("resources", 32);
                    for (String resourceName : resourceNames) {
                        safeRelativeResource(resourceName);
                    }
                    SkillDescriptor descriptor = new SkillDescriptor(
                            id,
                            metadata.requiredScalar("description"),
                            policy,
                            root.source(),
                            root.source().name().toLowerCase() + "/" + id.value(),
                            metadata.digest(),
                            restriction,
                            resourceNames,
                            metadata.list("hooks", 16));
                    candidates.add(new Candidate(root.precedence(), descriptor));
                } catch (IllegalArgumentException | IOException | SkillLoadingException exception) {
                    SkillErrorCode code = exception instanceof SkillLoadingException loading
                            ? loading.code() : SkillErrorCode.INVALID_METADATA;
                    diagnostics.add(new SkillDiagnostic(safeId(entry), code));
                }
            }
        } catch (IOException exception) {
            diagnostics.add(new SkillDiagnostic(null, SkillErrorCode.UNREADABLE));
        }
    }

    private ParsedMetadata scanMetadata(Path file) throws IOException {
        MessageDigest digest = newDigest();
        ByteArrayOutputStream frontmatter = new ByteArrayOutputStream();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean firstLine = true;
        boolean frontmatterClosed = false;
        boolean collectFrontmatter = false;
        int totalBytes = 0;
        int lines = 0;
        int lastValue = -1;
        try (InputStream input = Files.newInputStream(file)) {
            int value;
            while ((value = input.read()) != -1) {
                lastValue = value;
                digest.update((byte) value);
                scanMetrics.digestBytes++;
                totalBytes++;
                if (totalBytes > MAX_SKILL_BYTES) {
                    throw failure(SkillErrorCode.LIMIT_EXCEEDED);
                }
                if (value == '\n') {
                    lines++;
                    if (lines > MAX_SKILL_LINES) {
                        throw failure(SkillErrorCode.LIMIT_EXCEEDED);
                    }
                    String current = decodeLine(line.toByteArray());
                    line.reset();
                    if (!frontmatterClosed) {
                        if (firstLine) {
                            if (!current.equals("---")) {
                                throw failure(SkillErrorCode.INVALID_METADATA);
                            }
                            collectFrontmatter = true;
                        } else if (current.equals("---")) {
                            frontmatterClosed = true;
                            collectFrontmatter = false;
                        } else if (collectFrontmatter) {
                            int beforeSize = frontmatter.size();
                            appendFrontmatter(frontmatter, current);
                            scanMetrics.frontmatterMaterializedBytes += frontmatter.size() - beforeSize;
                        }
                        firstLine = false;
                    }
                } else if (!frontmatterClosed) {
                    line.write(value);
                    if (line.size() > MAX_FRONTMATTER_BYTES) {
                        throw failure(SkillErrorCode.LIMIT_EXCEEDED);
                    }
                }
            }
        }
        if (totalBytes > 0 && lastValue != '\n') {
            lines++;
            if (lines > MAX_SKILL_LINES) {
                throw failure(SkillErrorCode.LIMIT_EXCEEDED);
            }
            if (!frontmatterClosed) {
                String current = decodeLine(line.toByteArray());
                if (!firstLine && current.equals("---")) {
                    frontmatterClosed = true;
                }
            }
        }
        if (lines > MAX_SKILL_LINES || !frontmatterClosed) {
            throw failure(lines > MAX_SKILL_LINES ? SkillErrorCode.LIMIT_EXCEEDED : SkillErrorCode.INVALID_METADATA);
        }
        return parseFrontmatter(decode(frontmatter.toByteArray()), HexFormat.of().formatHex(digest.digest()));
    }

    private static void appendFrontmatter(ByteArrayOutputStream frontmatter, String line) {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (frontmatter.size() + bytes.length > MAX_FRONTMATTER_BYTES) {
            throw failure(SkillErrorCode.LIMIT_EXCEEDED);
        }
        frontmatter.writeBytes(bytes);
    }

    private static ParsedMetadata parseFrontmatter(String text, String digest) {
        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        Set<String> present = new HashSet<>();
        String activeList = null;
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("  - ")) {
                if (activeList == null) {
                    throw failure(SkillErrorCode.INVALID_METADATA);
                }
                String value = unquote(line.substring(4).trim());
                if (value.isBlank()) {
                    throw failure(SkillErrorCode.INVALID_METADATA);
                }
                lists.get(activeList).add(value);
                continue;
            }
            if (Character.isWhitespace(line.codePointAt(0))) {
                throw failure(SkillErrorCode.INVALID_METADATA);
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw failure(SkillErrorCode.INVALID_METADATA);
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!FIELDS.contains(key) || !present.add(key)) {
                throw failure(SkillErrorCode.INVALID_METADATA);
            }
            if (LIST_FIELDS.contains(key)) {
                if (!value.isEmpty()) {
                    throw failure(SkillErrorCode.INVALID_METADATA);
                }
                lists.put(key, new ArrayList<>());
                activeList = key;
            } else {
                if (value.isEmpty()) {
                    throw failure(SkillErrorCode.INVALID_METADATA);
                }
                scalars.put(key, unquote(value));
                activeList = null;
            }
        }
        if (!scalars.containsKey("name") || !scalars.containsKey("description")) {
            throw failure(SkillErrorCode.INVALID_METADATA);
        }
        return new ParsedMetadata(scalars, lists, present, digest);
    }

    private Path resolveLocalDescriptor(SkillDescriptor descriptor) {
        if (descriptor.source() == SkillSource.PLUGIN) {
            PluginSkillSet.Entry entry = pluginEntries.get(descriptor.id());
            if (entry == null || !entry.descriptor().equals(descriptor)) {
                throw failure(SkillErrorCode.UNREADABLE);
            }
            return entry.skillFile();
        }
        Root root = roots.stream().filter(candidate -> candidate.source() == descriptor.source()).findFirst()
                .orElseThrow(() -> failure(SkillErrorCode.UNREADABLE));
        if (root.path() == null) {
            throw failure(SkillErrorCode.UNREADABLE);
        }
        return root.path().resolve(descriptor.id().value()).resolve("SKILL.md");
    }

    private static Path safeRelativeResource(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.startsWith("\\")
                || WINDOWS_DRIVE.matcher(value).matches() || value.contains("\\") || value.indexOf('\0') >= 0) {
            throw failure(SkillErrorCode.RESOURCE_REJECTED);
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException exception) {
            throw failure(SkillErrorCode.RESOURCE_REJECTED);
        }
        if (path.isAbsolute() || path.normalize().startsWith("..")) {
            throw failure(SkillErrorCode.RESOURCE_REJECTED);
        }
        for (Path segment : path) {
            if (segment.toString().equals("..")) {
                throw failure(SkillErrorCode.RESOURCE_REJECTED);
            }
        }
        return path.normalize();
    }

    private static SkillInvocationPolicy parseInvocation(String value) {
        return switch (value) {
            case "explicit" -> SkillInvocationPolicy.EXPLICIT;
            case "model" -> SkillInvocationPolicy.MODEL;
            case "both" -> SkillInvocationPolicy.BOTH;
            default -> throw failure(SkillErrorCode.INVALID_METADATA);
        };
    }

    private FileIdentity identity(Path path, FileKind kind) throws IOException {
        pathSafetyProbe.rejectReparsePoint(path);
        BasicFileAttributes attributes = readAttributes(path);
        if (attributes.isSymbolicLink() || attributes.isOther()
                || (kind == FileKind.REGULAR_FILE && !attributes.isRegularFile())
                || (kind == FileKind.DIRECTORY && !attributes.isDirectory())) {
            throw new IOException("unsafe file type");
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path real = path.toRealPath();
        if (!real.equals(absolute)) {
            throw new IOException("linked or reparse path");
        }
        return new FileIdentity(real, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime().toMillis());
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("symbolic link");
        }
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static byte[] readBounded(Path path, int maximum) throws IOException {
        long size = Files.size(path);
        if (size > maximum) {
            throw failure(SkillErrorCode.LIMIT_EXCEEDED);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                throw failure(SkillErrorCode.LIMIT_EXCEEDED);
            }
            return bytes;
        }
    }

    private static FrontmatterBody splitFrontmatter(String text) {
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            throw failure(SkillErrorCode.INVALID_METADATA);
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            if (normalized.endsWith("\n---")) {
                return new FrontmatterBody("");
            }
            throw failure(SkillErrorCode.INVALID_METADATA);
        }
        return new FrontmatterBody(normalized.substring(end + 5));
    }

    private static String decodeLine(byte[] bytes) {
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        byte[] line = length == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, length);
        return decode(line);
    }

    private static String decode(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            if (decoded.length() > 0 && decoded.charAt(0) == '﻿') {
                throw failure(SkillErrorCode.INVALID_METADATA);
            }
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw failure(SkillErrorCode.UNREADABLE);
        }
    }

    private static int countLines(byte[] bytes) {
        if (bytes.length == 0) {
            return 0;
        }
        int lines = 1;
        for (byte value : bytes) {
            if (value == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        if (value.startsWith("\"") || value.endsWith("\"")) {
            throw failure(SkillErrorCode.INVALID_METADATA);
        }
        return value;
    }

    private static SkillId safeId(Path path) {
        try {
            return new SkillId(path.getFileName().toString());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String catalogDigest(List<SkillDescriptor> descriptors) {
        MessageDigest digest = newDigest();
        for (SkillDescriptor descriptor : descriptors) {
            String canonical = descriptor.id().value() + "\0" + descriptor.source() + "\0"
                    + descriptor.contentDigest() + "\0" + descriptor.policy() + "\0"
                    + descriptor.toolRestriction().declared() + "\0"
                    + String.join("\0", descriptor.toolRestriction().toolNames()) + "\n";
            digest.update(canonical.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private static SkillLoadingException failure(SkillErrorCode code) {
        return new SkillLoadingException(code);
    }

    /**
     * metadata-first 扫描的隐私安全字节指标。
     *
     * @param digestBytes 为身份校验流式读取的字节数
     * @param frontmatterMaterializedBytes 实际 materialize 的 metadata 字节数
     * @param bodyMaterializedBytes 启动扫描中 materialize 的正文字节数，应保持为零
     */
    public record ScanMetrics(
            long digestBytes,
            long frontmatterMaterializedBytes,
            long bodyMaterializedBytes) {
    }

    @FunctionalInterface
    interface PathSafetyProbe {
        void rejectReparsePoint(Path path) throws IOException;

        static PathSafetyProbe system() {
            return path -> {
                try {
                    var attributes = Files.readAttributes(path,
                            java.nio.file.attribute.DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isOther()) {
                        throw new IOException("reparse point");
                    }
                } catch (UnsupportedOperationException ignored) {
                    // 非 DOS 文件系统仍由 BasicFileAttributes、NOFOLLOW 和 real-path equality 校验。
                }
            };
        }
    }

    private static final class MutableScanMetrics {
        private long digestBytes;
        private long frontmatterMaterializedBytes;
        private long bodyMaterializedBytes;

        private void reset() {
            digestBytes = 0;
            frontmatterMaterializedBytes = 0;
            bodyMaterializedBytes = 0;
        }

        private ScanMetrics snapshot() {
            return new ScanMetrics(digestBytes, frontmatterMaterializedBytes, bodyMaterializedBytes);
        }
    }

    private enum FileKind { REGULAR_FILE, DIRECTORY }

    private record Root(SkillSource source, Path path, int precedence) {}

    private record Candidate(int precedence, SkillDescriptor descriptor) {}

    private record FileIdentity(Path realPath, Object fileKey, long size, long modifiedMillis) {
        private boolean same(FileIdentity other) {
            return realPath.equals(other.realPath)
                    && Objects.equals(fileKey, other.fileKey)
                    && size == other.size
                    && modifiedMillis == other.modifiedMillis;
        }
    }

    private record FrontmatterBody(String body) {}

    private record ParsedMetadata(Map<String, String> scalars, Map<String, List<String>> lists,
            Set<String> presentFields, String digest) {
        private String requiredScalar(String key) {
            String value = scalars.get(key);
            if (value == null || value.isBlank()) {
                throw failure(SkillErrorCode.INVALID_METADATA);
            }
            return value;
        }

        private String scalarOr(String key, String fallback) {
            return scalars.getOrDefault(key, fallback);
        }

        private boolean hasField(String key) {
            return presentFields.contains(key);
        }

        private List<String> list(String key, int maximum) {
            List<String> values = lists.getOrDefault(key, List.of());
            if (values.size() > maximum || values.stream().distinct().count() != values.size()) {
                throw failure(SkillErrorCode.INVALID_METADATA);
            }
            return List.copyOf(values);
        }
    }
}
