package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.ContextUsageView;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.command.CommandId;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandIntent;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandKind;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandResult;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandResultCode;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandStatus;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADR-047 Session Command 的 Java Application 分派基础。
 *
 * <p>分派器只调用既有的只读 Runtime seam 或 Surface transient port；没有安全实现的命令
 * 返回固定终态，绝不绕过 S05 Pipeline、S06 Recovery Gate 或 S07 Context Gate。每个
 * {@link CommandId} 的首次终态会在当前 dispatcher 生命周期内缓存，重复分派不会重新执行副作用。
 * 缓存由固定 request budget 限制且不淘汰；耗尽后新 ID 在执行前 fail closed。</p>
 *
 * @since 0.8.0
 */
public final class SessionCommandDispatcher {
    private final HeadlessRuntimeSession runtime;
    private final DoctorReportService doctor;
    private final SurfaceTransientState transientState;
    private final boolean hasTransientSurface;
    private final int maxCommandIds;
    private final Map<CommandId, SessionCommandResult> terminalResults = new HashMap<>();

    /**
     * 创建未接入 stdio/TUI transient state 的 dispatcher。
     *
     * @param runtime 提供只读状态与活动 Run Gate 的当前 Runtime
     * @param doctor 只读 doctor 投影服务
     */
    public SessionCommandDispatcher(HeadlessRuntimeSession runtime, DoctorReportService doctor) {
        this(runtime, doctor, () -> { }, false, 256);
    }

    /**
     * 创建绑定具体 transient Surface state 的 dispatcher。
     *
     * @param runtime 提供只读状态与活动 Run Gate 的当前 Runtime
     * @param doctor 只读 doctor 投影服务
     * @param transientState 仅允许清理 Surface 瞬态状态的端口
     */
    public SessionCommandDispatcher(HeadlessRuntimeSession runtime, DoctorReportService doctor,
                                    SurfaceTransientState transientState) {
        this(runtime, doctor, transientState, true, 256);
    }

    /**
     * 创建使用固定 request budget 的 dispatcher。
     *
     * <p>已接受的 commandId 永不淘汰，避免重复请求重放 Surface 副作用；达到上限时仅拒绝
     * 新 commandId，不执行其 intent。</p>
     *
     * @param runtime 提供只读状态与活动 Run Gate 的当前 Runtime
     * @param doctor 只读 doctor 投影服务
     * @param transientState 仅允许清理 Surface 瞬态状态的端口
     * @param maxCommandIds 当前 dispatcher 生命周期中可接受的不同 commandId 数
     */
    public SessionCommandDispatcher(HeadlessRuntimeSession runtime, DoctorReportService doctor,
                                    SurfaceTransientState transientState, int maxCommandIds) {
        this(runtime, doctor, transientState, true, maxCommandIds);
    }

    private SessionCommandDispatcher(HeadlessRuntimeSession runtime, DoctorReportService doctor,
                                     SurfaceTransientState transientState, boolean hasTransientSurface,
                                     int maxCommandIds) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
        this.doctor = Objects.requireNonNull(doctor, "doctor 不能为空");
        this.transientState = Objects.requireNonNull(transientState, "transientState 不能为空");
        if (maxCommandIds <= 0) throw new IllegalArgumentException("maxCommandIds 必须为正数");
        this.hasTransientSurface = hasTransientSurface;
        this.maxCommandIds = maxCommandIds;
    }

    /**
     * 执行一次命令分派并返回唯一终态。
     *
     * @param commandId 本次请求关联标识
     * @param intent 已解码且受限的命令意图
     * @param cancellationToken 本次请求的协作式取消边界
     * @return 与 commandId 一一对应的唯一终态
     */
    public synchronized SessionCommandResult dispatch(CommandId commandId, SessionCommandIntent intent,
                                                      CancellationToken cancellationToken) {
        Objects.requireNonNull(commandId, "commandId 不能为空");
        Objects.requireNonNull(intent, "intent 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        SessionCommandResult previous = terminalResults.get(commandId);
        if (previous != null) return previous;
        if (terminalResults.size() >= maxCommandIds) {
            return rejected(intent.kind(), commandId, safeSessionId(), SessionCommandResultCode.REQUEST_BUDGET_EXHAUSTED);
        }
        SessionCommandResult result = dispatchOnce(commandId, intent, cancellationToken);
        terminalResults.put(commandId, result);
        return result;
    }

    private SessionCommandResult dispatchOnce(CommandId commandId, SessionCommandIntent intent,
                                              CancellationToken cancellationToken) {
        try {
            SessionId sessionId = runtime.sessionId();
            if (cancellationToken.isCancellationRequested()) {
                return terminal(intent.kind(), commandId, sessionId, SessionCommandStatus.CANCELLED,
                        SessionCommandResultCode.CANCELLED, new SessionCommandEvent.EmptyPayload());
            }
            if (requiresIdle(intent) && runtime.hasActiveRun()) {
                return terminal(intent.kind(), commandId, sessionId, SessionCommandStatus.REJECTED,
                        SessionCommandResultCode.ACTIVE_RUN, new SessionCommandEvent.EmptyPayload());
            }
            return switch (intent) {
                case SessionCommandIntent.Help ignored -> success(intent.kind(), commandId, sessionId, help());
                case SessionCommandIntent.Clear ignored -> clear(commandId, sessionId);
                case SessionCommandIntent.Compact ignored -> rejected(intent.kind(), commandId, sessionId,
                        SessionCommandResultCode.NOT_AVAILABLE);
                case SessionCommandIntent.Context ignored -> context(commandId, sessionId);
                case SessionCommandIntent.Doctor ignored -> success(intent.kind(), commandId, sessionId, doctor.report());
                case SessionCommandIntent.ModelChange ignored -> rejected(intent.kind(), commandId, sessionId,
                        SessionCommandResultCode.NOT_AVAILABLE);
                case SessionCommandIntent.Permissions ignored -> rejected(intent.kind(), commandId, sessionId,
                        SessionCommandResultCode.DEFERRED);
                case SessionCommandIntent.Resume ignored -> rejected(intent.kind(), commandId, sessionId,
                        SessionCommandResultCode.DEFERRED);
            };
        } catch (RuntimeException ignored) {
            return terminal(intent.kind(), commandId, safeSessionId(), SessionCommandStatus.FAILED,
                    SessionCommandResultCode.INTERNAL_FAILURE, new SessionCommandEvent.EmptyPayload());
        }
    }

    private SessionCommandResult clear(CommandId commandId, SessionId sessionId) {
        if (!hasTransientSurface) {
            return rejected(SessionCommandKind.CLEAR, commandId, sessionId, SessionCommandResultCode.DEFERRED);
        }
        transientState.clear();
        return success(SessionCommandKind.CLEAR, commandId, sessionId, new SessionCommandEvent.EmptyPayload());
    }

    private SessionCommandResult context(CommandId commandId, SessionId sessionId) {
        return runtime.latestContextUsage()
                .<SessionCommandResult>map(view -> success(SessionCommandKind.CONTEXT, commandId, sessionId,
                        DoctorReportService.context(view)))
                .orElseGet(() -> rejected(SessionCommandKind.CONTEXT, commandId, sessionId,
                        SessionCommandResultCode.UNAVAILABLE));
    }

    private static boolean requiresIdle(SessionCommandIntent intent) {
        return intent instanceof SessionCommandIntent.Compact
                || intent instanceof SessionCommandIntent.ModelChange
                || intent instanceof SessionCommandIntent.Permissions
                || intent instanceof SessionCommandIntent.Resume;
    }

    private SessionCommandEvent.HelpPayload help() {
        List<SessionCommandEvent.CommandAvailability> commands = Arrays.stream(SessionCommandKind.values())
                .map(kind -> new SessionCommandEvent.CommandAvailability(kind, support(kind))).toList();
        return new SessionCommandEvent.HelpPayload(commands);
    }

    private SessionCommandEvent.CommandSupport support(SessionCommandKind kind) {
        return switch (kind) {
            case HELP, CONTEXT, DOCTOR -> SessionCommandEvent.CommandSupport.AVAILABLE;
            case CLEAR -> hasTransientSurface ? SessionCommandEvent.CommandSupport.AVAILABLE
                    : SessionCommandEvent.CommandSupport.DEFERRED;
            case COMPACT, MODEL_CHANGE -> SessionCommandEvent.CommandSupport.NOT_AVAILABLE;
            case PERMISSIONS, RESUME -> SessionCommandEvent.CommandSupport.DEFERRED;
        };
    }

    private static SessionCommandResult success(SessionCommandKind kind, CommandId commandId, SessionId sessionId,
                                                SessionCommandEvent.SessionCommandPayload payload) {
        return terminal(kind, commandId, sessionId, SessionCommandStatus.SUCCEEDED, SessionCommandResultCode.OK, payload);
    }

    private static SessionCommandResult rejected(SessionCommandKind kind, CommandId commandId, SessionId sessionId,
                                                 SessionCommandResultCode code) {
        return terminal(kind, commandId, sessionId, SessionCommandStatus.REJECTED, code,
                new SessionCommandEvent.EmptyPayload());
    }

    private static SessionCommandResult terminal(SessionCommandKind kind, CommandId commandId, SessionId sessionId,
                                                 SessionCommandStatus status, SessionCommandResultCode code,
                                                 SessionCommandEvent.SessionCommandPayload payload) {
        SessionCommandEvent event = new SessionCommandEvent(kind, commandId, sessionId, status, code, payload);
        return switch (status) {
            case SUCCEEDED -> new SessionCommandResult.Succeeded(event);
            case REJECTED -> new SessionCommandResult.Rejected(event);
            case CANCELLED -> new SessionCommandResult.Cancelled(event);
            case FAILED -> new SessionCommandResult.Failed(event);
        };
    }

    private SessionId safeSessionId() {
        try {
            return runtime.sessionId();
        } catch (RuntimeException ignored) {
            return new SessionId("unavailable");
        }
    }
}
