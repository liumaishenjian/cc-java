package io.github.liumaishenjian.ccjava.cli.stdio;

import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.node.ObjectNode;

/**
 * 定义 S02 Java Headless 与终端 Client 之间的内部 stdio v0 契约。
 *
 * <p>命令与事件分别在各自方向维护从 1 开始的连接级序号。Java 是 Session、Run
 * 和终态的权威；Client 不得根据已经看到的文本增量推断完成或取消。</p>
 *
 * <p>该协议只用于 S02 Spike，没有跨版本兼容承诺。稳定机器协议仍属于 S14。</p>
 *
 * @since 0.1.0
 */
public final class StdioProtocol {

    /** 当前实验协议主版本。 */
    public static final int VERSION = 0;

    /** 无法从坏消息中恢复请求标识时使用的保留值。 */
    public static final String UNAVAILABLE_REQUEST_ID = "unavailable";

    private StdioProtocol() {
    }

    /**
     * Client 发给 Java Headless 的单条命令。
     *
     * @param version 协议主版本
     * @param type 命令类型
     * @param requestId Client 生成的请求关联标识
     * @param sessionId 已初始化 Session；初始化命令为空
     * @param runId 活动 Run；非 Run 命令为空
     * @param sequence Client 到 Java 方向的连接级序号
     * @param payload 命令参数对象；所有权转移给该消息
     */
    public record Command(
            int version,
            String type,
            String requestId,
            Optional<String> sessionId,
            Optional<String> runId,
            long sequence,
            ObjectNode payload) {

        /**
         * 校验命令的通用信封字段。
         */
        public Command {
            type = requireText(type, "type");
            requestId = requireText(requestId, "requestId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
            runId = Objects.requireNonNull(runId, "runId 不能为空");
            if (sequence < 1) {
                throw new IllegalArgumentException("sequence 必须从 1 开始");
            }
            payload = Objects.requireNonNull(payload, "payload 不能为空").deepCopy();
        }
    }

    /**
     * Java Headless 发给 Client 的单条事件。
     *
     * @param version 协议主版本
     * @param type 事件类型
     * @param requestId 触发该事件的请求关联标识
     * @param sessionId 事件所属 Session
     * @param runId 事件所属 Run
     * @param sequence Java 到 Client 方向的连接级序号
     * @param payload 事件数据对象；所有权转移给该消息
     */
    public record Event(
            int version,
            String type,
            String requestId,
            Optional<String> sessionId,
            Optional<String> runId,
            long sequence,
            ObjectNode payload) {

        /**
         * 校验事件的通用信封字段。
         */
        public Event {
            type = requireText(type, "type");
            requestId = requireText(requestId, "requestId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
            runId = Objects.requireNonNull(runId, "runId 不能为空");
            if (sequence < 1) {
                throw new IllegalArgumentException("sequence 必须从 1 开始");
            }
            payload = Objects.requireNonNull(payload, "payload 不能为空").deepCopy();
        }
    }

    /**
     * 把已通过传输层校验的命令交给 Application Session。
     *
     * <p>实现可以异步发布后续事件，但 {@link #close()} 返回前必须停止后台任务，
     * 从而保证事件 Writer 可以安全排空并退出。</p>
     */
    public interface CommandHandler extends AutoCloseable {

        /**
         * 处理一条序号有效的命令。
         *
         * @param command Client 命令
         * @param events 线程安全的单 Writer 事件入口
         * @return 是否继续读取连接
         * @throws StdioProtocolException 命令与当前 Application 状态不兼容时
         */
        Disposition handle(Command command, EventEmitter events)
                throws StdioProtocolException;

        /**
         * 停止活动任务并释放 Application 资源。
         *
         * @throws Exception 资源无法释放时
         */
        @Override
        void close() throws Exception;
    }

    /**
     * 供 Application 发布事件的线程安全边界。
     */
    @FunctionalInterface
    public interface EventEmitter {

        /**
         * 发布一条待分配输出序号的事件。
         *
         * @param type 事件类型
         * @param requestId 请求关联标识
         * @param sessionId Session 关联
         * @param runId Run 关联
         * @param payload 事件数据
         */
        void emit(
                String type,
                String requestId,
                Optional<String> sessionId,
                Optional<String> runId,
                ObjectNode payload);
    }

    /**
     * 命令处理后的连接动作。
     */
    public enum Disposition {
        /** 继续读取下一条命令。 */
        CONTINUE,
        /** 完成当前命令后有序关闭连接。 */
        SHUTDOWN
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
        return value;
    }
}
