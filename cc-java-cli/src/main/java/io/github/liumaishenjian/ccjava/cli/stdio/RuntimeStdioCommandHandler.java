package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ModelTurnTelemetry;
import io.github.liumaishenjian.ccjava.core.RunTelemetry;
import io.github.liumaishenjian.ccjava.core.ToolCallTelemetry;
import io.github.liumaishenjian.ccjava.core.ContextPreparationConfig;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.cli.runtime.DoctorReportService;
import io.github.liumaishenjian.ccjava.cli.runtime.SessionCommandDispatcher;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.command.CommandId;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandIntent;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandResult;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.cli.session.SessionStorage;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/**
 * 把 stdio v0 命令适配到真实 {@link HeadlessRuntimeSession}。
 *
 * <p>该类型只管理单连接的 Session/Run 状态和事件映射。模型循环、规范消息历史、
 * Tool Pipeline、取消与终态仍由 Core 拥有；S03 只把 Core Lifecycle 投影为不含参数、正文、
 * 绝对路径和原始异常的 Tool 进度事件。</p>
 *
 * @since 0.1.0
 */
public final class RuntimeStdioCommandHandler
        implements StdioProtocol.CommandHandler, AgentEventSink {

    /** 当前连接允许保留的未发送 steering 数量。 */
    static final int MAX_STEERING_MESSAGES = 100;
    static final int MAX_EXPANDED_INPUT_BYTES = 1_048_576;
    static final int MAX_INPUT_CHUNKS = 64;
    static final Duration INPUT_ASSEMBLY_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_INPUT_TOMBSTONES = 256;
    /** {@code file.suggestions} 单条事件 payload 的 UTF-8 预算。 */
    static final int MAX_SUGGESTION_EVENT_BYTES = 8_192;

    private final Object lock = new Object();
    private final StdioProtocolCodec codec = new StdioProtocolCodec();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("cc-java-runtime-run").daemon(true).factory());
    private final InputAssemblyScheduler assemblyScheduler;
    private final Clock clock;
    private final StdioApprovalCoordinator approvals;
    private final HeadlessRuntimeSession application;
    private final Deque<QueuedSteering> steeringQueue = new ArrayDeque<>();
    private State state = State.NEW;
    private ActiveRun activeRun;
    private SessionCommandDispatcher commandDispatcher;
    /** 仅记录 dispatcher 已接受的有限 commandId，拒绝预算外新 ID 后立即关闭连接。 */
    private final Set<CommandId> emittedCommandResults = new HashSet<>();
    private boolean commandRequestBudgetExhausted;
    private final LinkedHashMap<String, InputTerminal> inputTombstones = new LinkedHashMap<>();
    private InputAssembly inputAssembly;
    private ExpiryHandle inputExpiry;
    private io.github.liumaishenjian.ccjava.cli.mentions.FileMentionService fileMentions;
    private io.github.liumaishenjian.ccjava.cli.mentions.FileSuggestionService fileSuggestions;

    /**
     * 使用已校验的本地 Provider 设置装配 Headless Runtime。
     *
     * @param settings 不得记录或持久化的 Provider 设置
     */
    public RuntimeStdioCommandHandler(OpenAiCompatibleSettings settings) {
        this(
                settings,
                Path.of("").toAbsolutePath().normalize(),
                io.github.liumaishenjian.ccjava.domain.AgentLimits.DEFAULT.maxDuration());
    }

    /**
     * 使用 CLI 已解析的 Workspace 与墙钟限制装配 Headless Runtime。
     *
     * @param settings 已应用模型覆盖的 Provider 设置
     * @param workspace 已解析的真实 Workspace
     * @param timeout 每个 Run 的墙钟限制
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout) {
        this(settings, workspace, timeout, PermissionMode.DEFAULT);
    }

    /**
     * 使用显式 S05 Permission Mode 装配 Headless Runtime。
     *
     * @param settings 已应用模型覆盖的 Provider 设置
     * @param workspace 已解析的真实 Workspace
     * @param timeout 每个 Run 的墙钟限制
     * @param permissionMode 当前 Headless Session 的权限模式
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout,
            PermissionMode permissionMode) {
        this(settings, workspace, timeout, permissionMode, SessionOpenRequest.create());
    }

    /**
     * 使用 CLI 已解析的持久 Session 选择装配 Headless Runtime。
     *
     * @param settings 已应用模型覆盖的 Provider 设置
     * @param workspace 已解析的真实 Workspace
     * @param timeout 每个 Run 的墙钟限制
     * @param permissionMode 当前 Permission Mode
     * @param sessionOpenRequest Create/Continue/Resume/Fork 选择
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout,
            PermissionMode permissionMode,
            SessionOpenRequest sessionOpenRequest) {
        this(
                settings,
                workspace,
                timeout,
                permissionMode,
                sessionOpenRequest,
                Optional.empty(),
                ModelDiagnosticMode.OFF,
                Optional.empty());
    }

    /**
     * 使用可选显式 S07 启动容量装配 Headless Runtime。
     *
     * @param settings 已应用模型覆盖的 Provider 设置
     * @param workspace 已解析的真实 Workspace
     * @param timeout 每个 Run 的墙钟限制
     * @param permissionMode 当前 Permission Mode
     * @param sessionOpenRequest Create/Continue/Resume/Fork 选择
     * @param contextPreparation 可信 CLI 容量元组；空表示不启用 Projection
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout,
            PermissionMode permissionMode,
            SessionOpenRequest sessionOpenRequest,
            Optional<ContextPreparationConfig> contextPreparation) {
        this(settings, workspace, timeout, permissionMode, sessionOpenRequest,
                contextPreparation, ModelDiagnosticMode.OFF, Optional.empty());
    }

    /**
     * 使用显式本机诊断配置装配 Headless Runtime；目录不会进入 stdio 事件。
     *
     * @param settings Provider 设置
     * @param workspace 已解析 Workspace
     * @param timeout Run 墙钟限制
     * @param permissionMode 权限模式
     * @param sessionOpenRequest Session 选择
     * @param contextPreparation Context 配置
     * @param diagnosticMode 模型诊断模式
     * @param diagnosticDirectory 可选可信目录
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout,
            PermissionMode permissionMode,
            SessionOpenRequest sessionOpenRequest,
            Optional<ContextPreparationConfig> contextPreparation,
            ModelDiagnosticMode diagnosticMode,
            Optional<Path> diagnosticDirectory) {
        clock = Clock.systemUTC();
        assemblyScheduler = InputAssemblyScheduler.production();
        approvals = new StdioApprovalCoordinator(this::emitApprovalRequest);
        application = new HeadlessRuntimeSession(
                Objects.requireNonNull(settings, "settings 不能为空"),
                this,
                new HeadlessRuntimeOptions(
                        workspace,
                        settings.model(),
                        timeout,
                        permissionMode,
                        java.util.List.of(),
                        Objects.requireNonNull(sessionOpenRequest, "sessionOpenRequest 不能为空"),
                        SessionStorage.defaultRoot(),
                        Objects.requireNonNull(contextPreparation, "contextPreparation 不能为空"),
                        Objects.requireNonNull(diagnosticMode, "diagnosticMode 不能为空"),
                        Objects.requireNonNull(diagnosticDirectory, "diagnosticDirectory 不能为空")),
                approvals);
    }

    /**
     * 使用 Fake Model 装配真实 Runtime/stdio Adapter，供确定性契约测试使用。
     *
     * @param model 不访问网络的模型端口
     */
    RuntimeStdioCommandHandler(ModelGateway model) {
        this(
                model,
                new HeadlessRuntimeOptions(
                        Path.of("").toAbsolutePath().normalize(),
                        "fake-model",
                        io.github.liumaishenjian.ccjava.domain.AgentLimits.DEFAULT.maxDuration()));
    }

    /**
     * 使用 Fake Model 和显式 Workspace 装配真实 Runtime/stdio Adapter。
     *
     * @param model 不访问网络的模型端口
     * @param options 测试 Workspace 与墙钟配置
     */
    RuntimeStdioCommandHandler(
            ModelGateway model,
            HeadlessRuntimeOptions options) {
        this(model, options, Clock.systemUTC(), InputAssemblyScheduler.production());
    }

    RuntimeStdioCommandHandler(
            ModelGateway model,
            HeadlessRuntimeOptions options,
            Clock clock,
            InputAssemblyScheduler assemblyScheduler) {
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.assemblyScheduler = Objects.requireNonNull(assemblyScheduler, "assemblyScheduler 不能为空");
        approvals = new StdioApprovalCoordinator(this::emitApprovalRequest);
        application = new HeadlessRuntimeSession(
                Objects.requireNonNull(model, "model 不能为空"),
                this,
                Objects.requireNonNull(options, "options 不能为空"),
                approvals);
    }

    @Override
    public StdioProtocol.Disposition handle(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        return switch (command.type()) {
            case "initialize" -> initialize(command, events);
            case "run.start" -> startRun(command, events);
            case "input.begin" -> beginInput(command);
            case "input.chunk" -> appendInputChunk(command);
            case "input.commit" -> commitInput(command, events);
            case "run.cancel" -> cancelRun(command);
            case "approval.resolve" -> resolveApproval(command);
            case "checkpoint.list" -> listCheckpoints(command, events);
            case "checkpoint.diff" -> checkpointDiff(command, events);
            case "checkpoint.undo" -> checkpointUndo(command, events);
            case "session.command" -> sessionCommand(command, events);
            case "file.suggest" -> suggestFiles(command, events);
            case "shutdown" -> shutdown();
            default -> throw protocolError(
                    "UNKNOWN_COMMAND",
                    command,
                    "不支持该命令");
        };
    }

    private StdioProtocol.Disposition initialize(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        synchronized (lock) {
            ensureState(State.NEW, command);
            if (command.sessionId().isPresent() || command.runId().isPresent()) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "initialize 不能携带 Session 或 Run");
            }
            application.open();
            fileMentions = new io.github.liumaishenjian.ccjava.cli.mentions.FileMentionService(
                    application.workspaceGuard());
            fileSuggestions = new io.github.liumaishenjian.ccjava.cli.mentions.FileSuggestionService(
                    application.workspaceGuard());
            state = State.READY;
        }
        ObjectNode payload = codec.objectNode();
        payload.put("protocolVersion", StdioProtocol.VERSION);
        var sessionOpen = application.sessionOpenResult();
        payload.put("openMode", sessionOpen.mode().name().toLowerCase(Locale.ROOT));
        payload.put("readOnly", sessionOpen.readOnly());
        sessionOpen.parentSessionId().ifPresent(parent ->
                payload.put("parentSessionId", parent.value()));
        ArrayNode warnings = codec.arrayNode();
        sessionOpen.issues().forEach(issue ->
                warnings.add(issue.kind().name().toLowerCase(Locale.ROOT)));
        payload.set("warnings", warnings);
        events.emit(
                "initialized",
                command.requestId(),
                Optional.of(application.sessionId().value()),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition startRun(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String prompt = requiredPrompt(command);
        return startAcceptedInput(command, events, prompt);
    }

    private StdioProtocol.Disposition startAcceptedInput(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events,
            String prompt) throws StdioProtocolException {
        ActiveRun run;
        io.github.liumaishenjian.ccjava.domain.UserMessage message;
        synchronized (lock) {
            ensureStateReadyOrRunning(command);
            requireSession(command);
            if (command.runId().isPresent()) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "run.start 的 Run ID 必须由 Java 生成");
            }
            // 显式文件提及必须在创建 Run、写 Session 或请求模型之前完成权威校验。
            try {
                message = fileMentions.resolve(prompt);
            } catch (io.github.liumaishenjian.ccjava.cli.mentions.FileMentionException invalid) {
                throw protocolError(
                        io.github.liumaishenjian.ccjava.cli.mentions.FileMentionException.CODE,
                        command,
                        "显式文件提及无法安全解析");
            }
            if (state == State.RUNNING) {
                if (steeringQueue.size() >= MAX_STEERING_MESSAGES) {
                    throw protocolError("STEERING_QUEUE_FULL", command, "steering 队列已满");
                }
                QueuedSteering steering = new QueuedSteering(
                        command.requestId(), application.sessionId().value(), message, events);
                steeringQueue.addLast(steering);
                try {
                    emitSteeringQueued(command, events, steeringQueue.size());
                } catch (RuntimeException failure) {
                    closeForTransportFailureLocked();
                    throw failure;
                }
                return StdioProtocol.Disposition.CONTINUE;
            }
            run = startRunLocked(command.requestId(), prompt.length(), events);
        }
        io.github.liumaishenjian.ccjava.domain.UserMessage accepted = message;
        executor.submit(() -> executeRun(run, accepted));
        return StdioProtocol.Disposition.CONTINUE;
    }

    /**
     * 返回只服务 UX 的有界 Workspace-relative 候选，绝不启动 Run 或修改 Session。
     *
     * <p>候选不是授权依据：提交时仍由
     * {@link io.github.liumaishenjian.ccjava.cli.mentions.FileMentionService} 重新做权威校验。
     * 事件超过固定预算时从低优先级尾部移除候选；建议本来就不是完整清单，因此该裁剪不会改变
     * 权威文件解析语义。</p>
     *
     * @param command 已通过严格 schema 的 file.suggest
     * @param events 当前连接的有序事件出口
     * @return 连接继续读取下一条命令
     * @throws StdioProtocolException 状态、Session 或扫描不可用时
     */
    private StdioProtocol.Disposition suggestFiles(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String query;
        String sessionId;
        io.github.liumaishenjian.ccjava.cli.mentions.FileSuggestionService suggestionService;
        java.util.List<String> candidates;
        synchronized (lock) {
            ensureStateReadyOrRunning(command);
            requireSession(command);
            requireNoRunId(command);
            query = command.payload().get("query").stringValue();
            sessionId = application.sessionId().value();
            suggestionService = fileSuggestions;
        }
        try {
            candidates = suggestionService.suggest(query);
        } catch (RuntimeException failure) {
            throw protocolError("FILE_SUGGEST_UNAVAILABLE", command, "文件候选不可用");
        }
        ObjectNode payload = codec.objectNode();
        payload.put("query", query);
        ArrayNode items = codec.arrayNode();
        candidates.forEach(items::add);
        payload.set("candidates", items);
        StdioProtocol.Event sizeProbe = new StdioProtocol.Event(
                StdioProtocol.VERSION,
                "file.suggestions",
                command.requestId(),
                Optional.of(sessionId),
                Optional.empty(),
                Long.MAX_VALUE,
                payload);
        while (items.size() > 0
                && codec.encodeEvent(sizeProbe).getBytes(StandardCharsets.UTF_8).length + 1
                        > MAX_SUGGESTION_EVENT_BYTES) {
            items.remove(items.size() - 1);
        }
        if (codec.encodeEvent(sizeProbe).getBytes(StandardCharsets.UTF_8).length + 1
                > MAX_SUGGESTION_EVENT_BYTES) {
            throw protocolError("FILE_SUGGEST_TOO_LARGE", command, "文件候选事件超过预算");
        }
        events.emit(
                "file.suggestions",
                command.requestId(),
                Optional.of(sessionId),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition beginInput(StdioProtocol.Command command)
            throws StdioProtocolException {
        synchronized (lock) {
            ensureStateReadyOrRunning(command);
            requireSession(command);
            requireNoRunId(command);
            expireInputAssembly();
            String inputId = requiredAssemblyText(command, "inputId");
            rejectTerminalInputId(command, inputId);
            if (inputAssembly != null) {
                InputAssembly abandoned = inputAssembly;
                failAssemblyLocked(abandoned, InputTerminal.FAILED);
                recordInputTombstone(inputId, InputTerminal.FAILED);
                throw correlatedError("INPUT_IN_FLIGHT", command, abandoned.requestId, "已有输入正在组装");
            }
            int byteCount;
            int chunkCount;
            String digest;
            try {
                byteCount = requiredAssemblyInt(command, "byteCount", 1, MAX_EXPANDED_INPUT_BYTES);
                chunkCount = requiredAssemblyInt(command, "chunkCount", 1, MAX_INPUT_CHUNKS);
                digest = requiredAssemblyText(command, "sha256");
            } catch (StdioProtocolException invalid) {
                recordInputTombstone(inputId, InputTerminal.FAILED);
                throw invalid;
            }
            if (!digest.matches("[0-9a-f]{64}")) {
                recordInputTombstone(inputId, InputTerminal.FAILED);
                throw protocolError("INPUT_DIGEST_INVALID", command, "输入摘要格式无效");
            }
            inputAssembly = new InputAssembly(
                    command.requestId(), inputId, byteCount, chunkCount, digest,
                    clock.instant().plus(INPUT_ASSEMBLY_TIMEOUT), new java.io.ByteArrayOutputStream(byteCount));
            InputAssembly captured = inputAssembly;
            inputExpiry = assemblyScheduler.schedule(INPUT_ASSEMBLY_TIMEOUT, () -> expireInputAssembly(captured));
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition appendInputChunk(StdioProtocol.Command command)
            throws StdioProtocolException {
        synchronized (lock) {
            ensureStateReadyOrRunning(command);
            requireSession(command);
            requireNoRunId(command);
            InputAssembly assembly = requireAssembly(command);
            requireAssemblyId(command, assembly);
            int ordinal = requiredAssemblyInt(command, "ordinal", 0, MAX_INPUT_CHUNKS - 1);
            if (ordinal != assembly.receivedChunks) {
                failAssemblyLocked(assembly, InputTerminal.FAILED);
                throw correlatedError("INPUT_CHUNK_ORDER", command, assembly.requestId, "输入分块顺序不连续");
            }
            JsonNode value = command.payload().get("text");
            if (value == null || !value.isString() || value.stringValue().isEmpty()) {
                failAssemblyLocked(assembly, InputTerminal.FAILED);
                throw correlatedError("INPUT_CHUNK_INVALID", command, assembly.requestId, "输入分块必须是非空文本");
            }
            byte[] bytes = value.stringValue().getBytes(StandardCharsets.UTF_8);
            if (assembly.bytes.size() + bytes.length > assembly.byteCount
                    || assembly.receivedChunks >= assembly.chunkCount) {
                failAssemblyLocked(assembly, InputTerminal.FAILED);
                throw correlatedError("INPUT_SIZE_MISMATCH", command, assembly.requestId, "输入分块超过声明边界");
            }
            assembly.bytes.writeBytes(bytes);
            assembly.receivedChunks++;
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition commitInput(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String prompt;
        synchronized (lock) {
            ensureStateReadyOrRunning(command);
            requireSession(command);
            requireNoRunId(command);
            InputAssembly assembly = requireAssembly(command);
            requireAssemblyId(command, assembly);
            byte[] bytes = assembly.bytes.toByteArray();
            if (assembly.receivedChunks != assembly.chunkCount || bytes.length != assembly.byteCount
                    || !sha256(bytes).equals(assembly.sha256)) {
                failAssemblyLocked(assembly, InputTerminal.FAILED);
                throw correlatedError("INPUT_COMMIT_MISMATCH", command, assembly.requestId, "输入分块校验失败");
            }
            try {
                prompt = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException invalid) {
                failAssemblyLocked(assembly, InputTerminal.FAILED);
                throw correlatedError("INPUT_UTF8_INVALID", command, assembly.requestId, "输入不是严格 UTF-8");
            }
            if (prompt.isBlank() || prompt.length() > MAX_EXPANDED_INPUT_BYTES) {
                failAssemblyLocked(assembly, InputTerminal.FAILED);
                throw correlatedError("INVALID_PAYLOAD", command, assembly.requestId, "展开输入为空或超过限制");
            }
            completeAssemblyLocked(assembly, InputTerminal.COMPLETED);
            command = new StdioProtocol.Command(
                    command.version(), "run.start", assembly.requestId, command.sessionId(),
                    Optional.empty(), command.sequence(), command.payload());
        }
        return startAcceptedInput(command, events, prompt);
    }

    private InputAssembly requireAssembly(StdioProtocol.Command command) throws StdioProtocolException {
        expireInputAssembly();
        String inputId = requiredAssemblyText(command, "inputId");
        InputTerminal terminal = inputTombstones.get(inputId);
        if (terminal != null) {
            throw correlatedError("INPUT_REPLAY", command, command.requestId(), "输入 ID 已终结：" + terminal.name());
        }
        if (inputAssembly == null) {
            throw protocolError("INPUT_NOT_IN_FLIGHT", command, "没有正在组装的输入");
        }
        return inputAssembly;
    }

    private void requireAssemblyId(StdioProtocol.Command command, InputAssembly assembly)
            throws StdioProtocolException {
        String mismatchedId = requiredAssemblyText(command, "inputId");
        if (!assembly.inputId.equals(mismatchedId)) {
            failAssemblyLocked(assembly, InputTerminal.FAILED);
            recordInputTombstone(mismatchedId, InputTerminal.FAILED);
            throw correlatedError("INPUT_ID_MISMATCH", command, assembly.requestId, "输入 ID 不匹配");
        }
    }

    private void expireInputAssembly() {
        if (inputAssembly != null && !clock.instant().isBefore(inputAssembly.deadline)) {
            failAssemblyLocked(inputAssembly, InputTerminal.EXPIRED);
        }
    }

    private void expireInputAssembly(InputAssembly expected) {
        synchronized (lock) {
            if (inputAssembly == expected) failAssemblyLocked(expected, InputTerminal.EXPIRED);
        }
    }

    private void rejectTerminalInputId(StdioProtocol.Command command, String inputId)
            throws StdioProtocolException {
        InputTerminal terminal = inputTombstones.get(inputId);
        if (terminal != null) {
            throw correlatedError("INPUT_REPLAY", command, command.requestId(), "输入 ID 已终结：" + terminal.name());
        }
    }

    private void failAssemblyLocked(InputAssembly assembly, InputTerminal terminal) {
        completeAssemblyLocked(assembly, terminal);
    }

    private void completeAssemblyLocked(InputAssembly assembly, InputTerminal terminal) {
        if (inputAssembly == assembly) inputAssembly = null;
        if (inputExpiry != null) {
            inputExpiry.cancel();
            inputExpiry = null;
        }
        recordInputTombstone(assembly.inputId, terminal);
    }

    private void recordInputTombstone(String inputId, InputTerminal terminal) {
        inputTombstones.put(inputId, terminal);
        while (inputTombstones.size() > MAX_INPUT_TOMBSTONES) {
            inputTombstones.remove(inputTombstones.keySet().iterator().next());
        }
    }

    private StdioProtocolException correlatedError(
            String code, StdioProtocol.Command command, String logicalRequestId, String message) {
        return new StdioProtocolException(code, logicalRequestId, message);
    }

    private String requiredAssemblyText(StdioProtocol.Command command, String field)
            throws StdioProtocolException {
        JsonNode value = command.payload().get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()
                || (value.stringValue().length() > StdioProtocolCodec.MAX_IDENTIFIER_CHARS
                        && !field.equals("sha256"))) {
            throw protocolError("INVALID_PAYLOAD", command, field + " 无效");
        }
        return value.stringValue();
    }

    private int requiredAssemblyInt(StdioProtocol.Command command, String field, int minimum, int maximum)
            throws StdioProtocolException {
        JsonNode value = command.payload().get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum) {
            throw protocolError("INVALID_PAYLOAD", command, field + " 超过边界");
        }
        return value.intValue();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java 运行时缺少 SHA-256", impossible);
        }
    }

    private ActiveRun startRunLocked(String requestId, int promptChars, StdioProtocol.EventEmitter events) {
        ActiveRun run = new ActiveRun(requestId, promptChars, events);
        activeRun = run;
        state = State.RUNNING;
        return run;
    }

    private void emitSteeringQueued(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events,
            int queueDepth) {
        ObjectNode payload = codec.objectNode();
        payload.put("queueDepth", queueDepth);
        events.emit("steering.queued", command.requestId(), Optional.of(application.sessionId().value()), Optional.empty(), payload);
    }

    /**
     * 将严格解码的 stdio 命令交给 S08 Application dispatcher，并只发布一次安全终态。
     *
     * @param command 已通过 v0 传输/字段校验的命令
     * @param events 当前连接的有序事件出口
     * @return 连接继续读取下一条命令
     * @throws StdioProtocolException Session 不匹配或请求状态不合法时
     */
    private StdioProtocol.Disposition sessionCommand(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        SessionCommandResult result;
        CommandId commandId;
        synchronized (lock) {
            ensureStateNotNewOrClosed(command);
            requireSession(command);
            if (command.runId().isPresent()) {
                throw protocolError("INVALID_STATE", command, "session.command 不能携带 Run ID");
            }
            if (commandDispatcher == null) {
                commandDispatcher = new SessionCommandDispatcher(
                        application, new DoctorReportService(application),
                        () -> discardSteering(DiscardReason.CLEAR));
            }
            try {
                commandId = new CommandId(requiredSessionCommandText(command, "commandId"));
                if (commandRequestBudgetExhausted && !emittedCommandResults.contains(commandId)) {
                    return StdioProtocol.Disposition.SHUTDOWN;
                }
                SessionCommandIntent intent = decodeSessionCommandIntent(command);
                result = commandDispatcher.dispatch(commandId, intent, CancellationToken.none());
                if (intent instanceof SessionCommandIntent.Resume
                        && result.event().status() == io.github.liumaishenjian.ccjava.domain.command.SessionCommandStatus.SUCCEEDED) {
                    discardSteering(DiscardReason.SESSION_SWITCH);
                }
            } catch (IllegalArgumentException invalid) {
                throw protocolError("INVALID_PAYLOAD", command, "session.command 参数无效");
            }
        }
        synchronized (lock) {
            if (!emittedCommandResults.contains(commandId)) {
                if (result.event().code() == io.github.liumaishenjian.ccjava.domain.command.SessionCommandResultCode.REQUEST_BUDGET_EXHAUSTED) {
                    commandRequestBudgetExhausted = true;
                    emitSessionCommandResult(command.requestId(), result, events);
                    return StdioProtocol.Disposition.SHUTDOWN;
                }
                emittedCommandResults.add(commandId);
                emitSessionCommandResult(command.requestId(), result, events);
            }
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    private void emitSessionCommandResult(
            String requestId,
            SessionCommandResult result,
            StdioProtocol.EventEmitter events) {
        SessionCommandEvent event = result.event();
        ObjectNode payload = codec.objectNode();
        payload.put("commandId", event.commandId().value());
        payload.put("intent", safeIntentName(event.kind()));
        payload.put("status", event.status().name().toLowerCase(Locale.ROOT));
        payload.put("code", event.code().name().toLowerCase(Locale.ROOT));
        payload.set("result", sessionCommandPayload(event.payload()));
        events.emit("session.command.result", requestId, Optional.of(event.sessionId().value()), Optional.empty(), payload);
    }

    private ObjectNode sessionCommandPayload(SessionCommandEvent.SessionCommandPayload source) {
        ObjectNode payload = codec.objectNode();
        switch (source) {
            case SessionCommandEvent.EmptyPayload ignored -> { }
            case SessionCommandEvent.HelpPayload help -> {
                ArrayNode commands = codec.arrayNode();
                help.commands().forEach(value -> {
                    ObjectNode item = codec.objectNode();
                    item.put("intent", safeIntentName(value.kind()));
                    item.put("support", value.support().name().toLowerCase(Locale.ROOT));
                    commands.add(item);
                });
                payload.set("commands", commands);
            }
            case SessionCommandEvent.ContextPayload context -> {
                payload.put("systemTokens", context.systemTokens());
                payload.put("transcriptTokens", context.transcriptTokens());
                payload.put("toolTokens", context.toolTokens());
                payload.put("memoryTokens", context.memoryTokens());
                payload.put("totalTokens", context.totalTokens());
                payload.put("availableInputTokens", context.availableInputTokens());
                payload.put("freeTokens", context.freeTokens());
                payload.put("overflowTokens", context.overflowTokens());
                payload.put("sourceRevision", context.sourceRevision());
                payload.put("estimateKind", context.estimateKind());
                payload.put("contextStatus", context.status());
                payload.put("modelRequestAttempts", context.modelRequestAttempts());
                payload.set("reductionStrategies", mapperEnums(context.reductionStrategies()));
                payload.set("reasonCodes", mapperEnums(context.reasonCodes()));
            }
            case SessionCommandEvent.PermissionsPayload permissions -> {
                payload.put("effectiveMode", permissions.effectiveMode());
                payload.put("modeSourceKind", permissions.modeSourceKind());
                payload.put("modeSafeSourceId", permissions.modeSafeSourceId());
                payload.put("modeValidationStatus", permissions.modeValidationStatus());
                payload.put("startupRuleCount", permissions.startupRuleCount());
                ArrayNode rules = codec.arrayNode();
                permissions.rules().forEach(value -> {
                    ObjectNode item = codec.objectNode();
                    item.put("ruleId", value.ruleId());
                    item.put("sourceKind", value.sourceKind());
                    item.put("safeSourceId", value.safeSourceId());
                    item.put("operation", value.operation());
                    item.put("validationStatus", value.validationStatus());
                    rules.add(item);
                });
                payload.set("rules", rules);
            }
            case SessionCommandEvent.ResumePayload resume -> {
                payload.put("previousSessionId", resume.previousSessionId());
                payload.put("resumedSessionId", resume.resumedSessionId());
            }
            case SessionCommandEvent.DoctorPayload doctor -> {
                payload.put("settingsAvailable", doctor.settingsAvailable());
                payload.put("settingsRevision", doctor.settingsRevision());
                payload.put("instructionCount", doctor.instructionCount());
                payload.put("contextAvailable", doctor.contextAvailable());
                payload.put("activeRun", doctor.activeRun());
                ArrayNode entries = codec.arrayNode();
                doctor.entries().forEach(value -> {
                    ObjectNode item = codec.objectNode();
                    item.put("component", value.component());
                    item.put("sourceKind", value.sourceKind());
                    item.put("safeId", value.safeId());
                    item.put("code", value.code());
                    item.put("severity", value.severity());
                    entries.add(item);
                });
                payload.set("entries", entries);
            }
        }
        return payload;
    }

    private ArrayNode mapperEnums(java.util.List<String> values) {
        ArrayNode array = codec.arrayNode();
        values.forEach(array::add);
        return array;
    }

    private SessionCommandIntent decodeSessionCommandIntent(StdioProtocol.Command command) throws StdioProtocolException {
        String intent = requiredSessionCommandText(command, "intent");
        ObjectNode arguments = (ObjectNode) command.payload().get("arguments");
        return switch (intent) {
            case "help" -> new SessionCommandIntent.Help();
            case "clear" -> new SessionCommandIntent.Clear();
            case "compact" -> new SessionCommandIntent.Compact(
                    java.util.stream.StreamSupport.stream(arguments.get("anchors").spliterator(), false)
                            .map(JsonNode::stringValue).toList());
            case "context" -> new SessionCommandIntent.Context();
            case "doctor" -> new SessionCommandIntent.Doctor();
            case "model" -> new SessionCommandIntent.ModelChange(arguments.get("name").stringValue());
            case "permissions" -> new SessionCommandIntent.Permissions(arguments.isEmpty()
                    ? new SessionCommandIntent.PermissionsOperation.Query()
                    : new SessionCommandIntent.PermissionsOperation.ModeChange(
                            io.github.liumaishenjian.ccjava.domain.PermissionMode.valueOf(arguments.get("mode").stringValue())));
            case "resume" -> new SessionCommandIntent.Resume(new SessionId(arguments.get("sessionId").stringValue()));
            default -> throw protocolError("INVALID_ARGUMENT", command, "未知 session.command intent");
        };
    }

    private String requiredSessionCommandText(StdioProtocol.Command command, String field)
            throws StdioProtocolException {
        JsonNode value = command.payload().get(field);
        if (value == null || !value.isString()) {
            throw protocolError("INVALID_PAYLOAD", command, "session.command 缺少必填字段");
        }
        return value.stringValue();
    }

    private static String safeIntentName(io.github.liumaishenjian.ccjava.domain.command.SessionCommandKind kind) {
        return switch (kind) {
            case HELP -> "help";
            case CLEAR -> "clear";
            case COMPACT -> "compact";
            case CONTEXT -> "context";
            case DOCTOR -> "doctor";
            case MODEL_CHANGE -> "model";
            case PERMISSIONS -> "permissions";
            case RESUME -> "resume";
        };
    }

    private StdioProtocol.Disposition listCheckpoints(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
        }
        ArrayNode items = codec.arrayNode();
        for (var summary : application.checkpoints()) {
            ObjectNode item = codec.objectNode();
            item.put("checkpointId", summary.id().value());
            item.put("callId", summary.callId());
            item.put("toolName", summary.toolName());
            item.put("target", summary.target());
            item.put("existedBefore", summary.existedBefore());
            item.put("phase", summary.phase().name().toLowerCase(Locale.ROOT));
            item.put("undoable", summary.undoable());
            items.add(item);
        }
        ObjectNode payload = codec.objectNode();
        payload.set("checkpoints", items);
        events.emit(
                "checkpoint.listed",
                command.requestId(),
                Optional.of(application.sessionId().value()),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition checkpointDiff(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String checkpointId;
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
            checkpointId = requiredCheckpointId(command);
        }
        var diff = application.checkpointDiff(
                new io.github.liumaishenjian.ccjava.domain.CheckpointId(checkpointId));
        ObjectNode payload = codec.objectNode();
        payload.put("checkpointId", diff.checkpointId().value());
        payload.put("target", diff.target());
        payload.put("status", diff.status().name().toLowerCase(Locale.ROOT));
        payload.put("text", diff.text());
        payload.put("truncated", diff.truncated());
        events.emit(
                "checkpoint.diffed",
                command.requestId(),
                Optional.of(application.sessionId().value()),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition checkpointUndo(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String checkpointId;
        JsonNode confirmed = command.payload().get("confirmed");
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
            checkpointId = requiredCheckpointId(command);
            if (confirmed == null || !confirmed.isBoolean()) {
                throw protocolError("INVALID_PAYLOAD", command, "checkpoint.undo.confirmed 必须是布尔值");
            }
        }
        var result = application.undoCheckpoint(
                new io.github.liumaishenjian.ccjava.domain.CheckpointId(checkpointId),
                confirmed.booleanValue());
        ObjectNode payload = codec.objectNode();
        payload.put("checkpointId", result.checkpointId().value());
        payload.put("target", result.target());
        payload.put("status", result.status().name().toLowerCase(Locale.ROOT));
        payload.put("message", result.message());
        events.emit(
                "checkpoint.undone",
                command.requestId(),
                Optional.of(application.sessionId().value()),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition cancelRun(StdioProtocol.Command command)
            throws StdioProtocolException {
        synchronized (lock) {
            ensureState(State.RUNNING, command);
            requireSession(command);
            if (activeRun.runId == null
                    || command.runId().isEmpty()
                    || !activeRun.runId.value().equals(command.runId().orElseThrow())
                    || !application.cancel(activeRun.runId)) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "run.cancel 与活动 Run 不匹配或取消已经发生");
            }
            if (inputAssembly != null) failAssemblyLocked(inputAssembly, InputTerminal.CANCELLED);
            discardSteering(DiscardReason.CANCELLED);
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition shutdown() {
        RuntimeException failure = null;
        try {
            synchronized (lock) {
                state = State.CLOSED;
                if (inputAssembly != null) failAssemblyLocked(inputAssembly, InputTerminal.CANCELLED);
                cancelActiveRunLocked();
                discardSteering(DiscardReason.SHUTDOWN);
            }
        } catch (RuntimeException cleanupFailure) {
            failure = cleanupFailure;
        }
        try {
            approvals.close();
        } catch (RuntimeException closeFailure) {
            failure = retainFirstFailure(failure, closeFailure);
        }
        if (failure != null) {
            throw failure;
        }
        return StdioProtocol.Disposition.SHUTDOWN;
    }

    private StdioProtocol.Disposition resolveApproval(StdioProtocol.Command command)
            throws StdioProtocolException {
        String approvalId;
        ApprovalResponse decision;
        synchronized (lock) {
            ensureState(State.RUNNING, command);
            requireSession(command);
            if (activeRun == null
                    || activeRun.runId == null
                    || command.runId().isEmpty()
                    || !activeRun.runId.value().equals(command.runId().orElseThrow())) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "approval.resolve 与活动 Run 不匹配");
            }
            JsonNode id = command.payload().get("approvalId");
            JsonNode rawDecision = command.payload().get("decision");
            if (id == null
                    || !id.isString()
                    || id.stringValue().isBlank()
                    || id.stringValue().length() > 128
                    || rawDecision == null
                    || !rawDecision.isString()) {
                throw protocolError(
                        "INVALID_PAYLOAD",
                        command,
                        "approval.resolve payload 无效");
            }
            approvalId = id.stringValue();
            decision = switch (rawDecision.stringValue()) {
                case "allow_once" -> ApprovalResponse.allowOnce();
                case "allow_session" -> {
                    StdioApprovalCoordinator.Request pending = approvals.pendingRequest();
                    if (pending == null || !pending.approvalId().equals(approvalId)) {
                        throw protocolError(
                                "STALE_APPROVAL",
                                command,
                                "审批不存在、已结束或与当前请求不匹配");
                    }
                    yield ApprovalResponse.allowSession(pending.scope());
                }
                case "deny" -> ApprovalResponse.deny();
                default -> throw protocolError(
                        "INVALID_PAYLOAD",
                        command,
                        "approval.resolve decision 无效");
            };
        }
        if (!approvals.resolve(approvalId, decision)) {
            throw protocolError(
                    "STALE_APPROVAL",
                    command,
                    "审批不存在、已结束或与当前请求不匹配");
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    private void emitApprovalRequest(StdioApprovalCoordinator.Request request) {
        ActiveRun run;
        synchronized (lock) {
            run = activeRun;
            if (run == null
                    || run.runId == null
                    || !run.runId.equals(request.runId())
                    || state != State.RUNNING) {
                throw new IllegalStateException("审批请求与活动 Run 不匹配");
            }
        }
        ObjectNode payload = codec.objectNode();
        payload.put("approvalId", request.approvalId());
        payload.put("ordinal", request.ordinal());
        payload.put("toolName", request.toolName());
        payload.put("effect", request.effect().name().toLowerCase(Locale.ROOT));
        payload.put("sessionScope", !request.scope().toolWide());
        if (!request.preview().target().isEmpty()) {
            payload.put("target", request.preview().target());
            payload.put("operation", request.preview().operation());
            payload.put("removedLines", request.preview().removedLines());
            payload.put("addedLines", request.preview().addedLines());
        }
        if (!request.preview().command().isEmpty()) {
            payload.put("command", request.preview().command());
            payload.put("shell", request.preview().shell());
            payload.put("workingDirectory", request.preview().workingDirectory());
            payload.put("operation", request.preview().operation());
        }
        emit(run, "approval.requested", payload);
    }

    private void executeRun(ActiveRun run, io.github.liumaishenjian.ccjava.domain.UserMessage message) {
        try {
            application.run(message);
        } catch (RuntimeException exception) {
            emitUnexpectedFailure(run);
        }
    }

    @Override
    public void publish(AgentEventEnvelope envelope) {
        ActiveRun run;
        synchronized (lock) {
            run = activeRun;
            if (run == null
                    || !envelope.sessionId().equals(application.sessionId())) {
                return;
            }
            if (envelope.event() instanceof LifecycleEvent.RunStarted) {
                run.runId = envelope.runId().orElseThrow();
            } else if (run.runId == null
                    || envelope.runId().isEmpty()
                    || !run.runId.equals(envelope.runId().orElseThrow())) {
                return;
            }
        }

        if (envelope.event() instanceof LifecycleEvent.RunStarted) {
            ObjectNode payload = codec.objectNode();
            payload.put("promptChars", run.promptChars);
            emit(run, "run.started", payload);
        } else if (envelope.event() instanceof LifecycleEvent.BeforeTool before) {
            ObjectNode payload = codec.objectNode();
            payload.put("ordinal", before.ordinal());
            payload.put("toolName", before.call().name());
            payload.put("status", "started");
            safeToolMode(before.call()).ifPresent(mode -> {
                run.toolModes.put(before.ordinal(), mode);
                payload.put("mode", mode);
            });
            emit(run, "tool.started", payload);
        } else if (envelope.event() instanceof LifecycleEvent.AfterTool after) {
            ObjectNode payload = codec.objectNode();
            payload.put("ordinal", after.ordinal());
            payload.put("toolName", after.result().toolName());
            payload.put("status", after.result().status().name().toLowerCase());
            payload.put("returnedCharacters", after.result().metadata().returnedCharacters());
            payload.put("returnedItems", after.result().metadata().returnedItems());
            payload.put("truncated", after.result().metadata().truncated());
            payload.put(
                    "truncationReason",
                    after.result().metadata().truncationReason().name().toLowerCase(Locale.ROOT));
            payload.put("filteredItems", after.result().metadata().filteredItems());
            Optional.ofNullable(run.toolModes.remove(after.ordinal()))
                    .ifPresent(mode -> payload.put("mode", mode));
            after.result().error().ifPresent(error -> payload.put(
                    "errorCode", error.code().name().toLowerCase()));
            String type = after.result().status()
                    == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS
                            ? "tool.completed" : "tool.failed";
            emit(run, type, payload);
        } else if (envelope.event() instanceof LifecycleEvent.ToolOutput output) {
            ObjectNode payload = codec.objectNode();
            payload.put("ordinal", output.ordinal());
            payload.put("toolName", output.toolName());
            payload.put("stream", output.stream().name().toLowerCase(Locale.ROOT));
            payload.put("text", output.text());
            emit(run, "tool.output", payload);
        } else if (envelope.event() instanceof ModelTextDelta delta) {
            ObjectNode payload = codec.objectNode();
            payload.put("text", delta.text());
            payload.put("turn", delta.turnNumber());
            emit(run, "model.text.delta", payload);
        } else if (envelope.event() instanceof LifecycleEvent.RunFinished finished) {
            emitTerminal(run, finished.result());
        }
    }

    private void emitTerminal(ActiveRun run, AgentRunResult result) {
        ObjectNode payload = codec.objectNode();
        payload.put("stopReason", result.stopReason().name().toLowerCase());
        payload.put("modelTurns", result.modelTurns());
        payload.put("toolCalls", result.toolCalls());
        result.finalText().ifPresent(value -> payload.put("finalText", value));
        result.modelFailure().ifPresent(value -> {
            ObjectNode failure = codec.objectNode();
            failure.put("category", value.category().name().toLowerCase(Locale.ROOT));
            value.statusClass().ifPresent(status -> failure.put(
                    "statusClass",
                    status == io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass.CLIENT_ERROR
                            ? "4xx"
                            : "5xx"));
            failure.put("attempts", value.attempts());
            failure.put("receivedOutput", value.receivedOutput());
            payload.set("modelFailure", failure);
        });
        application.telemetry(result.runId())
                .ifPresent(value -> payload.set("telemetry", telemetryPayload(value)));
        String type = switch (result.stopReason()) {
            case COMPLETED -> "run.completed";
            case USER_CANCELLED -> "run.cancelled";
            default -> "run.failed";
        };
        emit(run, type, payload);
        finish(run, result.stopReason() == io.github.liumaishenjian.ccjava.domain.StopReason.USER_CANCELLED);
    }

    private ObjectNode telemetryPayload(RunTelemetry telemetry) {
        ObjectNode payload = codec.objectNode();
        payload.put("elapsedMillis", telemetry.elapsed().toMillis());
        payload.put("usageReportedTurns", telemetry.usageReportedTurns());
        payload.put("usageMissingTurns", telemetry.usageMissingTurns());

        ArrayNode modelTurns = codec.arrayNode();
        for (ModelTurnTelemetry turn : telemetry.modelTurns()) {
            ObjectNode item = codec.objectNode();
            item.put("turn", turn.turnNumber());
            item.put("elapsedMillis", turn.elapsed().toMillis());
            item.put("completed", turn.completed());
            turn.finishReason().ifPresent(
                    reason -> item.put("finishReason", reason.name().toLowerCase()));
            turn.usage().ifPresent(usage -> {
                ObjectNode usageNode = codec.objectNode();
                usageNode.put("inputTokens", usage.inputTokens());
                usageNode.put("outputTokens", usage.outputTokens());
                usageNode.put("totalTokens", usage.totalTokens());
                item.set("usage", usageNode);
            });
            modelTurns.add(item);
        }
        payload.set("modelTurns", modelTurns);

        ArrayNode toolCalls = codec.arrayNode();
        for (ToolCallTelemetry call : telemetry.toolCalls()) {
            ObjectNode item = codec.objectNode();
            item.put("ordinal", call.ordinal());
            item.put("elapsedMillis", call.elapsed().toMillis());
            item.put("completed", call.completed());
            toolCalls.add(item);
        }
        payload.set("toolCalls", toolCalls);

        telemetry.totalUsage().ifPresent(usage -> {
            ObjectNode usageNode = codec.objectNode();
            usageNode.put("inputTokens", usage.inputTokens());
            usageNode.put("outputTokens", usage.outputTokens());
            usageNode.put("totalTokens", usage.totalTokens());
            payload.set("totalUsage", usageNode);
        });
        return payload;
    }

    /**
     * 从 Tool Call 中只提取允许进入展示协议的固定枚举，不暴露查询、路径或其他参数。
     *
     * @param call 原始 Tool Call
     * @return search_text 的安全模式；非搜索或非法模式为空
     */
    static Optional<String> safeToolMode(ToolCall call) {
        Objects.requireNonNull(call, "call 不能为空");
        if (!"search_text".equals(call.name())) {
            return Optional.empty();
        }
        try {
            String mode = call.arguments().string("mode")
                    .orElse("content")
                    .toLowerCase(Locale.ROOT);
            return switch (mode) {
                case "content", "files", "count" -> Optional.of(mode);
                default -> Optional.empty();
            };
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private void emitUnexpectedFailure(ActiveRun run) {
        synchronized (lock) {
            if (activeRun != run || run.runId == null) {
                return;
            }
        }
        ObjectNode payload = codec.objectNode();
        payload.put("code", "RUNTIME_FAILURE");
        payload.put("stopReason", "internal_error");
        payload.put("modelTurns", 0);
        payload.put("toolCalls", 0);
        emit(run, "run.failed", payload);
        finish(run, false);
    }

    private void emit(ActiveRun run, String type, ObjectNode payload) {
        try {
            run.events.emit(
                    type,
                    run.requestId,
                    Optional.of(application.sessionId().value()),
                    Optional.of(run.runId.value()),
                    payload);
        } catch (RuntimeException failure) {
            synchronized (lock) {
                closeForTransportFailureLocked();
            }
            throw failure;
        }
    }

    /**
     * 把不可继续的事件传输故障收敛为关闭状态。
     *
     * <p>未发送 steering 只存在于本适配器内存，传输失效时不能再启动它们。丢弃事件本身无法可靠
     * 投影，故仅在内存中移除；活动 Run 交给既有取消路径，并禁止其终态后继续调度下一 Run。</p>
     */
    private void closeForTransportFailureLocked() {
        state = State.CLOSED;
        steeringQueue.clear();
        cancelActiveRunLocked();
    }

    private void cancelActiveRunLocked() {
        if (activeRun != null && activeRun.runId != null) {
            application.cancel(activeRun.runId);
        }
    }

    private static RuntimeException retainFirstFailure(
            RuntimeException first,
            RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    /**
     * 在唯一 Run 终态已经投影后释放活动状态，并且只在安全边界调度下一条 steering。
     *
     * <p>当前 Run 的终态事件先于下一 Run 的启动；用户取消、关闭或显式丢弃都不会消费未发送
     * 文本，其余终态才可进入下一条。队列中的原始文本始终只停留在本适配器内存，直到被实际启动时
     * 才交给 Runtime。</p>
     */
    private void finish(ActiveRun run, boolean discardQueuedSteering) {
        QueuedSteering next = null;
        synchronized (lock) {
            if (activeRun != run) {
                return;
            }
            activeRun = null;
            if (discardQueuedSteering || state == State.CLOSED) {
                discardSteering(discardQueuedSteering ? DiscardReason.CANCELLED : DiscardReason.SHUTDOWN);
                return;
            }
            state = State.READY;
            next = steeringQueue.pollFirst();
            if (next != null) {
                QueuedSteering steering = next;
                ActiveRun nextRun = startRunLocked(
                        steering.requestId(), steering.message().content().length(), steering.events());
                executor.submit(() -> executeRun(nextRun, steering.message()));
            }
        }
    }

    /**
     * 清除尚未消费的 Surface steering，不触及 Runtime 或任何 durable state。
     *
     * <p>每条已接收消息恰好产生一次不含文本的 discarded 投影；空队列不产生事件，重复清理也不会
     * 重复投影。reason 是固定枚举值，避免将用户输入、路径或其他不可信内容写入 stdout。</p>
     */
    private void discardSteering(DiscardReason reason) {
        RuntimeException failure = null;
        QueuedSteering steering;
        while ((steering = steeringQueue.pollFirst()) != null) {
            ObjectNode payload = codec.objectNode();
            payload.put("reason", reason.wireValue());
            try {
                steering.events().emit("steering.discarded", steering.requestId(), Optional.of(steering.sessionId()),
                        Optional.empty(), payload);
            } catch (RuntimeException emissionFailure) {
                if (failure == null) {
                    failure = emissionFailure;
                }
            }
        }
        if (failure != null) {
            closeForTransportFailureLocked();
            throw failure;
        }
    }

    private String requiredPrompt(StdioProtocol.Command command)
            throws StdioProtocolException {
        JsonNode prompt = command.payload().get("prompt");
        if (prompt == null
                || !prompt.isString()
                || prompt.stringValue().isBlank()
                || prompt.stringValue().length() > HeadlessRuntimeSession.MAX_PROMPT_CHARS
                || prompt.stringValue().codePointCount(0, prompt.stringValue().length()) > HeadlessRuntimeSession.MAX_PROMPT_CHARS
                || prompt.stringValue().getBytes(StandardCharsets.UTF_8).length > HeadlessRuntimeSession.MAX_PROMPT_UTF8_BYTES) {
            throw protocolError(
                    "INVALID_PAYLOAD",
                    command,
                    "run.start.prompt 为空或超过长度限制");
        }
        return prompt.stringValue();
    }

    private void ensureStateNotNewOrClosed(StdioProtocol.Command command)
            throws StdioProtocolException {
        if (state == State.NEW || state == State.CLOSED) {
            throw protocolError("INVALID_STATE", command, "session.command 需要已初始化且未关闭的 Session");
        }
    }

    private void ensureState(State expected, StdioProtocol.Command command)
            throws StdioProtocolException {
        if (state != expected) {
            throw protocolError(
                    "INVALID_STATE",
                    command,
                    "命令与当前 Application 状态不兼容");
        }
    }

    private void ensureStateReadyOrRunning(StdioProtocol.Command command)
            throws StdioProtocolException {
        if (state != State.READY && state != State.RUNNING) {
            throw protocolError(
                    "INVALID_STATE",
                    command,
                    "run.start 需要已初始化且未关闭的 Session");
        }
    }

    private void requireSession(StdioProtocol.Command command)
            throws StdioProtocolException {
        if (command.sessionId().isEmpty()
                || !application.sessionId().value().equals(command.sessionId().orElseThrow())) {
            throw protocolError(
                    "INVALID_STATE",
                    command,
                    "命令 Session 与当前连接不匹配");
        }
    }

    private void requireNoRunId(StdioProtocol.Command command) throws StdioProtocolException {
        if (command.runId().isPresent()) {
            throw protocolError("INVALID_STATE", command, "Checkpoint 命令不能携带 Run ID");
        }
    }

    private String requiredCheckpointId(StdioProtocol.Command command)
            throws StdioProtocolException {
        JsonNode value = command.payload().get("checkpointId");
        if (value == null
                || !value.isString()
                || value.stringValue().isBlank()
                || value.stringValue().length() > 128) {
            throw protocolError("INVALID_PAYLOAD", command, "checkpointId 为空或超过长度限制");
        }
        try {
            return new io.github.liumaishenjian.ccjava.domain.CheckpointId(
                            value.stringValue())
                    .value();
        } catch (IllegalArgumentException invalid) {
            throw protocolError("INVALID_PAYLOAD", command, "checkpointId 格式无效");
        }
    }

    private StdioProtocolException protocolError(
            String code,
            StdioProtocol.Command command,
            String message) {
        return new StdioProtocolException(code, command.requestId(), message);
    }

    @Override
    public void close() throws InterruptedException {
        RuntimeException failure = null;
        try {
            synchronized (lock) {
                state = State.CLOSED;
                if (inputAssembly != null) failAssemblyLocked(inputAssembly, InputTerminal.CANCELLED);
                cancelActiveRunLocked();
                discardSteering(DiscardReason.SHUTDOWN);
            }
        } catch (RuntimeException cleanupFailure) {
            failure = cleanupFailure;
        }
        try {
            approvals.close();
        } catch (RuntimeException closeFailure) {
            failure = retainFirstFailure(failure, closeFailure);
        }
        assemblyScheduler.close();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    failure = retainFirstFailure(failure,
                            new IllegalStateException("Runtime Run Executor 未退出"));
                }
            }
        } finally {
            if (state != State.NEW) {
                try {
                    application.close();
                } catch (RuntimeException closeFailure) {
                    failure = retainFirstFailure(failure, closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    interface ExpiryHandle {
        void cancel();
    }

    interface InputAssemblyScheduler extends AutoCloseable {
        ExpiryHandle schedule(Duration delay, Runnable task);

        static InputAssemblyScheduler production() {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("cc-java-input-expiry").daemon(true).factory());
            return new InputAssemblyScheduler() {
                @Override
                public ExpiryHandle schedule(Duration delay, Runnable task) {
                    ScheduledFuture<?> future = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
                    return () -> future.cancel(false);
                }

                @Override
                public void close() {
                    executor.shutdownNow();
                }
            };
        }

        @Override
        void close();
    }

    private enum InputTerminal {
        COMPLETED,
        FAILED,
        EXPIRED,
        CANCELLED
    }

    private enum State {
        NEW,
        READY,
        RUNNING,
        CLOSED
    }

    /** steering 丢弃原因只用于内部状态转换，禁止携带用户文本。 */
    private enum DiscardReason {
        CLEAR("clear"),
        CANCELLED("cancelled"),
        SESSION_SWITCH("session_switch"),
        SHUTDOWN("shutdown");

        private final String wireValue;

        DiscardReason(String wireValue) {
            this.wireValue = wireValue;
        }

        private String wireValue() {
            return wireValue;
        }
    }

    /**
     * 尚未送入 Runtime 的单条 Surface 输入。
     *
     * <p>该对象不进入 AgentEvent、Canonical Transcript、Session JSONL 或 Checkpoint；其文本仅在
     * 当前 Run 正常终态后的安全边界被消费一次。</p>
     */
    private record QueuedSteering(
            String requestId,
            String sessionId,
            io.github.liumaishenjian.ccjava.domain.UserMessage message,
            StdioProtocol.EventEmitter events) {
        private QueuedSteering {
            Objects.requireNonNull(requestId, "requestId 不能为空");
            Objects.requireNonNull(sessionId, "sessionId 不能为空");
            Objects.requireNonNull(message, "message 不能为空");
            Objects.requireNonNull(events, "events 不能为空");
        }
    }

    private static final class InputAssembly {
        private final String requestId;
        private final String inputId;
        private final int byteCount;
        private final int chunkCount;
        private final String sha256;
        private final Instant deadline;
        private final java.io.ByteArrayOutputStream bytes;
        private int receivedChunks;

        private InputAssembly(
                String requestId,
                String inputId,
                int byteCount,
                int chunkCount,
                String sha256,
                Instant deadline,
                java.io.ByteArrayOutputStream bytes) {
            this.requestId = requestId;
            this.inputId = inputId;
            this.byteCount = byteCount;
            this.chunkCount = chunkCount;
            this.sha256 = sha256;
            this.deadline = deadline;
            this.bytes = bytes;
        }
    }

    private static final class ActiveRun {
        private final String requestId;
        private final int promptChars;
        private final StdioProtocol.EventEmitter events;
        private final Map<Integer, String> toolModes = new LinkedHashMap<>();
        private RunId runId;

        private ActiveRun(
                String requestId,
                int promptChars,
                StdioProtocol.EventEmitter events) {
            this.requestId = requestId;
            this.promptChars = promptChars;
            this.events = events;
        }
    }
}
