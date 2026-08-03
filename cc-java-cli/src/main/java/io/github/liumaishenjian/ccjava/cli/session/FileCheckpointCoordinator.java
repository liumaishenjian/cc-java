package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.core.CheckpointCoordinator;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.CheckpointDiff;
import io.github.liumaishenjian.ccjava.domain.CheckpointId;
import io.github.liumaishenjian.ccjava.domain.CheckpointPhase;
import io.github.liumaishenjian.ccjava.domain.CheckpointSummary;
import io.github.liumaishenjian.ccjava.domain.CheckpointTarget;
import io.github.liumaishenjian.ccjava.domain.CheckpointUndoResult;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 使用 Session 私有普通文件保存 write-ahead pre-image 与 compare-before-restore Undo 状态。
 *
 * <p>Coordinator 重用 {@link WorkspaceGuard} 解析相对目标，不跟随 Symlink/Junction，不调用 Git，
 * 也不会恢复 Shell、进程、网络或远端副作用。每个 Checkpoint 使用独立目录；本地 metadata 只承担
 * crash phase 与文件校验，Session JSONL 才是 created/completed/undo completed 的规范语义记录。</p>
 *
 * <p>Metadata 与 JSONL 之间不能原子提交，因此实现显式保留 {@code *_PREPARED} 与
 * {@code *_JOURNAL_UNCERTAIN} 阶段。任何 journal 调用抛错后都保留 durable pre-image 并 Fail Closed；
 * 重启不会猜测记录是否提交，也绝不自动重放 Tool 或 Undo。</p>
 *
 * @since 0.6.0
 */
public final class FileCheckpointCoordinator implements CheckpointCoordinator {

    private static final int SCHEMA_MAJOR = 1;
    private static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 16 * 1024;
    private static final int MAX_DIFF_CHARS = 16 * 1024;
    private static final int MAX_CHECKPOINTS = 1_000;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final Path sessionRoot;
    private final WorkspaceGuard guard;
    private final FileSessionStore journal;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private final AtomicLong ids = new AtomicLong();
    private final Map<SessionId, LinkedHashMap<CheckpointId, State>> states =
            new LinkedHashMap<>();
    private final FaultInjector faults;

    /**
     * 创建绑定 Session Store、WorkspaceGuard 和规范 journal 的 Checkpoint Adapter。
     *
     * @param sessionRoot 与 Session journal 相同的私有 Store root
     * @param guard Workspace 文件安全边界
     * @param journal Checkpoint 语义记录入口
     */
    public FileCheckpointCoordinator(
            Path sessionRoot,
            WorkspaceGuard guard,
            FileSessionStore journal) {
        this(sessionRoot, guard, journal, FaultInjector.none());
    }

    FileCheckpointCoordinator(
            Path sessionRoot,
            WorkspaceGuard guard,
            FileSessionStore journal,
            FaultInjector faults) {
        this.sessionRoot = Objects.requireNonNull(sessionRoot, "sessionRoot 不能为空")
                .toAbsolutePath().normalize();
        this.guard = Objects.requireNonNull(guard, "guard 不能为空");
        this.journal = Objects.requireNonNull(journal, "journal 不能为空");
        this.faults = Objects.requireNonNull(faults, "faults 不能为空");
    }

    @Override
    public synchronized CheckpointId create(
            ToolInvocation invocation,
            CheckpointTarget target) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        Objects.requireNonNull(target, "target 不能为空");
        LinkedHashMap<CheckpointId, State> sessionStates = load(invocation.sessionId());
        if (sessionStates.size() >= MAX_CHECKPOINTS) {
            throw failure("CHECKPOINT_LIMIT", "Session Checkpoint 数量达到上限");
        }
        CheckpointId id = nextId(invocation);
        Path directory = checkpointDirectory(invocation.sessionId(), id);
        boolean durableMaterialExists = false;
        try {
            ValidatedWorkspacePath validated = target.existedBefore()
                    ? guard.requireRegularFile(target.protocolPath())
                    : guard.requireNewFile(target.protocolPath());
            if (!validated.protocolPath().equals(target.protocolPath())) {
                throw failure("CHECKPOINT_TARGET", "Checkpoint 目标路径不一致");
            }
            byte[] preImage = target.existedBefore()
                    ? readBounded(validated.realPath())
                    : null;
            createCheckpointDirectory(directory);
            if (preImage != null) {
                writeDurableNew(directory.resolve("pre-image.bin"), preImage);
            }
            State prepared = new State(
                    id,
                    invocation.call().id(),
                    invocation.call().name(),
                    target.protocolPath(),
                    target.existedBefore(),
                    preImage == null ? Optional.empty() : Optional.of(digest(preImage)),
                    new PostState.Unknown(),
                    CheckpointPhase.CREATE_PREPARED);
            writeMetadata(invocation.sessionId(), prepared);
            durableMaterialExists = true;
            try {
                faults.beforeCheckpointCreatedJournal();
                journal.checkpointCreated(
                        invocation.sessionId(),
                        invocation.runId(),
                        invocation.ordinal(),
                        prepared.summary(),
                        prepared.preDigest().orElse("ABSENT"));
            } catch (RuntimeException uncertain) {
                State journalUncertain = prepared.withPhase(CheckpointPhase.CREATE_JOURNAL_UNCERTAIN);
                persistUncertainBestEffort(invocation.sessionId(), journalUncertain, uncertain);
                sessionStates.put(id, journalUncertain);
                throw failure(
                        "CHECKPOINT_JOURNAL_UNCERTAIN",
                        "Checkpoint created 记录结果不确定，已保留 durable pre-image");
            }
            State committed = prepared.withPhase(CheckpointPhase.CREATED);
            writeMetadata(invocation.sessionId(), committed);
            sessionStates.put(id, committed);
            return id;
        } catch (WorkspaceAccessException exception) {
            deleteKnownFilesQuietly(directory, durableMaterialExists);
            throw failure("CHECKPOINT_TARGET", "Checkpoint 目标不满足 Workspace 安全契约");
        } catch (SessionOpenException known) {
            deleteKnownFilesQuietly(directory, durableMaterialExists);
            throw known;
        } catch (IOException io) {
            deleteKnownFilesQuietly(directory, durableMaterialExists);
            throw failure("CHECKPOINT_IO", "Checkpoint pre-image 未可靠持久化");
        }
    }

    @Override
    public synchronized void complete(
            ToolInvocation invocation,
            CheckpointId checkpointId,
            ToolResult result) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        Objects.requireNonNull(checkpointId, "checkpointId 不能为空");
        Objects.requireNonNull(result, "result 不能为空");
        State current = requireUsableState(invocation.sessionId(), checkpointId, CheckpointPhase.CREATED);
        PostState postState = currentPostState(current);
        if (result.status() == ToolResultStatus.SUCCESS && postState instanceof PostState.Absent) {
            throw failure("POST_IMAGE_UNKNOWN", "成功写 Tool 缺少可验证 post-image");
        }
        State prepared = current.withPostState(postState).withPhase(CheckpointPhase.POST_PREPARED);
        writeMetadata(invocation.sessionId(), prepared);
        try {
            faults.beforeCheckpointCompletedJournal();
            journal.checkpointCompleted(
                    invocation.sessionId(),
                    invocation.runId(),
                    invocation.ordinal(),
                    checkpointId,
                    postState.digest(),
                    postState instanceof PostState.Absent);
        } catch (RuntimeException uncertain) {
            State journalUncertain = prepared.withPhase(CheckpointPhase.POST_JOURNAL_UNCERTAIN);
            persistUncertainBestEffort(invocation.sessionId(), journalUncertain, uncertain);
            load(invocation.sessionId()).put(checkpointId, journalUncertain);
            throw failure(
                    "CHECKPOINT_JOURNAL_UNCERTAIN",
                    "Checkpoint completed 记录结果不确定，禁止继续执行");
        }
        State completed = prepared.withPhase(postState instanceof PostState.Absent
                ? CheckpointPhase.COMPLETED_ABSENT
                : CheckpointPhase.COMPLETED_PRESENT);
        writeMetadata(invocation.sessionId(), completed);
        load(invocation.sessionId()).put(checkpointId, completed);
    }

    @Override
    public synchronized List<CheckpointSummary> list(SessionId sessionId) {
        return load(sessionId).values().stream().map(State::summary).toList();
    }

    /**
     * 返回会阻止 Resume/新 Run 的本地 Checkpoint 恢复问题。
     *
     * <p>JSONL 无法表达 metadata 已准备但 journal 结果未知的窗口，因此 Store 在决定可写
     * Resume 前必须合并该投影。这里只返回固定类型，不暴露路径、digest 或文件内容。</p>
     *
     * @param sessionId 目标 Session
     * @return 当前需要人工检查的固定问题
     */
    public synchronized List<io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue>
            recoveryIssues(SessionId sessionId) {
        boolean undoUncertain = load(sessionId).values().stream()
                .map(State::phase)
                .anyMatch(phase -> phase == CheckpointPhase.UNDO_PREPARED
                        || phase == CheckpointPhase.UNDO_APPLIED
                        || phase == CheckpointPhase.UNDO_JOURNAL_UNCERTAIN);
        return undoUncertain
                ? List.of(io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue.session(
                        io.github.liumaishenjian.ccjava.core.SessionRecoveryIssueKind
                                .CHECKPOINT_UNDO_UNCERTAIN))
                : List.of();
    }

    @Override
    public synchronized CheckpointDiff diff(
            SessionId sessionId,
            CheckpointId checkpointId) {
        State state = requireState(sessionId, checkpointId);
        requireReadablePhase(state);
        Optional<byte[]> current = currentBytes(state);
        if (!state.existedBefore() && current.isEmpty()) {
            return new CheckpointDiff(
                    checkpointId,
                    state.target(),
                    CheckpointDiff.Status.ABSENT,
                    "目标仍不存在",
                    false);
        }
        if (state.existedBefore() && current.isPresent()) {
            byte[] before = readPreImage(sessionId, state);
            byte[] now = current.orElseThrow();
            if (MessageDigest.isEqual(before, now)) {
                return new CheckpointDiff(
                        checkpointId,
                        state.target(),
                        CheckpointDiff.Status.UNCHANGED,
                        "当前内容等于 Checkpoint pre-image",
                        false);
            }
            return renderDiff(state, before, now);
        }
        if (!state.existedBefore() && current.isPresent()) {
            return renderDiff(state, new byte[0], current.orElseThrow());
        }
        return conflictDiff(state, "当前目标不存在");
    }

    @Override
    public synchronized CheckpointUndoResult undo(
            SessionId sessionId,
            CheckpointId checkpointId,
            boolean explicitlyConfirmed) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(checkpointId, "checkpointId 不能为空");
        if (!explicitlyConfirmed) {
            throw failure("UNDO_CONFIRMATION_REQUIRED", "Undo 必须经过独立显式确认");
        }
        journal.requireUndoAllowed(sessionId);
        State state = requireState(sessionId, checkpointId);
        if (state.phase() == CheckpointPhase.UNDONE) {
            return new CheckpointUndoResult(
                    checkpointId,
                    CheckpointUndoResult.Status.ALREADY_RESTORED,
                    state.target(),
                    "Checkpoint 已恢复，本次未重复写入");
        }
        requireUndoablePhase(state);

        Optional<byte[]> current = currentBytes(state);
        if (state.postState() instanceof PostState.Absent) {
            if (current.isPresent()) {
                return conflict(state, "Checkpoint 完成时目标已不存在，但当前目标已出现");
            }
            if (state.existedBefore()) {
                return conflict(state, "原文件已缺失且没有可比较 post-image，拒绝恢复");
            }
            State applied = state.withPhase(CheckpointPhase.UNDO_APPLIED);
            writeMetadata(sessionId, applied);
            return commitUndoRecord(sessionId, applied, "新文件仍不存在");
        }
        if (current.isEmpty()) {
            return conflict(state, "当前目标不存在");
        }
        if (!digest(current.orElseThrow()).equals(state.postState().digest().orElseThrow())) {
            return conflict(state, "当前内容已改变，拒绝覆盖用户修改");
        }

        byte[] preImage = state.existedBefore() ? readPreImage(sessionId, state) : null;
        State prepared = state.withPhase(CheckpointPhase.UNDO_PREPARED);
        writeMetadata(sessionId, prepared);
        faults.afterUndoPrepared();
        try {
            applyUndoWithFinalRecheck(prepared, preImage);
        } catch (RuntimeException failure) {
            load(sessionId).put(checkpointId, prepared);
            throw failure;
        }
        State applied = prepared.withPhase(CheckpointPhase.UNDO_APPLIED);
        writeMetadata(sessionId, applied);
        return commitUndoRecord(sessionId, applied, "Checkpoint 已显式恢复");
    }

    private CheckpointUndoResult commitUndoRecord(
            SessionId sessionId,
            State applied,
            String message) {
        try {
            faults.beforeUndoCompletedJournal();
            journal.checkpointUndoCompleted(sessionId, applied.id());
        } catch (RuntimeException uncertain) {
            State journalUncertain = applied.withPhase(CheckpointPhase.UNDO_JOURNAL_UNCERTAIN);
            persistUncertainBestEffort(sessionId, journalUncertain, uncertain);
            load(sessionId).put(applied.id(), journalUncertain);
            throw failure(
                    "CHECKPOINT_JOURNAL_UNCERTAIN",
                    "Undo 已应用但完成记录结果不确定，禁止自动重试");
        }
        State undone = applied.withPhase(CheckpointPhase.UNDONE);
        writeMetadata(sessionId, undone);
        load(sessionId).put(applied.id(), undone);
        return new CheckpointUndoResult(
                applied.id(),
                CheckpointUndoResult.Status.RESTORED,
                applied.target(),
                message);
    }

    private void applyUndoWithFinalRecheck(State state, byte[] preImage) {
        try {
            if (state.existedBefore()) {
                ValidatedWorkspacePath validated = requireMatchingRegularFile(state);
                Path target = validated.realPath();
                Path staged = createDurableStagedFile(
                        target.getParent(), ".checkpoint-undo-", preImage);
                try {
                    faults.afterUndoStaged();
                    ValidatedWorkspacePath finalValidated = requireMatchingRegularFile(state);
                    if (!finalValidated.realPath().equals(target)
                            || !digest(readBounded(finalValidated.realPath()))
                                    .equals(state.postState().digest().orElseThrow())) {
                        throw failure("CHECKPOINT_CONFLICT", "最终恢复检查发现目标已改变");
                    }
                    moveReplacing(staged, finalValidated.realPath());
                    staged = null;
                } finally {
                    if (staged != null) {
                        Files.deleteIfExists(staged);
                    }
                }
            } else {
                ValidatedWorkspacePath initial = requireMatchingRegularFile(state);
                faults.afterUndoStaged();
                ValidatedWorkspacePath finalValidated = requireMatchingRegularFile(state);
                if (!finalValidated.realPath().equals(initial.realPath())
                        || !digest(readBounded(finalValidated.realPath()))
                                .equals(state.postState().digest().orElseThrow())) {
                    throw failure("CHECKPOINT_CONFLICT", "最终删除检查发现目标已改变");
                }
                Files.delete(finalValidated.realPath());
            }
        } catch (WorkspaceAccessException exception) {
            throw failure("CHECKPOINT_CONFLICT", "当前目标不满足安全恢复契约");
        } catch (IOException exception) {
            throw failure("CHECKPOINT_IO", "无法可靠应用 Checkpoint Undo");
        }
    }

    private ValidatedWorkspacePath requireMatchingRegularFile(State state)
            throws WorkspaceAccessException {
        ValidatedWorkspacePath validated = guard.requireRegularFile(state.target());
        if (!validated.protocolPath().equals(state.target())) {
            throw failure("CHECKPOINT_CONFLICT", "恢复目标路径不一致");
        }
        if (!digest(readBounded(validated.realPath())).equals(state.postState().digest().orElseThrow())) {
            throw failure("CHECKPOINT_CONFLICT", "当前内容已改变，拒绝覆盖用户修改");
        }
        return validated;
    }

    private CheckpointDiff renderDiff(State state, byte[] before, byte[] current) {
        String left = decodeText(before);
        String right = decodeText(current);
        String value = "--- checkpoint/" + state.target() + "\n"
                + "+++ workspace/" + state.target() + "\n"
                + "-" + escapeLines(left) + "\n"
                + "+" + escapeLines(right);
        boolean truncated = value.length() > MAX_DIFF_CHARS;
        if (truncated) {
            value = value.substring(0, MAX_DIFF_CHARS - 20) + "\n[diff truncated]";
        }
        return new CheckpointDiff(
                state.id(), state.target(), CheckpointDiff.Status.CHANGED, value, truncated);
    }

    private CheckpointDiff conflictDiff(State state, String message) {
        return new CheckpointDiff(
                state.id(), state.target(), CheckpointDiff.Status.CONFLICT, message, false);
    }

    private CheckpointUndoResult conflict(State state, String message) {
        return new CheckpointUndoResult(
                state.id(), CheckpointUndoResult.Status.CONFLICT, state.target(), message);
    }

    private Optional<byte[]> currentBytes(State state) {
        try {
            try {
                return Optional.of(readBounded(guard.requireRegularFile(state.target()).realPath()));
            } catch (WorkspaceAccessException missingOrInvalid) {
                guard.requireNewFile(state.target());
                return Optional.empty();
            }
        } catch (WorkspaceAccessException failure) {
            throw failure("CHECKPOINT_CONFLICT", "当前目标不满足普通文件安全契约");
        }
    }

    private PostState currentPostState(State state) {
        return currentBytes(state)
                .<PostState>map(bytes -> new PostState.Present(digest(bytes)))
                .orElseGet(PostState.Absent::new);
    }

    private byte[] readPreImage(SessionId sessionId, State state) {
        if (!state.existedBefore() || state.preDigest().isEmpty()) {
            throw failure("CHECKPOINT_CORRUPT", "Checkpoint 没有 pre-image");
        }
        Path path = checkpointDirectory(sessionId, state.id()).resolve("pre-image.bin");
        rejectLink(path);
        byte[] bytes = readBounded(path);
        if (!digest(bytes).equals(state.preDigest().orElseThrow())) {
            throw failure("CHECKPOINT_CORRUPT", "Checkpoint pre-image digest 不匹配");
        }
        return bytes;
    }

    private LinkedHashMap<CheckpointId, State> load(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        LinkedHashMap<CheckpointId, State> cached = states.get(sessionId);
        if (cached != null) {
            return cached;
        }
        LinkedHashMap<CheckpointId, State> loaded = new LinkedHashMap<>();
        Path root = checkpointsRoot(sessionId);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            rejectLink(root);
            List<Path> directories = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path directory : stream) {
                    if (directories.size() >= MAX_CHECKPOINTS) {
                        throw failure("CHECKPOINT_LIMIT", "Session Checkpoint 枚举超过上限");
                    }
                    directories.add(directory);
                }
            } catch (SessionOpenException known) {
                throw known;
            } catch (IOException failure) {
                throw failure("CHECKPOINT_IO", "无法读取 Checkpoint Store");
            }
            directories.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path directory : directories) {
                rejectLink(directory);
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure("CHECKPOINT_CORRUPT", "Checkpoint Store 包含非法条目");
                }
                CheckpointId directoryId = parseDirectoryId(directory);
                State state = readMetadata(directory.resolve("metadata.json"));
                if (!state.id().equals(directoryId)) {
                    throw failure("CHECKPOINT_CORRUPT", "Checkpoint metadata ID 与目录名不一致");
                }
                if (loaded.putIfAbsent(state.id(), state) != null) {
                    throw failure("CHECKPOINT_CORRUPT", "Checkpoint ID 重复");
                }
            }
        }
        states.put(sessionId, loaded);
        return loaded;
    }

    private State requireState(SessionId sessionId, CheckpointId checkpointId) {
        State state = load(sessionId).get(Objects.requireNonNull(checkpointId, "checkpointId 不能为空"));
        if (state == null) {
            throw new IllegalArgumentException("Checkpoint 不存在");
        }
        return state;
    }

    private State requireUsableState(
            SessionId sessionId,
            CheckpointId checkpointId,
            CheckpointPhase expected) {
        State state = requireState(sessionId, checkpointId);
        if (state.phase() != expected) {
            throw failure("CHECKPOINT_STATE", "Checkpoint 阶段不允许该操作: " + state.phase().name());
        }
        return state;
    }

    private static void requireReadablePhase(State state) {
        switch (state.phase()) {
            case CREATED,
                    POST_PREPARED,
                    COMPLETED_PRESENT,
                    COMPLETED_ABSENT,
                    UNDO_PREPARED,
                    UNDONE -> {
            }
            case CREATE_PREPARED,
                    CREATE_JOURNAL_UNCERTAIN,
                    POST_JOURNAL_UNCERTAIN,
                    UNDO_APPLIED,
                    UNDO_JOURNAL_UNCERTAIN -> throw failure(
                            "CHECKPOINT_STATE_UNCERTAIN",
                            "Checkpoint durable 状态不确定，只能人工检查");
        }
    }

    private static void requireUndoablePhase(State state) {
        if (state.phase() != CheckpointPhase.COMPLETED_PRESENT
                && state.phase() != CheckpointPhase.COMPLETED_ABSENT) {
            throw failure(
                    "CHECKPOINT_STATE_UNCERTAIN",
                    "只有 clean COMPLETED Checkpoint 可以 Undo: " + state.phase().name());
        }
    }

    private State readMetadata(Path path) {
        rejectLink(path);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > MAX_METADATA_BYTES) {
                throw failure("CHECKPOINT_CORRUPT", "Checkpoint metadata 无效");
            }
            JsonNode node = mapper.readTree(Files.readAllBytes(path));
            if (node == null || !node.isObject()) {
                throw failure("CHECKPOINT_CORRUPT", "Checkpoint metadata 不是 Object");
            }
            ObjectNode root = (ObjectNode) node;
            if (integer(root, "schemaMajor") != SCHEMA_MAJOR) {
                throw failure("CHECKPOINT_VERSION", "Checkpoint Schema 版本不受支持");
            }
            Optional<String> preDigest = optionalDigest(root, "preDigest");
            Optional<String> postDigest = optionalDigest(root, "postDigest");
            boolean postAbsent = optionalBoolean(root, "postAbsent").orElse(false);
            if (postDigest.isPresent() && postAbsent) {
                throw failure("CHECKPOINT_CORRUPT", "Checkpoint post-state 不能同时是 digest 和 ABSENT");
            }
            PostState postState = postDigest
                    .<PostState>map(PostState.Present::new)
                    .orElseGet(() -> postAbsent ? new PostState.Absent() : new PostState.Unknown());
            State state = new State(
                    new CheckpointId(text(root, "id", 128)),
                    text(root, "callId", 200),
                    text(root, "toolName", 200),
                    text(root, "target", 1_024),
                    bool(root, "existedBefore"),
                    preDigest,
                    postState,
                    phase(root));
            if (state.existedBefore() != state.preDigest().isPresent()) {
                throw failure("CHECKPOINT_CORRUPT", "Checkpoint pre-image 元数据不一致");
            }
            boolean createPhase = state.phase() == CheckpointPhase.CREATE_PREPARED
                    || state.phase() == CheckpointPhase.CREATE_JOURNAL_UNCERTAIN
                    || state.phase() == CheckpointPhase.CREATED;
            if (createPhase != (state.postState() instanceof PostState.Unknown)) {
                throw failure("CHECKPOINT_CORRUPT", "Checkpoint phase 与 post-state 不一致");
            }
            if (state.phase() == CheckpointPhase.COMPLETED_PRESENT
                    && !(state.postState() instanceof PostState.Present)) {
                throw failure("CHECKPOINT_CORRUPT", "COMPLETED_PRESENT 缺少 post digest");
            }
            if (state.phase() == CheckpointPhase.COMPLETED_ABSENT
                    && !(state.postState() instanceof PostState.Absent)) {
                throw failure("CHECKPOINT_CORRUPT", "COMPLETED_ABSENT 缺少 ABSENT post-state");
            }
            return state;
        } catch (SessionOpenException known) {
            throw known;
        } catch (Exception failure) {
            throw failure("CHECKPOINT_CORRUPT", "Checkpoint metadata 无法解析");
        }
    }

    private void writeMetadata(SessionId sessionId, State state) {
        Path directory = checkpointDirectory(sessionId, state.id());
        try {
            rejectLink(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("CHECKPOINT_CORRUPT", "Checkpoint 目录不存在或类型无效");
            }
            ObjectNode root = mapper.createObjectNode();
            root.put("schemaMajor", SCHEMA_MAJOR);
            root.put("id", state.id().value());
            root.put("callId", state.callId());
            root.put("toolName", state.toolName());
            root.put("target", state.target());
            root.put("existedBefore", state.existedBefore());
            state.preDigest().ifPresent(value -> root.put("preDigest", value));
            state.postState().digest().ifPresent(value -> root.put("postDigest", value));
            if (state.postState() instanceof PostState.Absent) {
                root.put("postAbsent", true);
            }
            root.put("phase", state.phase().name());
            writeAtomic(directory.resolve("metadata.json"), mapper.writeValueAsBytes(root));
        } catch (SessionOpenException known) {
            throw known;
        } catch (IOException failure) {
            throw failure("CHECKPOINT_IO", "Checkpoint metadata 未可靠持久化");
        }
    }

    private void persistUncertainBestEffort(
            SessionId sessionId,
            State uncertain,
            RuntimeException original) {
        try {
            writeMetadata(sessionId, uncertain);
        } catch (RuntimeException metadataFailure) {
            original.addSuppressed(metadataFailure);
        }
    }

    private void createCheckpointDirectory(Path directory) throws IOException {
        Path root = directory.getParent();
        Files.createDirectories(root);
        rejectLink(root);
        Files.createDirectory(directory);
        rejectLink(directory);
    }

    private CheckpointId nextId(ToolInvocation invocation) {
        String run = invocation.runId().value().replaceAll("[^A-Za-z0-9-]", "-");
        return new CheckpointId(
                "checkpoint-" + run + "-" + invocation.ordinal() + "-" + ids.incrementAndGet());
    }

    private CheckpointId parseDirectoryId(Path directory) {
        try {
            return new CheckpointId(directory.getFileName().toString());
        } catch (IllegalArgumentException invalid) {
            throw failure("CHECKPOINT_CORRUPT", "Checkpoint 目录名格式无效");
        }
    }

    private Path checkpointsRoot(SessionId sessionId) {
        return sessionDirectory(sessionId).resolve("checkpoints");
    }

    private Path checkpointDirectory(SessionId sessionId, CheckpointId id) {
        Path root = checkpointsRoot(sessionId);
        Path directory = root.resolve(id.value()).normalize();
        if (!directory.getParent().equals(root)) {
            throw failure("CHECKPOINT_ID", "Checkpoint ID 不能形成路径");
        }
        return directory;
    }

    private Path sessionDirectory(SessionId sessionId) {
        Path directory = sessionRoot.resolve(sessionId.value()).normalize();
        if (!directory.getParent().equals(sessionRoot)) {
            throw failure("SESSION_ID", "Session ID 不能形成路径");
        }
        return directory;
    }

    private static byte[] readBounded(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)
                    || Files.size(path) > MAX_IMAGE_BYTES) {
                throw failure("CHECKPOINT_FILE", "Checkpoint 仅支持有界普通文件");
            }
            return Files.readAllBytes(path);
        } catch (SessionOpenException known) {
            throw known;
        } catch (IOException failure) {
            throw failure("CHECKPOINT_IO", "无法读取 Checkpoint 文件");
        }
    }

    private static void writeDurableNew(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            writeAndForce(channel, bytes);
        }
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path staged = createDurableStagedFile(target.getParent(), ".checkpoint-", bytes);
        try {
            moveReplacing(staged, target);
            staged = null;
        } finally {
            if (staged != null) {
                Files.deleteIfExists(staged);
            }
        }
    }

    private static Path createDurableStagedFile(
            Path parent,
            String prefix,
            byte[] bytes) throws IOException {
        Path staged = Files.createTempFile(parent, prefix, ".tmp");
        boolean success = false;
        try (FileChannel channel = FileChannel.open(
                staged,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writeAndForce(channel, bytes);
            success = true;
            return staged;
        } finally {
            if (!success) {
                Files.deleteIfExists(staged);
            }
        }
    }

    private static void writeAndForce(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        channel.force(true);
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void rejectLink(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            throw failure("CHECKPOINT_LINK", "Checkpoint Store 不接受链接");
        }
        try {
            if (!path.toRealPath().equals(path.toAbsolutePath().normalize())) {
                throw failure("CHECKPOINT_LINK", "Checkpoint Store 不接受 Junction 或重解析路径");
            }
        } catch (IOException failure) {
            throw failure("CHECKPOINT_IO", "无法验证 Checkpoint Store 路径");
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static String decodeText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw failure("CHECKPOINT_ENCODING", "Checkpoint Diff 仅支持严格 UTF-8 文本");
        }
    }

    private static String escapeLines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n ");
    }

    private static int integer(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw failure("CHECKPOINT_CORRUPT", field + " 必须是整数");
        }
        return value.intValue();
    }

    private static boolean bool(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw failure("CHECKPOINT_CORRUPT", field + " 必须是布尔值");
        }
        return value.booleanValue();
    }

    private static String text(ObjectNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null
                || !value.isString()
                || value.stringValue().isBlank()
                || value.stringValue().length() > max
                || value.stringValue().chars().anyMatch(ch -> ch == 0)) {
            throw failure("CHECKPOINT_CORRUPT", field + " 为空、包含 NUL 或超过上限");
        }
        return value.stringValue();
    }

    private static Optional<String> optionalDigest(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        String digest = text(node, field, 64);
        if (!SHA_256.matcher(digest).matches()) {
            throw failure("CHECKPOINT_CORRUPT", field + " 必须是小写 SHA-256 hex");
        }
        return Optional.of(digest);
    }

    private static Optional<Boolean> optionalBoolean(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isBoolean()) {
            throw failure("CHECKPOINT_CORRUPT", field + " 必须是布尔值");
        }
        return Optional.of(value.booleanValue());
    }

    private static CheckpointPhase phase(ObjectNode node) {
        String value = text(node, "phase", 64);
        try {
            return CheckpointPhase.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw failure("CHECKPOINT_CORRUPT", "Checkpoint phase 无效");
        }
    }

    private static SessionOpenException failure(String code, String message) {
        return new SessionOpenException(code, message);
    }

    private static void deleteKnownFilesQuietly(Path directory, boolean preserveDurableMaterial) {
        if (preserveDurableMaterial
                || directory == null
                || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            deleteKnownRegularFile(directory.resolve("metadata.json"));
            deleteKnownRegularFile(directory.resolve("pre-image.bin"));
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                if (entries.iterator().hasNext()) {
                    return;
                }
            }
            Files.deleteIfExists(directory);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void deleteKnownRegularFile(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.deleteIfExists(path);
    }

    interface FaultInjector {

        default void beforeCheckpointCreatedJournal() {
        }

        default void beforeCheckpointCompletedJournal() {
        }

        default void afterUndoPrepared() {
        }

        default void afterUndoStaged() {
        }

        default void beforeUndoCompletedJournal() {
        }

        static FaultInjector none() {
            return new FaultInjector() {
            };
        }
    }

    private sealed interface PostState {

        Optional<String> digest();

        record Unknown() implements PostState {
            @Override
            public Optional<String> digest() {
                return Optional.empty();
            }
        }

        record Present(String value) implements PostState {
            public Present {
                Objects.requireNonNull(value);
                if (!SHA_256.matcher(value).matches()) {
                    throw failure("CHECKPOINT_CORRUPT", "post digest 必须是小写 SHA-256 hex");
                }
            }

            @Override
            public Optional<String> digest() {
                return Optional.of(value);
            }
        }

        record Absent() implements PostState {
            @Override
            public Optional<String> digest() {
                return Optional.empty();
            }
        }
    }

    private record State(
            CheckpointId id,
            String callId,
            String toolName,
            String target,
            boolean existedBefore,
            Optional<String> preDigest,
            PostState postState,
            CheckpointPhase phase) {

        private State {
            Objects.requireNonNull(id);
            Objects.requireNonNull(callId);
            Objects.requireNonNull(toolName);
            Objects.requireNonNull(target);
            preDigest = Objects.requireNonNull(preDigest);
            postState = Objects.requireNonNull(postState);
            phase = Objects.requireNonNull(phase);
        }

        private CheckpointSummary summary() {
            return new CheckpointSummary(
                    id,
                    callId,
                    toolName,
                    target,
                    existedBefore,
                    phase);
        }

        private State withPostState(PostState next) {
            return new State(
                    id,
                    callId,
                    toolName,
                    target,
                    existedBefore,
                    preDigest,
                    next,
                    phase);
        }

        private State withPhase(CheckpointPhase next) {
            return new State(
                    id,
                    callId,
                    toolName,
                    target,
                    existedBefore,
                    preDigest,
                    postState,
                    next);
        }
    }
}
