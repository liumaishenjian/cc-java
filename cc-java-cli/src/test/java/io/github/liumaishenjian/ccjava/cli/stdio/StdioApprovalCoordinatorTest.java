package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StdioApprovalCoordinatorTest {

    @Test
    void matchingAllowOnceReleasesOnlyTheDisplayedRequest() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        AtomicReference<StdioApprovalCoordinator.Request> captured = new AtomicReference<>();
        StdioApprovalCoordinator coordinator = new StdioApprovalCoordinator(request -> {
            captured.set(request);
            requested.countDown();
        }, () -> "approval-1");
        PermissionSelector scope = PermissionSelector.toolWide("fake_write", io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN);

        CompletableFuture<ApprovalResponse> decision = CompletableFuture.supplyAsync(
                () -> coordinator.requestApproval(
                        invocation(new CancellationSource()), definition(), ask(scope)));
        assertThat(requested.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get()).isEqualTo(new StdioApprovalCoordinator.Request(
                "approval-1",
                new RunId("run-1"),
                1,
                "fake_write",
                ToolEffect.WRITE_WORKSPACE,
                scope,
                StdioApprovalCoordinator.Preview.unavailable()));
        assertThat(coordinator.resolve("other", ApprovalResponse.allowOnce())).isFalse();
        assertThat(decision).isNotDone();

        assertThat(coordinator.resolve("approval-1", ApprovalResponse.allowOnce())).isTrue();
        assertThat(decision.get(2, TimeUnit.SECONDS)).isEqualTo(ApprovalResponse.allowOnce());
        assertThat(coordinator.resolve("approval-1", ApprovalResponse.deny())).isFalse();
    }

    @Test
    void cancellationAndCloseFailClosedAndReleaseWaiters() throws Exception {
        CountDownLatch firstRequested = new CountDownLatch(1);
        CancellationSource cancellation = new CancellationSource();
        StdioApprovalCoordinator cancelledCoordinator =
                new StdioApprovalCoordinator(ignored -> firstRequested.countDown(), () -> "cancel");
        CompletableFuture<ApprovalResponse> cancelled = CompletableFuture.supplyAsync(
                () -> cancelledCoordinator.requestApproval(
                        invocation(cancellation), definition(),
                        ask(PermissionSelector.toolWide("fake_write", io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN))));
        assertThat(firstRequested.await(2, TimeUnit.SECONDS)).isTrue();
        cancellation.cancel();
        assertThat(cancelled.get(2, TimeUnit.SECONDS)).isEqualTo(ApprovalResponse.deny());

        CountDownLatch secondRequested = new CountDownLatch(1);
        StdioApprovalCoordinator closedCoordinator =
                new StdioApprovalCoordinator(ignored -> secondRequested.countDown(), () -> "close");
        CompletableFuture<ApprovalResponse> closed = CompletableFuture.supplyAsync(
                () -> closedCoordinator.requestApproval(
                        invocation(new CancellationSource()), definition(),
                        ask(PermissionSelector.toolWide("fake_write", io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN))));
        assertThat(secondRequested.await(2, TimeUnit.SECONDS)).isTrue();
        closedCoordinator.close();
        assertThat(closed.get(2, TimeUnit.SECONDS)).isEqualTo(ApprovalResponse.deny());
    }

    @Test
    void patchPreviewContainsOnlyRelativeTargetOperationAndLineCounts() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        AtomicReference<StdioApprovalCoordinator.Request> captured = new AtomicReference<>();
        StdioApprovalCoordinator coordinator = new StdioApprovalCoordinator(request -> {
            captured.set(request);
            requested.countDown();
        }, () -> "patch-approval");
        ToolInvocation invocation = new ToolInvocation(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                new ToolCall("call-1", "apply_patch", new JsonObject(java.util.Map.of(
                        "path", "src/main/App.java",
                        "oldText", "old\nblock",
                        "newText", "new\nblock\nextra"))),
                new CancellationSource().token());
        ToolDefinition definition = new ToolDefinition(
                "apply_patch",
                "Patch",
                "{\"type\":\"object\"}",
                ToolEffect.WRITE_WORKSPACE,
                ToolSource.BUILT_IN,
                false,
                Duration.ofSeconds(1),
                "text/plain",
                1024);
        PermissionSelector scope = new PermissionSelector("apply_patch", io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN, "src/main/App.java");

        CompletableFuture<ApprovalResponse> decision = CompletableFuture.supplyAsync(
                () -> coordinator.requestApproval(invocation, definition, ask(scope)));
        assertThat(requested.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(captured.get().preview()).isEqualTo(
                new StdioApprovalCoordinator.Preview(
                        "src/main/App.java", "modify", 2, 3, "", "", "", "", ""));
        assertThat(coordinator.resolve("patch-approval", ApprovalResponse.deny())).isTrue();
        assertThat(decision.get(2, TimeUnit.SECONDS)).isEqualTo(ApprovalResponse.deny());
    }

    @Test
    void webSearchPreviewContainsOnlyBoundedQueryAndFixedDestination() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        AtomicReference<StdioApprovalCoordinator.Request> captured = new AtomicReference<>();
        StdioApprovalCoordinator coordinator = new StdioApprovalCoordinator(request -> {
            captured.set(request);
            requested.countDown();
        }, () -> "network-approval");
        ToolInvocation invocation = new ToolInvocation(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                new ToolCall("call-web", "web_search", new JsonObject(java.util.Map.of(
                        "query", "明天杭州天气",
                        "result_limit", 5))),
                new CancellationSource().token());
        ToolDefinition definition = new ToolDefinition(
                "web_search",
                "Controlled web search",
                "{\"type\":\"object\"}",
                ToolEffect.NETWORK_OR_REMOTE,
                ToolSource.BUILT_IN,
                true,
                Duration.ofSeconds(10),
                "text/plain",
                64_000);
        PermissionSelector scope = PermissionSelector.toolWide("web_search", ToolSource.BUILT_IN);

        CompletableFuture<ApprovalResponse> decision = CompletableFuture.supplyAsync(
                () -> coordinator.requestApproval(invocation, definition, ask(scope)));
        assertThat(requested.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get().preview()).isEqualTo(
                StdioApprovalCoordinator.Preview.webSearch("明天杭州天气"));
        assertThat(captured.get().preview().toString())
                .doesNotContain("https://", "Authorization", "api-key");
        assertThat(coordinator.resolve("network-approval", ApprovalResponse.allowOnce())).isTrue();
        assertThat(decision.get(2, TimeUnit.SECONDS)).isEqualTo(ApprovalResponse.allowOnce());
    }

    private static PermissionOutcome ask(PermissionSelector selector) {
        return PermissionOutcome.of(
                PermissionDecision.ASK,
                PermissionReason.EFFECT_DEFAULT,
                selector);
    }

    private ToolInvocation invocation(CancellationSource cancellation) {
        return new ToolInvocation(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                new ToolCall("call-1", "fake_write", JsonObject.empty()),
                cancellation.token());
    }

    private ToolDefinition definition() {
        return new ToolDefinition(
                "fake_write",
                "Fake write without filesystem access",
                "{\"type\":\"object\"}",
                ToolEffect.WRITE_WORKSPACE,
                ToolSource.BUILT_IN,
                true,
                Duration.ofSeconds(1),
                "text/plain",
                1024);
    }
}
