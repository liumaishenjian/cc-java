package io.github.liumaishenjian.ccjava.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class CcJavaSdkClientTest {

    @Test
    void preservesCompleteRequestAndDelegatesLifecycle() {
        RecordingApplication application = new RecordingApplication();
        AgentRunRequest request = new AgentRunRequest(
                new UserMessage("task"), new AgentLimits(3, 4, Duration.ofSeconds(2)));
        try (CcJavaSdkClient client = new CcJavaSdkClient(application)) {
            AgentRunResult result = client.run(request, envelope -> { });
            assertThat(result.runId()).isEqualTo(new RunId("run-1"));
            assertThat(application.request).isEqualTo(request);
            assertThat(client.cancel(new RunId("run-1"))).isTrue();
            assertThat(client.drain(Duration.ofMillis(10))).isTrue();
        }
        assertThat(application.closed).isTrue();
    }

    @Test
    void closedClientRejectsNewRun() {
        RecordingApplication application = new RecordingApplication();
        CcJavaSdkClient client = new CcJavaSdkClient(application);
        client.close();
        assertThatThrownBy(() -> client.run(AgentRunRequest.of("task"), envelope -> { }))
                .isInstanceOf(IllegalStateException.class);
    }

    private static final class RecordingApplication implements AgentApplicationService {
        private AgentRunRequest request;
        private boolean draining;
        private boolean closed;

        @Override
        public AgentRunResult run(AgentRunRequest request, AgentEventSink events) {
            if (draining) {
                throw new IllegalStateException("draining");
            }
            this.request = request;
            return AgentRunResult.completed(
                    new SessionId("session-1"), new RunId("run-1"), "done", 1, 0);
        }

        @Override
        public boolean cancel(RunId runId) {
            return runId.equals(new RunId("run-1"));
        }

        @Override
        public void beginDrain() {
            draining = true;
        }

        @Override
        public boolean awaitTermination(Duration timeout) {
            return true;
        }

        @Override
        public Optional<RunId> activeRun() {
            return Optional.empty();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
