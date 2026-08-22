package io.github.liumaishenjian.ccjava.cli.stdio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.node.ObjectNode;

/**
 * 在当前进程的 stdin/stdout 上驱动内部 stdio v0 连接。
 *
 * <p>Server 只处理传输级校验、命令序号和错误转换。Session/Run 状态由注入的
 * {@link StdioProtocol.CommandHandler} 拥有，未来真实 Handler 必须把 Run 交给
 * {@code AgentRuntime}，不能在本类中复制 Agent Loop。</p>
 *
 * <p>输入 Reader 与 Application 的异步 Run 线程分离，因此 Run 活动时仍能接收
 * {@code run.cancel} 与 {@code approval.resolve}。所有输出都经过
 * {@link QueuedStdioEventEmitter}，stdout
 * 不允许出现日志或普通文本。</p>
 *
 * @since 0.1.0
 */
public final class StdioProtocolServer {

    /** Spike 默认单行最大 64 KiB。 */
    public static final int DEFAULT_MAX_LINE_BYTES = 64 * 1024;

    /** Spike 默认最多积压 256 个事件。 */
    public static final int DEFAULT_EVENT_QUEUE_CAPACITY = 256;

    /** Spike 默认单次等待队列/Writer 的上限。 */
    public static final Duration DEFAULT_QUEUE_TIMEOUT = Duration.ofSeconds(1);

    private final BoundedUtf8LineReader reader;
    private final StdioProtocolCodec codec;
    private final StdioProtocol.CommandHandler handler;
    private final QueuedStdioEventEmitter events;
    private long expectedCommandSequence = 1;

    /**
     * 使用 Spike 默认上限创建 Server。
     *
     * @param input Java 子进程 stdin
     * @param output Java 子进程 stdout
     * @param handler Application 命令处理器
     */
    public StdioProtocolServer(
            InputStream input,
            OutputStream output,
            StdioProtocol.CommandHandler handler) {
        this(
                input,
                output,
                handler,
                DEFAULT_MAX_LINE_BYTES,
                DEFAULT_EVENT_QUEUE_CAPACITY,
                DEFAULT_QUEUE_TIMEOUT);
    }

    /**
     * 使用显式资源上限创建 Server，供故障测试和后续配置装配使用。
     *
     * @param input Java 子进程 stdin
     * @param output Java 子进程 stdout
     * @param handler Application 命令处理器
     * @param maxLineBytes 单行字节上限
     * @param eventQueueCapacity 事件队列容量
     * @param queueTimeout 发布与关闭等待上限
     */
    public StdioProtocolServer(
            InputStream input,
            OutputStream output,
            StdioProtocol.CommandHandler handler,
            int maxLineBytes,
            int eventQueueCapacity,
            Duration queueTimeout) {
        codec = new StdioProtocolCodec();
        reader = new BoundedUtf8LineReader(
                Objects.requireNonNull(input, "input 不能为空"),
                maxLineBytes);
        this.handler = Objects.requireNonNull(handler, "handler 不能为空");
        events = new QueuedStdioEventEmitter(
                codec,
                Objects.requireNonNull(output, "output 不能为空"),
                eventQueueCapacity,
                queueTimeout);
    }

    /**
     * 在调用线程持续读取命令，直到 EOF、shutdown 或内部失败。
     *
     * @return 可用于进程退出码映射的连接终止原因
     * @throws IOException stdin 读取失败时
     */
    public ExitReason run() throws IOException {
        ExitReason reason = ExitReason.EOF;
        try {
            while (true) {
                String line;
                try {
                    line = reader.readLine();
                } catch (StdioProtocolException exception) {
                    emitError(exception);
                    continue;
                }
                if (line == null) {
                    reason = ExitReason.EOF;
                    break;
                }

                StdioProtocol.Command command;
                try {
                    command = codec.decodeCommand(line);
                } catch (StdioProtocolException exception) {
                    emitError(exception);
                    continue;
                }

                if (command.sequence() != expectedCommandSequence) {
                    StdioProtocolException invalidSequence = new StdioProtocolException(
                            "INVALID_SEQUENCE",
                            command.requestId(),
                            "命令 sequence 与连接期望值不一致");
                    emitRunCommandRejection(command, invalidSequence);
                    emitError(invalidSequence);
                    continue;
                }
                expectedCommandSequence++;

                try {
                    StdioProtocol.Disposition disposition =
                            Objects.requireNonNull(
                                    handler.handle(command, events),
                                    "Handler disposition 不能为空");
                    if (disposition == StdioProtocol.Disposition.SHUTDOWN) {
                        reason = ExitReason.SHUTDOWN;
                        break;
                    }
                } catch (StdioProtocolException exception) {
                    emitRunCommandRejection(command, exception);
                    emitError(exception);
                } catch (RuntimeStdioCommandHandler.AcceptedRunTransportException outcomeUnknown) {
                    // acceptance write 已经 outcome-unknown；再写 rejected 会制造同 request 双 disposition。
                    reason = ExitReason.INTERNAL_ERROR;
                    break;
                } catch (RuntimeException exception) {
                    StdioProtocolException internal = new StdioProtocolException(
                            "INTERNAL_ERROR",
                            command.requestId(),
                            "Application 命令处理失败");
                    emitRunCommandRejection(command, internal);
                    emitError(internal);
                    reason = ExitReason.INTERNAL_ERROR;
                    break;
                }
            }
        } finally {
            try {
                closeHandler();
            } finally {
                events.close();
            }
        }
        return reason;
    }

    private void closeHandler() {
        try {
            handler.close();
        } catch (Exception exception) {
            emitError(new StdioProtocolException(
                    "INTERNAL_ERROR",
                    StdioProtocol.UNAVAILABLE_REQUEST_ID,
                    "Application 资源清理失败"));
        }
    }

    /**
     * 已完成解码的 Run-producing 命令即使被应用层拒绝，也必须得到确定的 correlated disposition。
     *
     * <p>{@code protocol.error} 继续承担安全诊断，但 Client 不再依靠未来是否出现
     * {@code run.started} 猜测命令是否被 Java 接受。</p>
     */
    private void emitRunCommandRejection(
            StdioProtocol.Command command,
            StdioProtocolException exception) {
        String commandType = runProducingCommandType(command);
        if (commandType == null) {
            return;
        }
        ObjectNode payload = codec.objectNode();
        payload.put("commandType", commandType);
        payload.put("disposition", "rejected");
        payload.put("code", exception.code());
        events.emit("run.command.result", exception.requestId(), command.sessionId(),
                Optional.empty(), payload);
    }

    private String runProducingCommandType(StdioProtocol.Command command) {
        return switch (command.type()) {
            case "run.start", "input.begin", "input.chunk", "input.commit" -> "run.start";
            case "plan.start" -> "plan.start";
            case "skill.invoke" -> "skill.invoke";
            case "plan.review.resolve" -> planReviewCreatesRun(command) ? "plan.review.resolve" : null;
            default -> null;
        };
    }

    private boolean planReviewCreatesRun(StdioProtocol.Command command) {
        var decision = command.payload().get("decision");
        if (decision == null || !decision.isString()) {
            return true;
        }
        return switch (decision.stringValue()) {
            case "REJECT" -> false;
            case "CONTINUE_PLANNING" -> {
                var feedback = command.payload().get("feedback");
                yield feedback == null || !feedback.isString() || !feedback.stringValue().isBlank();
            }
            default -> true;
        };
    }

    private void emitError(StdioProtocolException exception) {
        ObjectNode payload = codec.objectNode();
        payload.put("code", exception.code());
        payload.put("message", exception.getMessage());
        events.emit(
                "protocol.error",
                exception.requestId(),
                Optional.empty(),
                Optional.empty(),
                payload);
    }

    /**
     * stdio 连接停止原因。
     */
    public enum ExitReason {
        /** Client 关闭 stdin。 */
        EOF,
        /** Client 发送有序 shutdown。 */
        SHUTDOWN,
        /** Application 出现未预期内部失败。 */
        INTERNAL_ERROR
    }
}
