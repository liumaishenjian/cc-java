package io.github.liumaishenjian.ccjava.cli.instructions;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionContextService;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionDiscoveryCancelledException;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionDiscoveryRequest;
import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import io.github.liumaishenjian.ccjava.domain.instructions.ResolvedInstruction;
import io.github.liumaishenjian.ccjava.domain.instructions.ResolvedInstructions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 管理一次 Headless Session 的已验证 Instructions 快照及目录激活目标。
 *
 * <p>本类型只在 CLI Application 层使用 A1 的真实路径 Adapter。它每次模型请求前以 copy-on-write
 * 方式重建完整 {@link ResolvedInstructions}，失败或取消时保留最后完整快照。它不持久化正文或目标，
 * 也不从 Tool 输出、模型文本或用户文本推导目录范围。</p>
 *
 * @since 0.8.0
 */
public final class InstructionProjectionState implements InstructionContextService {
    private static final String OPEN = "<instructions>\n";
    private static final String CLOSE = "\n</instructions>";

    private final InstructionFoundationFactory.InstructionFoundation foundation;
    private final Map<String, VerifiedInstructionTarget> targets = new LinkedHashMap<>();
    private volatile ResolvedInstructions latest;

    /**
     * 建立绑定固定 user-root 和 Workspace 的短生命周期 projection 状态。
     *
     * @param foundation 已完成安全装配的 A1 基础设施
     */
    public InstructionProjectionState(InstructionFoundationFactory.InstructionFoundation foundation) {
        this.foundation = Objects.requireNonNull(foundation, "foundation 不能为空");
    }

    @Override
    public ModelRequest project(ModelRequest canonical, CancellationToken cancellationToken) {
        Objects.requireNonNull(canonical, "canonical 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        ResolvedInstructions snapshot = cancellationToken.isCancellationRequested()
                ? latest
                : refresh(cancellationToken).orElse(latest);
        return snapshot == null || snapshot.items().isEmpty()
                ? canonical
                : inject(canonical, snapshot.items());
    }

    @Override
    public void recordSuccessfulTool(ToolCall call, ToolResult result, CancellationToken cancellationToken) {
        Objects.requireNonNull(call, "call 不能为空");
        Objects.requireNonNull(result, "result 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (cancellationToken.isCancellationRequested()
                || result.status() != ToolResultStatus.SUCCESS
                || !call.id().equals(result.callId())) {
            return;
        }
        targetFor(call).ifPresent(target -> {
            synchronized (targets) {
                targets.putIfAbsent(target.protocolPath() + "#" + target.kind(), target);
            }
        });
    }

    /** 返回只供测试观察的最近完整 revision；不包含正文或路径。 */
    Optional<String> latestRevision() {
        ResolvedInstructions snapshot = latest;
        return snapshot == null ? Optional.empty() : Optional.of(snapshot.revision().value());
    }

    private Optional<ResolvedInstructions> refresh(CancellationToken cancellationToken) {
        try {
            List<VerifiedInstructionTarget> currentTargets;
            synchronized (targets) {
                currentTargets = List.copyOf(targets.values());
            }
            ResolvedInstructions discovered = foundation.discovery().discover(
                    new InstructionDiscoveryRequest(foundation.planner().plan(currentTargets)), cancellationToken);
            if (!cancellationToken.isCancellationRequested()) {
                latest = discovered;
                return Optional.of(discovered);
            }
        } catch (InstructionDiscoveryCancelledException | IllegalArgumentException ignored) {
            // 取消或无效候选均不得覆盖已发布快照。
        } catch (RuntimeException ignored) {
            // Adapter 失败也只保留最后完整快照，不能影响模型/Tool 的权威路径。
        }
        return Optional.empty();
    }

    private Optional<VerifiedInstructionTarget> targetFor(ToolCall call) {
        try {
            return switch (call.name()) {
                case "read_file", "write_file", "apply_patch" -> requiredFileArgument(call, "path");
                case "list_files" -> requiredDirectoryArgument(call, "path", ".");
                case "search_text" -> existingTarget(requiredArgument(call, "path", "."));
                default -> Optional.empty();
            };
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<VerifiedInstructionTarget> requiredFileArgument(ToolCall call, String field) throws Exception {
        return Optional.of(VerifiedInstructionTarget.file(
                foundation.workspaceGuard(), requiredArgument(call, field, null)));
    }

    private Optional<VerifiedInstructionTarget> requiredDirectoryArgument(
            ToolCall call, String field, String defaultValue) throws Exception {
        return Optional.of(VerifiedInstructionTarget.directory(
                foundation.workspaceGuard(), requiredArgument(call, field, defaultValue)));
    }

    private static String requiredArgument(ToolCall call, String field, String defaultValue) {
        return call.arguments().string(field).orElse(defaultValue);
    }

    private Optional<VerifiedInstructionTarget> existingTarget(String path) {
        try {
            return Optional.of(VerifiedInstructionTarget.file(foundation.workspaceGuard(), path));
        } catch (Exception ignored) {
            try {
                return Optional.of(VerifiedInstructionTarget.directory(foundation.workspaceGuard(), path));
            } catch (Exception stillInvalid) {
                return Optional.empty();
            }
        }
    }

    private static ModelRequest inject(ModelRequest canonical, List<ResolvedInstruction> items) {
        if (canonical.messages().isEmpty() || !(canonical.messages().getFirst() instanceof SystemMessage system)) {
            return canonical;
        }
        StringBuilder content = new StringBuilder(system.content());
        for (ResolvedInstruction item : items) {
            content.append("\n\n").append(OPEN).append(item.boundedText()).append(CLOSE);
        }
        List<AgentMessage> messages = new ArrayList<>(canonical.messages());
        messages.set(0, new SystemMessage(content.toString()));
        return new ModelRequest(
                canonical.sessionId(), canonical.runId(), canonical.turnNumber(), messages, canonical.toolDefinitions());
    }
}
