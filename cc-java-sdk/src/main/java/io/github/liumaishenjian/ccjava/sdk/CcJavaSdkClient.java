package io.github.liumaishenjian.ccjava.sdk;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.RunId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可嵌入 Java SDK façade；不持有第二套 Runtime 或协议状态机。
 *
 * <p>生命周期操作全部委托共享 {@link AgentApplicationService}，因此 SDK 与 CLI/Daemon
 * 观察相同的 Run identity、取消、drain 和唯一终态。</p>
 *
 * @since 0.1.0
 */
public final class CcJavaSdkClient implements AutoCloseable {
    private final AgentApplicationService application;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建独占 façade，但不取得底层 Runtime 之外的新状态所有权。
     *
     * @param application 共享 Application Service
     */
    public CcJavaSdkClient(AgentApplicationService application) {
        this.application = Objects.requireNonNull(application, "application 不能为空");
    }

    /**
     * 使用调用方事件 sink 同步执行一次完整请求。
     *
     * @param request 用户消息与限制
     * @param events 有序事件 sink
     * @return Runtime 唯一终态
     */
    public AgentRunResult run(AgentRunRequest request, AgentEventSink events) {
        requireOpen();
        return application.run(
                Objects.requireNonNull(request, "request 不能为空"),
                Objects.requireNonNull(events, "events 不能为空"));
    }

    /**
     * 请求取消指定 Run。
     *
     * @param runId 目标 Run identity
     * @return 是否找到并接受取消
     */
    public boolean cancel(RunId runId) {
        requireOpen();
        return application.cancel(Objects.requireNonNull(runId, "runId 不能为空"));
    }

    /**
     * 查询当前活动 Run。
     *
     * @return 活动 Run identity；idle 时为空
     */
    public Optional<RunId> activeRun() {
        requireOpen();
        return application.activeRun();
    }

    /**
     * 查询与同一 Application Service 绑定的控制面。
     *
     * @return production control API；未装配时为空
     */
    public Optional<AgentControlApi> control() {
        requireOpen();
        return application.control();
    }

    /**
     * 开始 drain 并等待活动 Run 收敛。
     *
     * @param timeout 最大等待时间
     * @return 是否在期限内收敛
     */
    public boolean drain(Duration timeout) {
        requireOpen();
        application.beginDrain();
        return application.awaitTermination(Objects.requireNonNull(timeout, "timeout 不能为空"));
    }

    /** 关闭 façade 并释放其 Application Service。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            application.close();
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("SDK Client 已关闭");
        }
    }
}
