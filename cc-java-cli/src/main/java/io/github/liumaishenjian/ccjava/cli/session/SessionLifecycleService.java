package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.core.session.RetentionAction;
import io.github.liumaishenjian.ccjava.core.session.RetentionDecision;
import io.github.liumaishenjian.ccjava.core.session.SessionExportPolicy;
import io.github.liumaishenjian.ccjava.core.session.SessionIndexEntry;
import io.github.liumaishenjian.ccjava.core.session.SessionLifecycleStatus;
import io.github.liumaishenjian.ccjava.core.session.SessionRetentionPolicy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 从 {@link FileSessionStore} canonical JSONL 推导 Export、Retention、Migration 与 Index 的生产服务。
 *
 * <p>客户端只提交 session identity、导出 policy 和删除确认，不能提交 workspace、record 正文或
 * lifecycle status。服务端在每次控制操作前重新读取 canonical、验证结构与恢复状态，并以实际
 * writer lock 和 migration 工件作为 fence。Index 只是可重建 projection，任何冲突均以 canonical
 * 和锁状态为准。正文导出逐字段脱敏，不复制 Provider/header/secret 候选。</p>
 *
 * @since 0.1.0
 */
public final class SessionLifecycleService {
    private static final long MAX_CANONICAL_BYTES = 64L * 1024 * 1024;
    private static final int MAX_RECORDS = 20_000;
    private static final int MAX_EXPORT_TEXT = 65_536;
    private final Path root;
    private final Path archiveRoot;
    private final FileSessionIndex index;
    private final SessionExportService exports = new SessionExportService();
    private final SessionRetentionPolicy retention = new SessionRetentionPolicy();
    private final SessionMigrationCoordinator migrations = new SessionMigrationCoordinator();
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    /**
     * 打开固定 Session store，并从 canonical metadata 重建派生索引。
     *
     * @param sessionRoot Session store root
     */
    public SessionLifecycleService(Path sessionRoot) {
        try {
            Path normalized = Objects.requireNonNull(sessionRoot, "sessionRoot 不能为空").toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            if (Files.isSymbolicLink(normalized)) throw new IOException();
            root = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
            archiveRoot = root.resolve("archive");
            Files.createDirectories(archiveRoot);
            if (Files.isSymbolicLink(archiveRoot)) throw new IOException();
            index = new FileSessionIndex(root.resolve("index"));
            refreshIndex();
        } catch (IOException failure) {
            throw new IllegalStateException("Session lifecycle root 非法", failure);
        }
    }

    /**
     * 从 canonical 服务端读取；默认 metadata-only，正文需 redacted+confirmed 双重 Gate。
     *
     * @param sessionId 要导出的 Session identity
     * @param includeContent 是否请求包含正文
     * @param redacted 调用方是否已要求脱敏
     * @param confirmed 是否针对本次正文导出明确确认
     * @return 稳定 Session Export v1 字节
     */
    public byte[] export(String sessionId, boolean includeContent, boolean redacted, boolean confirmed) {
        CanonicalSnapshot snapshot = inspect(sessionId);
        if (snapshot.status() == SessionLifecycleStatus.UNCERTAIN) {
            throw new IllegalArgumentException("canonical uncertain");
        }
        SessionExportPolicy policy = includeContent
                ? new SessionExportPolicy(true, redacted, confirmed) : SessionExportPolicy.metadataOnly();
        return exports.export(snapshot.sessionId(), snapshot.workspaceIdentity(),
                includeContent ? snapshot.redactedRecords() : List.of(), policy);
    }

    /** 重新扫描真实 Session metadata；损坏项以 UNCERTAIN 保留，不静默丢失。 */
    public synchronized void refreshIndex() {
        ArrayList<SessionIndexEntry> entries = new ArrayList<>();
        try (var children = Files.list(root)) {
            for (Path child : children.filter(path -> path.getFileName().toString().startsWith("session-")).toList()) {
                try {
                    CanonicalSnapshot snapshot = inspect(child.getFileName().toString());
                    entries.add(snapshot.indexEntry());
                } catch (RuntimeException failure) {
                    String id = child.getFileName().toString();
                    if (id.matches("session-[A-Za-z0-9-]{1,120}")) {
                        entries.add(new SessionIndexEntry(id, "unknown", id, Instant.EPOCH,
                                SessionLifecycleStatus.UNCERTAIN));
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("无法扫描 canonical sessions", failure);
        }
        index.rebuild(entries);
    }

    /**
     * 返回已验证的 canonical Session store root。
     *
     * @return canonical Session store root
     */
    public Path root() { return root; }
    /**
     * 重建索引后分页列出 Session metadata。
     *
     * @param offset 零基偏移
     * @param limit 最大返回数
     * @return 稳定排序的索引条目
     */
    public List<SessionIndexEntry> list(int offset, int limit) { refreshIndex(); return index.list(offset, limit); }
    /**
     * 重建索引后搜索 Session metadata。
     *
     * @param query 有界 display name 或 Session ID 查询
     * @param limit 最大返回数
     * @return 匹配的索引条目
     */
    public List<SessionIndexEntry> search(String query, int limit) { refreshIndex(); return index.search(query, limit); }

    /**
     * 服务端推导状态后 archive 或二次确认永久删除；writer/migration/recovery 不确定时拒绝。
     *
     * @param sessionId 目标 Session identity
     * @param action 归档或永久删除动作
     * @param firstConfirmation 第一次显式确认
     * @param secondConfirmation 永久删除所需第二次确认
     * @return 固定成功标志与状态码
     */
    public LifecycleResult retain(String sessionId, RetentionAction action,
            boolean firstConfirmation, boolean secondConfirmation) {
        Path directory = sessionDirectory(sessionId);
        Path control = root.resolve("session-control.lock");
        try (FileChannel controlChannel = FileChannel.open(control, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = controlChannel.lock()) {
            verifyDirectory(directory);
            if (migrationPresent(directory)) return new LifecycleResult(false, "MIGRATING");
            Path writerPath = directory.resolve("writer.lock");
            try (FileChannel writerChannel = FileChannel.open(writerPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock writer = tryWriter(writerChannel)) {
                if (writer == null) return new LifecycleResult(false, "ACTIVE");
                CanonicalSnapshot snapshot = inspectLocked(sessionId, false);
                RetentionDecision decision = retention.plan(snapshot.status(), action,
                        firstConfirmation, secondConfirmation);
                if (!decision.allowed()) return new LifecycleResult(false, decision.reason().name());
                // 最终提交前在同一 writer lease 内重新读取 canonical 和 migration fence。
                CanonicalSnapshot finalSnapshot = inspectLocked(sessionId, false);
                if (finalSnapshot.status() != snapshot.status() || migrationPresent(directory)) {
                    return new LifecycleResult(false, "STATE_CHANGED");
                }
                if (action == RetentionAction.ARCHIVE) {
                    Path marker = directory.resolve("archived.v1");
                    Files.writeString(marker, "schema=1\n", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    try (FileChannel markerChannel = FileChannel.open(marker, StandardOpenOption.WRITE)) {
                        markerChannel.force(true);
                    }
                    index.upsert(new SessionIndexEntry(snapshot.sessionId(), snapshot.workspaceIdentity(),
                            snapshot.displayName(), Instant.now(), SessionLifecycleStatus.ARCHIVED));
                    return new LifecycleResult(true, "ARCHIVED");
                }
                // canonical 在 writer lease 内先原子 claim 到 archive 私有 tombstone；不会覆盖既有事实。
                Path tombstone = archiveRoot.resolve(".delete-" + sessionId).normalize();
                if (!archiveRoot.equals(tombstone.getParent())
                        || Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)) {
                    return new LifecycleResult(false, "DELETE_CONFLICT");
                }
                Path journal = directory.resolve("session.jsonl");
                Files.move(journal, tombstone, StandardCopyOption.ATOMIC_MOVE);
                index.remove(sessionId);
            }
            deleteTree(directory);
            Files.deleteIfExists(archiveRoot.resolve(".delete-" + sessionId));
            return new LifecycleResult(true, "DELETED");
        } catch (Exception failure) {
            return new LifecycleResult(false, "RETENTION_FAILED");
        }
    }

    /**
     * 在 Session lifecycle 边界委托一次可恢复迁移。
     *
     * @param source canonical 源文件
     * @param target create-only 目标文件
     * @param fromMajor 源 schema major
     * @param toMajor 目标 schema major
     * @param migrator 单条 canonical record 迁移函数
     * @return 固定迁移终态与记录数
     */
    public SessionMigrationCoordinator.MigrationResult migrate(Path source, Path target, int fromMajor,
            int toMajor, SessionMigrationCoordinator.RecordMigrator migrator) {
        return migrations.migrate(source, target, fromMajor, toMajor, migrator);
    }

    private CanonicalSnapshot inspect(String sessionId) {
        Path directory = sessionDirectory(sessionId);
        try {
            verifyDirectory(directory);
            return inspectLocked(sessionId, writerActive(directory));
        } catch (IOException failure) {
            throw new IllegalArgumentException("session canonical 非法", failure);
        }
    }

    private CanonicalSnapshot inspectLocked(String sessionId, boolean active) {
        Path directory = sessionDirectory(sessionId);
        Path journal = directory.resolve("session.jsonl");
        try {
            if (Files.isSymbolicLink(journal) || !Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(journal) > MAX_CANONICAL_BYTES) throw new IOException();
            List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
            if (lines.isEmpty() || lines.size() > MAX_RECORDS) throw new IOException();
            String workspace = null;
            String display = sessionId;
            long expected = 1;
            boolean runOpen = false;
            boolean incompleteSideEffect = false;
            Map<String, String> startedEffects = new LinkedHashMap<>();
            ArrayList<String> redacted = new ArrayList<>();
            for (String line : lines) {
                JsonNode record = mapper.readTree(line);
                if (record == null || !record.isObject() || record.path("schemaMajor").asInt(-1) != 1
                        || record.path("sequence").asLong(-1) != expected++) throw new IOException();
                String type = record.path("recordType").asText("");
                if (type.isBlank()) throw new IOException();
                if ("session.created".equals(type)) {
                    if (!sessionId.equals(record.path("sessionId").asText()) || workspace != null) throw new IOException();
                    workspace = bounded(record.path("workspaceIdentity").asText(), 128);
                    JsonNode metadata = record.path("metadata");
                    if (metadata.isObject() && metadata.has("model")) display = bounded(metadata.path("model").asText(), 256);
                } else if ("run.started".equals(type)) {
                    if (runOpen) throw new IOException();
                    runOpen = true;
                } else if ("run.completed".equals(type)) {
                    runOpen = false;
                } else if ("tool.started".equals(type)) {
                    startedEffects.put(bounded(record.path("callId").asText(), 200),
                            bounded(record.path("effect").asText("UNKNOWN"), 64));
                } else if ("tool.completed".equals(type) || "tool.resolved".equals(type)) {
                    startedEffects.remove(record.path("callId").asText());
                }
                redacted.add(redactRecord(record));
            }
            if (workspace == null) throw new IOException();
            incompleteSideEffect = startedEffects.values().stream().anyMatch(effect ->
                    !"READ_WORKSPACE".equals(effect));
            SessionLifecycleStatus status;
            if (Files.exists(directory.resolve("archived.v1"), LinkOption.NOFOLLOW_LINKS)) {
                status = SessionLifecycleStatus.ARCHIVED;
            } else if (active) {
                status = SessionLifecycleStatus.ACTIVE;
            } else if (migrationPresent(directory)) {
                status = SessionLifecycleStatus.MIGRATING;
            } else if (incompleteSideEffect) {
                status = SessionLifecycleStatus.INCOMPLETE_SIDE_EFFECT;
            } else if (runOpen || !startedEffects.isEmpty()) {
                status = SessionLifecycleStatus.UNCERTAIN;
            } else {
                status = SessionLifecycleStatus.CLOSED;
            }
            Instant updated = Files.getLastModifiedTime(journal).toInstant();
            return new CanonicalSnapshot(sessionId, workspace, display, updated, status, redacted);
        } catch (Exception failure) {
            throw new IllegalArgumentException("session canonical 非法", failure);
        }
    }

    private String redactRecord(JsonNode source) {
        var output = mapper.createObjectNode();
        output.put("schemaMajor", 1);
        output.put("sequence", source.path("sequence").asLong());
        output.put("recordType", source.path("recordType").asText());
        copyIdentifier(source, output, "sessionId");
        copyIdentifier(source, output, "runId");
        copyIdentifier(source, output, "callId");
        copyIdentifier(source, output, "toolName");
        copyIdentifier(source, output, "effect");
        copyIdentifier(source, output, "stopReason");
        if (source.has("userText")) output.put("userText", redactText(source.path("userText").asText()));
        if (source.has("text")) output.put("text", redactText(source.path("text").asText()));
        return mapper.writeValueAsString(output);
    }

    private static void copyIdentifier(JsonNode source, tools.jackson.databind.node.ObjectNode output, String field) {
        if (source.has(field) && source.path(field).isTextual()) {
            String value = source.path(field).asText();
            if (value.length() <= 256 && value.chars().noneMatch(Character::isISOControl)) output.put(field, value);
        }
    }

    private static String redactText(String value) {
        String redacted = value.replaceAll("(?i)(api[_-]?key|token|password|secret)\\s*[:=]\\s*\\S+", "$1=[REDACTED]");
        if (redacted.length() > MAX_EXPORT_TEXT) redacted = redacted.substring(0, MAX_EXPORT_TEXT);
        return redacted;
    }

    private Path sessionDirectory(String sessionId) {
        if (sessionId == null || !sessionId.matches("session-[A-Za-z0-9-]{1,120}"))
            throw new IllegalArgumentException("sessionId 非法");
        Path value = root.resolve(sessionId).normalize();
        if (!root.equals(value.getParent())) throw new IllegalArgumentException("session 越界");
        return value;
    }

    private static String bounded(String value, int max) throws IOException {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) throw new IOException();
        return value;
    }

    private static void verifyDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(directory)) throw new IOException();
    }

    private static FileLock tryWriter(FileChannel channel) throws IOException {
        try { return channel.tryLock(); }
        catch (OverlappingFileLockException active) { return null; }
    }

    private static boolean writerActive(Path directory) throws IOException {
        Path lockPath = directory.resolve("writer.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException();
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = tryWriter(channel)) { return lock == null; }
    }

    private static boolean migrationPresent(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            return children.anyMatch(path -> path.getFileName().toString().contains(".migration."));
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException();
                Files.delete(path);
            }
        }
    }

    private record CanonicalSnapshot(String sessionId, String workspaceIdentity, String displayName,
            Instant updatedAt, SessionLifecycleStatus status, List<String> redactedRecords) {
        CanonicalSnapshot { redactedRecords = List.copyOf(redactedRecords); }
        SessionIndexEntry indexEntry() {
            return new SessionIndexEntry(sessionId, workspaceIdentity, displayName, updatedAt, status);
        }
    }

    /**
     * 不含路径或正文的 Session lifecycle 操作结果。
     *
     * @param success 请求动作是否在全部 fence 内完成
     * @param status 固定终态码
     */
    public record LifecycleResult(boolean success, String status) { }
}
