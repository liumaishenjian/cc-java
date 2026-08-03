package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.core.ApprovalHandler;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import io.github.liumaishenjian.ccjava.tools.local.command.CommandShell;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;

/**
 * 把同步 Tool Pipeline 的 ASK 决策桥接为 stdio 单次审批。
 *
 * <p>协调器在任意时刻至多保存一个待决请求。只有匹配的审批 ID 可以完成等待；
 * Run 取消、连接关闭或显式关闭都会按 Deny 释放等待线程。该类型不缓存 Session
 * 授权，也不把 Tool 原始参数交给终端。</p>
 *
 * @since 0.1.0
 */
final class StdioApprovalCoordinator implements ApprovalHandler, AutoCloseable {

    private static final int MAX_PREVIEW_PATH_CHARACTERS = 512;
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");

    private final Object lock = new Object();
    private final Consumer<Request> requestSink;
    private final Supplier<String> idSupplier;
    private Pending pending;
    private boolean closed;

    /**
     * 使用随机关联 ID 创建协调器。
     *
     * @param requestSink 安全审批摘要的事件出口
     */
    StdioApprovalCoordinator(Consumer<Request> requestSink) {
        this(requestSink, () -> UUID.randomUUID().toString());
    }

    /**
     * 使用可注入 ID 创建可确定性验证的协调器。
     *
     * @param requestSink 安全审批摘要的事件出口
     * @param idSupplier 唯一审批 ID 来源
     */
    StdioApprovalCoordinator(
            Consumer<Request> requestSink,
            Supplier<String> idSupplier) {
        this.requestSink = Objects.requireNonNull(requestSink, "requestSink 不能为空");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier 不能为空");
    }

    /**
     * 发布审批请求并等待匹配决定。
     *
     * @param invocation 当前 Tool 调用
     * @param definition Tool Definition
     * @return Allow 或 Deny；取消和关闭均返回 Deny
     */
    @Override
    public ApprovalResponse requestApproval(
            ToolInvocation invocation,
            ToolDefinition definition,
            PermissionOutcome outcome) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        Objects.requireNonNull(definition, "definition 不能为空");
        Objects.requireNonNull(outcome, "outcome 不能为空");
        if (invocation.cancellationToken().isCancellationRequested()) {
            return ApprovalResponse.deny();
        }

        String approvalId = requireId(idSupplier.get());
        Pending current = new Pending(
                new Request(
                        approvalId,
                        invocation.runId(),
                        invocation.ordinal(),
                        definition.name(),
                        definition.effect(),
                        outcome.selector(),
                        preview(invocation, definition)),
                new CompletableFuture<>());
        synchronized (lock) {
            if (closed) {
                return ApprovalResponse.deny();
            }
            if (pending != null) {
                throw new IllegalStateException("同一连接只能等待一个审批");
            }
            pending = current;
        }

        try (CancellationToken.Registration ignored =
                     invocation.cancellationToken().onCancellation(
                             () -> resolveInternally(approvalId, ApprovalResponse.deny()))) {
            if (!current.decision().isDone()) {
                try {
                    requestSink.accept(current.request());
                } catch (RuntimeException failure) {
                    resolveInternally(approvalId, ApprovalResponse.deny());
                }
            }
            return current.decision().join();
        } finally {
            synchronized (lock) {
                if (pending == current) {
                    pending = null;
                }
            }
        }
    }

    /**
     * 使用终端返回的单次决定完成待决请求。
     *
     * @param approvalId 终端看到的审批 ID
     * @param decision 最终 Allow 或 Deny
     * @return ID 匹配且首次完成时为 {@code true}
     */
    boolean resolve(String approvalId, ApprovalResponse decision) {
        Objects.requireNonNull(approvalId, "approvalId 不能为空");
        Objects.requireNonNull(decision, "decision 不能为空");
        return resolveInternally(approvalId, decision);
    }

    Request pendingRequest() {
        synchronized (lock) {
            return pending == null ? null : pending.request();
        }
    }

    private boolean resolveInternally(
            String approvalId,
            ApprovalResponse decision) {
        synchronized (lock) {
            if (pending == null
                    || !pending.request().approvalId().equals(approvalId)) {
                return false;
            }
            return pending.decision().complete(decision);
        }
    }

    /**
     * 关闭连接并拒绝仍在等待的请求。
     */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            if (pending != null) {
                pending.decision().complete(ApprovalResponse.deny());
            }
        }
    }

    private static String requireId(String value) {
        Objects.requireNonNull(value, "approvalId 不能为空");
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("approvalId 为空或过长");
        }
        return value;
    }

    private static Preview preview(
            ToolInvocation invocation,
            ToolDefinition definition) {
        String name = definition.name();
        if ("run_command".equals(name)) {
            String command;
            try {
                command = invocation.call().arguments().string("command").orElse("");
            } catch (IllegalArgumentException exception) {
                return Preview.unavailable();
            }
            if (command.isBlank()
                    || command.codePointCount(0, command.length())
                    > LocalToolLimits.MAX_COMMAND_CHARACTERS
                    || command.indexOf('\0') >= 0) {
                return Preview.unavailable();
            }
            return new Preview(
                    "",
                    "execute",
                    0,
                    0,
                    command,
                    CommandShell.current().id(),
                    ".");
        }
        if (!"apply_patch".equals(name) && !"write_file".equals(name)) {
            return Preview.unavailable();
        }
        JsonPreviewArguments arguments = JsonPreviewArguments.from(invocation);
        String target = safeRelativePath(arguments.path());
        if (target.isEmpty()) {
            return Preview.unavailable();
        }
        String operation = "apply_patch".equals(name) ? "modify" : "create";
        return new Preview(
                target,
                operation,
                lineCount(arguments.oldText()),
                lineCount(arguments.newText()),
                "",
                "",
                "");
    }

    private static String safeRelativePath(String raw) {
        if (raw == null
                || raw.isBlank()
                || raw.length() > MAX_PREVIEW_PATH_CHARACTERS
                || raw.startsWith("/")
                || raw.startsWith("\\")
                || WINDOWS_DRIVE.matcher(raw).matches()) {
            return "";
        }
        String normalized = raw.replace('\\', '/');
        String[] segments = normalized.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment) || containsControl(segment)) {
                return "";
            }
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(segment);
        }
        return result.toString();
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\\R", -1).length;
    }

    /**
     * 可以进入 stdio 协议的脱敏审批摘要。
     *
     * @param approvalId 单次审批关联 ID
     * @param runId 所属 Run
     * @param ordinal Tool 序号
     * @param toolName 固定 Tool 名称
     * @param effect Tool 最高副作用
     * @param scope 可用于 Session Allow 的具体规范化范围
     * @param preview 只含相对路径、操作与行数的专用预览
     */
    record Request(
            String approvalId,
            RunId runId,
            int ordinal,
            String toolName,
            ToolEffect effect,
            io.github.liumaishenjian.ccjava.domain.PermissionSelector scope,
            Preview preview) {

        Request {
            requireId(approvalId);
            runId = Objects.requireNonNull(runId, "runId 不能为空");
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal 必须从 1 开始");
            }
            Objects.requireNonNull(toolName, "toolName 不能为空");
            effect = Objects.requireNonNull(effect, "effect 不能为空");
            scope = Objects.requireNonNull(scope, "scope 不能为空");
            preview = Objects.requireNonNull(preview, "preview 不能为空");
        }
    }

    /**
     * 允许进入 stdio 的文件变更摘要，不含文件正文或绝对路径。
     *
     * @param target Workspace-relative 目标；不可安全展示时为空
     * @param operation {@code modify}、{@code create} 或 {@code unavailable}
     * @param removedLines 预计删除行数
     * @param addedLines 预计新增行数
     * @param command 已批准的完整命令正文；文件操作时为空
     * @param shell 固定 Shell ID；文件操作时为空
     * @param workingDirectory Workspace-relative 工作目录
     */
    record Preview(
            String target,
            String operation,
            int removedLines,
            int addedLines,
            String command,
            String shell,
            String workingDirectory) {

        Preview {
            target = Objects.requireNonNull(target, "target 不能为空");
            operation = Objects.requireNonNull(operation, "operation 不能为空");
            command = Objects.requireNonNull(command, "command 不能为空");
            shell = Objects.requireNonNull(shell, "shell 不能为空");
            workingDirectory = Objects.requireNonNull(
                    workingDirectory, "workingDirectory 不能为空");
            if (target.length() > MAX_PREVIEW_PATH_CHARACTERS
                    || (!"modify".equals(operation)
                    && !"create".equals(operation)
                    && !"execute".equals(operation)
                    && !"unavailable".equals(operation))
                    || removedLines < 0
                    || addedLines < 0
                    || command.codePointCount(0, command.length())
                    > LocalToolLimits.MAX_COMMAND_CHARACTERS
                    || shell.length() > 64
                    || workingDirectory.length() > MAX_PREVIEW_PATH_CHARACTERS) {
                throw new IllegalArgumentException("审批预览字段无效");
            }
        }

        static Preview unavailable() {
            return new Preview("", "unavailable", 0, 0, "", "", "");
        }
    }

    private record JsonPreviewArguments(
            String path,
            String oldText,
            String newText) {

        private static JsonPreviewArguments from(ToolInvocation invocation) {
            var arguments = invocation.call().arguments();
            try {
                String name = invocation.call().name();
                return new JsonPreviewArguments(
                        arguments.string("path").orElse(""),
                        "apply_patch".equals(name)
                                ? arguments.string("oldText").orElse("") : "",
                        "apply_patch".equals(name)
                                ? arguments.string("newText").orElse("")
                                : arguments.string("content").orElse(""));
            } catch (IllegalArgumentException exception) {
                return new JsonPreviewArguments("", "", "");
            }
        }
    }

    private record Pending(
            Request request,
            CompletableFuture<ApprovalResponse> decision) {
    }
}
