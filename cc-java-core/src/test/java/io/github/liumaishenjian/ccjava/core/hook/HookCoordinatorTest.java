package io.github.liumaishenjian.ccjava.core.hook;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.hook.HookDisposition;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionResult;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionStatus;
import io.github.liumaishenjian.ccjava.domain.hook.HookFailurePolicy;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import io.github.liumaishenjian.ccjava.domain.hook.HookMatcher;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 验证 S09 Hook 的匹配、并发批次、聚合优先级、失败策略和取消边界。
 *
 * @since 0.1.0
 */
class HookCoordinatorTest {

    private static final HookInvocation PRE_TOOL = new HookInvocation(
            HookEventKind.PRE_TOOL,
            new SessionId("session-1"),
            Optional.of(new RunId("run-1")),
            "run_command",
            new JsonObject(Map.of("callId", "call-1", "toolName", "run_command")));

    @Test
    void aggregatesInBindingOrderAndDenyWinsOverAllow() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            HookCoordinator coordinator = new HookCoordinator(
                    List.of(
                            binding("allow", 20, HookDisposition.ALLOW, "allowed", "first"),
                            binding("block", 10, HookDisposition.BLOCK, "blocked", "second")),
                    executor,
                    Duration.ofSeconds(1));

            var result = coordinator.evaluate(PRE_TOOL, CancellationToken.none());

            assertThat(result.disposition()).isEqualTo(HookDisposition.BLOCK);
            assertThat(result.blockingReason()).contains("blocked");
            assertThat(result.executions()).extracting("handlerId")
                    .containsExactly("block", "allow");
            assertThat(result.additionalContext()).contains("second\nfirst");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void runsMatchingHandlersConcurrentlyButReturnsStableOrder() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(2);
        try {
            HookCoordinator coordinator = new HookCoordinator(
                    List.of(
                            new HookBinding(
                                    "first",
                                    HookMatcher.event(HookEventKind.PRE_TOOL),
                                    (invocation, token) -> {
                                        started.countDown();
                                        await(started);
                                        return HookExecutionResult.continued("first");
                                    },
                                    HookFailurePolicy.FAIL_OPEN,
                                    true,
                                    0),
                            new HookBinding(
                                    "second",
                                    HookMatcher.event(HookEventKind.PRE_TOOL),
                                    (invocation, token) -> {
                                        started.countDown();
                                        await(started);
                                        return HookExecutionResult.continued("ignored");
                                    },
                                    HookFailurePolicy.FAIL_OPEN,
                                    true,
                                    1)),
                    executor,
                    Duration.ofSeconds(1));

            var result = coordinator.evaluate(PRE_TOOL, CancellationToken.none());

            assertThat(result.executions()).extracting("handlerId")
                    .containsExactly("first", "second");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutUsesFailClosedForPreToolAndDoesNotLeakFuture() {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            HookCoordinator coordinator = new HookCoordinator(
                    List.of(new HookBinding(
                            "slow",
                            HookMatcher.event(HookEventKind.PRE_TOOL),
                            (invocation, token) -> {
                                try {
                                    Thread.sleep(250);
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                }
                                return HookExecutionResult.continued("slow");
                            },
                            HookFailurePolicy.FAIL_CLOSED,
                            true,
                            0)),
                    executor,
                    Duration.ofMillis(20));

            var result = coordinator.evaluate(PRE_TOOL, CancellationToken.none());

            assertThat(result.disposition()).isEqualTo(HookDisposition.BLOCK);
            assertThat(result.executions().getFirst().status())
                    .isEqualTo(HookExecutionStatus.TIMED_OUT);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void untrustedHandlerIsNeverInvoked() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger invocations = new AtomicInteger();
        try {
            HookCoordinator coordinator = new HookCoordinator(
                    List.of(new HookBinding(
                            "untrusted",
                            HookMatcher.event(HookEventKind.PRE_TOOL),
                            (invocation, token) -> {
                                invocations.incrementAndGet();
                                return new HookExecutionResult(
                                        "untrusted",
                                        HookDisposition.ALLOW,
                                        HookExecutionStatus.COMPLETED,
                                        Optional.empty(),
                                        Optional.empty());
                            },
                            HookFailurePolicy.FAIL_CLOSED,
                            false,
                            0)),
                    executor,
                    Duration.ofSeconds(1));

            var result = coordinator.evaluate(PRE_TOOL, CancellationToken.none());

            assertThat(invocations).hasValue(0);
            assertThat(result.disposition()).isEqualTo(HookDisposition.BLOCK);
            assertThat(result.executions().getFirst().status())
                    .isEqualTo(HookExecutionStatus.SKIPPED_UNTRUSTED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nonBlockingPostToolCannotStopThePipeline() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            HookCoordinator coordinator = new HookCoordinator(
                    List.of(new HookBinding(
                            "post",
                            HookMatcher.event(HookEventKind.POST_TOOL),
                            (invocation, token) -> new HookExecutionResult(
                                    "post",
                                    HookDisposition.BLOCK,
                                    HookExecutionStatus.COMPLETED,
                                    Optional.of("must not block"),
                                    Optional.of("feedback")),
                            HookFailurePolicy.FAIL_OPEN,
                            true,
                            0)),
                    executor,
                    Duration.ofSeconds(1));
            HookInvocation postTool = new HookInvocation(
                    HookEventKind.POST_TOOL,
                    PRE_TOOL.sessionId(),
                    PRE_TOOL.runId(),
                    PRE_TOOL.subject(),
                    PRE_TOOL.data());

            var result = coordinator.evaluate(postTool, CancellationToken.none());

            assertThat(result.disposition()).isEqualTo(HookDisposition.CONTINUE);
            assertThat(result.blockingReason()).isEmpty();
            assertThat(result.additionalContext()).contains("feedback");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationPreventsHandlerExecution() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger invocations = new AtomicInteger();
        try {
            HookCoordinator coordinator = new HookCoordinator(
                    List.of(binding(
                            "cancelled",
                            0,
                            HookDisposition.CONTINUE,
                            "cancelled",
                            "")),
                    executor,
                    Duration.ofSeconds(1));
            CancellationSource source = new CancellationSource();
            source.cancel();

            var result = coordinator.evaluate(PRE_TOOL, source.token());

            assertThat(invocations).hasValue(0);
            assertThat(result.executions().getFirst().status())
                    .isEqualTo(HookExecutionStatus.CANCELLED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nonCompletedHandlerResultStillUsesBindingFailurePolicy() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            HookCoordinator coordinator = new HookCoordinator(
                    List.of(new HookBinding(
                            "invalid",
                            HookMatcher.event(HookEventKind.PRE_TOOL),
                            (invocation, token) -> new HookExecutionResult(
                                    "invalid",
                                    HookDisposition.ALLOW,
                                    HookExecutionStatus.INVALID_OUTPUT,
                                    Optional.of("bad response"),
                                    Optional.empty()),
                            HookFailurePolicy.FAIL_CLOSED,
                            true,
                            0)),
                    executor,
                    Duration.ofSeconds(1));

            var result = coordinator.evaluate(PRE_TOOL, CancellationToken.none());

            assertThat(result.disposition()).isEqualTo(HookDisposition.BLOCK);
            assertThat(result.executions().getFirst().status())
                    .isEqualTo(HookExecutionStatus.INVALID_OUTPUT);
        } finally {
            executor.shutdownNow();
        }
    }

    private static HookBinding binding(
            String id,
            int order,
            HookDisposition disposition,
            String reason,
            String context) {
        return new HookBinding(
                id,
                HookMatcher.event(HookEventKind.PRE_TOOL),
                (invocation, token) -> new HookExecutionResult(
                        "handler-result-id",
                        disposition,
                        HookExecutionStatus.COMPLETED,
                        reason.isEmpty() ? Optional.empty() : Optional.of(reason),
                        context.isEmpty() ? Optional.empty() : Optional.of(context)),
                HookFailurePolicy.FAIL_OPEN,
                true,
                order);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
