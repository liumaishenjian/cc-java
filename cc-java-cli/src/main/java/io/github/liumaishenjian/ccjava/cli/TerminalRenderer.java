package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;
import java.io.PrintWriter;
import java.util.Objects;

/**
 * 把框架无关 Agent Event 转换为人类可读终端输出。
 *
 * <p>Assistant 文本写入 {@code assistantWriter}；状态写入 {@code statusWriter}。
 * Print 模式因此可以把纯文本输出管道化，同时把诊断留在 stderr。该渲染器不展示
 * Prompt、Tool 参数、Tool 输出或异常 cause，避免意外泄漏 Secret 与源码正文。</p>
 *
 * <p>所有来自模型、Tool、配置或异常边界的文本都按不可信输入处理。Assistant
 * 正文会移除终端控制序列，保留 LF 与水平制表，并把 CR/CRLF 规范为 LF；
 * 状态文本还会把换行和制表规范为单个空格，以维持一条状态只占一行。渲染器自身
 * 产生的 ANSI 颜色不经过该过滤，并且只会在 {@code ansiEnabled} 为真时写出。</p>
 *
 * <p>调用方必须按单个 Run 的事件顺序串行调用。本类型按模型回合跟踪 delta：
 * 最终回合已经流式输出时不重复聚合文本；早期 Tool 回合有 delta、最终回合没有
 * delta 时仍会回退到 {@link AgentRunResult#finalText()}。</p>
 *
 * @since 0.1.0
 */
public final class TerminalRenderer implements CliEventListener {

    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_RESET = "\u001B[0m";

    private final PrintWriter assistantWriter;
    private final PrintWriter statusWriter;
    private final boolean ansiEnabled;
    private Integer activeTurnNumber;
    private boolean currentTurnReceivedDelta;
    private boolean assistantTextEmitted;
    private boolean lastAssistantTextEndedWithNewline;
    private boolean pendingTurnBoundary;

    /**
     * 创建终端渲染器。
     *
     * @param assistantWriter Assistant 文本出口
     * @param statusWriter    状态与诊断出口
     * @param ansiEnabled     是否允许 ANSI
     */
    public TerminalRenderer(
            PrintWriter assistantWriter,
            PrintWriter statusWriter,
            boolean ansiEnabled) {
        this.assistantWriter = Objects.requireNonNull(
                assistantWriter,
                "assistantWriter 不能为空");
        this.statusWriter = Objects.requireNonNull(statusWriter, "statusWriter 不能为空");
        this.ansiEnabled = ansiEnabled;
    }

    /**
     * 重置单个 Run 的模型回合和文本渲染状态。
     */
    public void beginRun() {
        activeTurnNumber = null;
        currentTurnReceivedDelta = false;
        assistantTextEmitted = false;
        lastAssistantTextEndedWithNewline = false;
        pendingTurnBoundary = false;
    }

    private void activateModelTurn(int turnNumber) {
        if (activeTurnNumber != null && activeTurnNumber == turnNumber) {
            return;
        }
        activeTurnNumber = turnNumber;
        currentTurnReceivedDelta = false;
        pendingTurnBoundary = assistantTextEmitted;
    }

    private void renderTextDelta(ModelTextDelta delta) {
        activateModelTurn(delta.turnNumber());
        if (renderAssistantText(delta.text())) {
            currentTurnReceivedDelta = true;
        }
    }

    private boolean renderAssistantText(String text) {
        String safeText = sanitizeAssistantText(text);
        if (safeText.isEmpty()) {
            return false;
        }
        if (pendingTurnBoundary) {
            if (!lastAssistantTextEndedWithNewline) {
                assistantWriter.println();
            }
            pendingTurnBoundary = false;
        }
        assistantWriter.print(safeText);
        assistantWriter.flush();
        assistantTextEmitted = true;
        lastAssistantTextEndedWithNewline = safeText.endsWith("\n");
        return true;
    }

    @Override
    public void onAgentEvent(AgentEventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope 不能为空");
        Object event = envelope.event();
        if (event instanceof ModelTextDelta delta) {
            renderTextDelta(delta);
        } else if (event instanceof LifecycleEvent.ModelTurnStarted started) {
            activateModelTurn(started.turnNumber());
            status("model", "turn " + started.turnNumber() + " started", ANSI_CYAN);
        } else if (event instanceof LifecycleEvent.BeforeTool beforeTool) {
            status("tool", beforeTool.call().name() + " requested", ANSI_YELLOW);
        } else if (event instanceof LifecycleEvent.PermissionRequested permission) {
            status("permission", permission.call().name() + " requested", ANSI_YELLOW);
        } else if (event instanceof LifecycleEvent.PermissionDecided permission) {
            status(
                    "permission",
                    permission.call().name() + " " + permission.decision().name().toLowerCase(),
                    ANSI_YELLOW);
        } else if (event instanceof LifecycleEvent.AfterTool afterTool) {
            status(
                    "tool",
                    afterTool.result().toolName()
                            + " "
                            + afterTool.result().status().name().toLowerCase(),
                    ANSI_YELLOW);
        }
    }

    /**
     * 渲染 Run 终态，并在 Provider 没有 delta 时回退到聚合最终文本。
     *
     * @param result Runtime 唯一终态
     */
    public void completeRun(AgentRunResult result) {
        Objects.requireNonNull(result, "result 不能为空");
        if (result.modelTurns() > 0) {
            activateModelTurn(result.modelTurns());
        }
        if (!currentTurnReceivedDelta && result.finalText().isPresent()) {
            renderAssistantText(result.finalText().orElseThrow());
        }
        if (assistantTextEmitted && !lastAssistantTextEndedWithNewline) {
            assistantWriter.println();
            lastAssistantTextEndedWithNewline = true;
        }
        assistantWriter.flush();

        if (result.stopReason()
                != io.github.liumaishenjian.ccjava.domain.StopReason.COMPLETED) {
            status(
                    "run",
                    result.stopReason().name().toLowerCase(),
                    result.stopReason()
                                    == io.github.liumaishenjian.ccjava.domain.StopReason.USER_CANCELLED
                            ? ANSI_YELLOW
                            : ANSI_RED);
        }
    }

    /**
     * 输出不含敏感内容的启动配置。
     *
     * @param configuration 最终配置
     */
    public void renderConfiguration(CliConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration 不能为空");
        status(
                "session",
                "workspace="
                        + configuration.workspace().value()
                        + " ("
                        + configuration.workspace().source().name().toLowerCase()
                        + ")",
                ANSI_CYAN);
        status(
                "session",
                "provider="
                        + configuration.providerId()
                        + ", model="
                        + configuration.model().value()
                        + " ("
                        + configuration.model().source().name().toLowerCase()
                        + ")",
                ANSI_CYAN);
        status(
                "session",
                "ollama-base-url="
                        + configuration.ollamaBaseUrl().value()
                        + " ("
                        + configuration.ollamaBaseUrl().source().name().toLowerCase()
                        + "), max-output-tokens="
                        + configuration.maxOutputTokens().value()
                        + " ("
                        + configuration.maxOutputTokens().source().name().toLowerCase()
                        + ")",
                ANSI_CYAN);
        if (configuration.secretStatus().required()
                || configuration.secretStatus().present()) {
            status(
                    "session",
                    configuration.secretStatus().environmentVariable()
                            + "="
                            + configuration.secretStatus().displayValue(),
                    ANSI_CYAN);
        }
    }

    /**
     * 输出一条安全诊断。
     *
     * <p>调用方仍负责避免传入 Secret；本方法负责阻止控制序列和换行伪造状态前缀。</p>
     *
     * @param message 不含 Secret、但仍按不可信输入处理的诊断
     */
    public void error(String message) {
        Objects.requireNonNull(message, "message 不能为空");
        status("error", message, ANSI_RED);
    }

    private void status(String category, String message, String color) {
        String safeCategory = sanitizeStatusText(category);
        String safeMessage = sanitizeStatusText(message);
        if (ansiEnabled) {
            statusWriter.print(color);
        }
        statusWriter.print("[");
        statusWriter.print(safeCategory);
        statusWriter.print("] ");
        statusWriter.print(safeMessage);
        if (ansiEnabled) {
            statusWriter.print(ANSI_RESET);
        }
        statusWriter.println();
        statusWriter.flush();
    }

    private static String sanitizeAssistantText(String text) {
        return sanitizeTerminalText(text, true);
    }

    private static String sanitizeStatusText(String text) {
        return sanitizeTerminalText(text, false);
    }

    /**
     * 移除能够改变终端状态的控制序列，并按输出通道规范化空白。
     *
     * <p>除了常见的 ESC CSI/OSC，还处理其 C1 单字符形式以及 DCS、SOS、PM、APC
     * 字符串控制。未终止的字符串控制会丢弃到输入末尾；宁可少显示一段不可信文本，
     * 也不能让截断序列逃逸到真实终端。</p>
     */
    private static String sanitizeTerminalText(String text, boolean multiline) {
        Objects.requireNonNull(text, "text 不能为空");
        StringBuilder safe = new StringBuilder(text.length());
        boolean pendingStatusSeparator = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\u001B') {
                index = skipEscapeSequence(text, index);
                continue;
            }
            if (character == '\u009B') {
                index = skipControlSequence(text, index + 1);
                continue;
            }
            if (character == '\u009D') {
                index = skipStringControl(text, index + 1, true);
                continue;
            }
            if (character == '\u0090'
                    || character == '\u0098'
                    || character == '\u009E'
                    || character == '\u009F') {
                index = skipStringControl(text, index + 1, false);
                continue;
            }

            if (multiline && character == '\r') {
                if (index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                safe.append('\n');
                continue;
            }
            if (multiline && (character == '\n' || character == '\t')) {
                safe.append(character);
                continue;
            }
            if (!multiline
                    && (character == '\r'
                            || character == '\n'
                            || character == '\t'
                            || character == '\u2028'
                            || character == '\u2029')) {
                pendingStatusSeparator = safe.length() > 0;
                continue;
            }
            if (isTerminalControl(character)) {
                continue;
            }

            if (pendingStatusSeparator) {
                if (!Character.isWhitespace(safe.charAt(safe.length() - 1))
                        && !Character.isWhitespace(character)) {
                    safe.append(' ');
                }
                pendingStatusSeparator = false;
            }
            safe.append(character);
        }
        return safe.toString();
    }

    private static boolean isTerminalControl(char character) {
        return character < '\u0020'
                || character == '\u007F'
                || (character >= '\u0080' && character <= '\u009F');
    }

    private static int skipEscapeSequence(String text, int escapeIndex) {
        int introducerIndex = escapeIndex + 1;
        if (introducerIndex >= text.length()) {
            return escapeIndex;
        }
        return switch (text.charAt(introducerIndex)) {
            case '[' -> skipControlSequence(text, introducerIndex + 1);
            case ']' -> skipStringControl(text, introducerIndex + 1, true);
            case 'P', 'X', '^', '_' ->
                    skipStringControl(text, introducerIndex + 1, false);
            default -> skipShortEscapeSequence(text, introducerIndex);
        };
    }

    private static int skipControlSequence(String text, int contentIndex) {
        for (int index = contentIndex; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character >= '\u0040' && character <= '\u007E') {
                return index;
            }
        }
        return text.length() - 1;
    }

    private static int skipStringControl(
            String text,
            int contentIndex,
            boolean bellTerminates) {
        for (int index = contentIndex; index < text.length(); index++) {
            char character = text.charAt(index);
            if ((bellTerminates && character == '\u0007') || character == '\u009C') {
                return index;
            }
            if (character == '\u001B'
                    && index + 1 < text.length()
                    && text.charAt(index + 1) == '\\') {
                return index + 1;
            }
        }
        return text.length() - 1;
    }

    private static int skipShortEscapeSequence(String text, int contentIndex) {
        int index = contentIndex;
        while (index < text.length()
                && text.charAt(index) >= '\u0020'
                && text.charAt(index) <= '\u002F') {
            index++;
        }
        if (index < text.length()
                && text.charAt(index) >= '\u0030'
                && text.charAt(index) <= '\u007E') {
            return index;
        }
        return contentIndex - 1;
    }
}
