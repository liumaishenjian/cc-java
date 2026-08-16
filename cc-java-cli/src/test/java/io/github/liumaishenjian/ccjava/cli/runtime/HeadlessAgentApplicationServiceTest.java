package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HeadlessAgentApplicationServiceTest {

    @Test
    void closeForceReleasesUncooperativeModelWorkerAfterBoundedDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelGateway uncooperative = request -> {
            entered.countDown();
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    // 故意忽略取消/中断，证伪首次 close 超时后的可重试资源状态。
                }
            }
            return ModelTurn.text("done");
        };
        HeadlessRuntimeSession session = new HeadlessRuntimeSession(uncooperative, AgentEventSink.noop());
        session.open();
        HeadlessAgentApplicationService application = new HeadlessAgentApplicationService(session);
        AgentRunRequest request = new AgentRunRequest(
                new UserMessage("test"), AgentLimits.DEFAULT, Optional.empty());
        Thread run = Thread.startVirtualThread(() -> application.run(request, AgentEventSink.noop()));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        application.close();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(java.time.Duration.ofSeconds(6));
        assertThat(application.activeRun()).isEmpty();

        release.countDown();
        run.join(TimeUnit.SECONDS.toMillis(2));
        assertThat(run.isAlive()).isFalse();
        application.close();
        assertThat(application.activeRun()).isEmpty();
        assertThatThrownBy(() -> application.run(request, AgentEventSink.noop()))
                .isInstanceOf(IllegalStateException.class);
    }
}
