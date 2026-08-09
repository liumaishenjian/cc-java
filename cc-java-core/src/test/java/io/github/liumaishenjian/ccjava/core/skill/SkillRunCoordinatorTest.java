package io.github.liumaishenjian.ccjava.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SkillContextMessage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillContentSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationKind;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationPolicy;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationRequest;
import io.github.liumaishenjian.ccjava.domain.skill.SkillSource;
import io.github.liumaishenjian.ccjava.domain.skill.SkillToolRestriction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SkillRunCoordinatorTest {
    private static final String DIGEST = "a".repeat(64);
    private static final String SNAPSHOT = "b".repeat(64);

    @Test
    void explicitAndModelEntriesShareInvokerAndProjectInStableOrderWhileNarrowingTools() throws Exception {
        SkillId first = new SkillId("first");
        SkillId second = new SkillId("second");
        var catalog = catalog(List.of(
                descriptor(first, SkillToolRestriction.declared(List.of("read", "test"))),
                descriptor(second, SkillToolRestriction.declared(List.of("test")))));
        var coordinator = coordinator(catalog);
        RunId runId = new RunId("run-skill");

        assertThat(coordinator.invokeExplicit(new SkillInvocationRequest(runId, first,
                SkillInvocationKind.EXPLICIT, "ARG_SENTINEL"), CancellationToken.none()).succeeded()).isTrue();
        var activation = coordinator.activationTool().orElseThrow();
        var outcome = activation.execute(new ToolInvocation(new SessionId("session-skill"), runId, 1,
                new ToolCall("call-skill", "activate_skill",
                        new JsonObject(Map.of("name", "second", "arguments", "")))));
        assertThat(outcome.successful()).isTrue();

        ModelRequest projected = coordinator.project(new ModelRequest(new SessionId("session-skill"), runId, 2,
                List.of(new UserMessage("canonical")), List.of(tool("read"), tool("test"), tool("write"))));
        assertThat(projected.toolDefinitions()).extracting(ToolDefinition::name).containsExactly("test");
        assertThat(coordinator.isToolVisible(runId, "test")).isTrue();
        assertThat(coordinator.isToolVisible(runId, "read")).isFalse();
        assertThat(coordinator.isToolVisible(runId, SkillRunCoordinator.ACTIVATE_TOOL_NAME)).isTrue();
        assertThat(projected.messages()).filteredOn(SkillContextMessage.class::isInstance)
                .map(SkillContextMessage.class::cast).extracting(value -> value.skillId().value())
                .containsExactly("first", "second");
        assertThat(projected.messages().getFirst()).isEqualTo(new UserMessage("canonical"));

        coordinator.closeRun(runId);
        assertThat(coordinator.project(projected).messages()).isEqualTo(projected.messages());
        assertThat(coordinator.activated(runId)).isEmpty();
    }

    @Test
    void failuresCancellationAndDuplicateDoNotWidenOrLeakRunState() throws Exception {
        SkillId id = new SkillId("only");
        var catalog = catalog(List.of(descriptor(id, SkillToolRestriction.declared(List.of()))));
        var coordinator = coordinator(catalog);
        RunId runId = new RunId("run-failure");
        var activation = coordinator.activationTool().orElseThrow();

        assertThat(activation.execute(new ToolInvocation(new SessionId("session-failure"), runId, 1,
                new ToolCall("unknown", "activate_skill", new JsonObject(Map.of("name", "missing"))))).successful())
                .isFalse();
        assertThat(coordinator.activated(runId)).isEmpty();
        assertThat(activation.execute(new ToolInvocation(new SessionId("session-failure"), runId, 2,
                new ToolCall("ok", "activate_skill", new JsonObject(Map.of("name", "only"))))).successful()).isTrue();
        assertThat(activation.execute(new ToolInvocation(new SessionId("session-failure"), runId, 3,
                new ToolCall("duplicate", "activate_skill", new JsonObject(Map.of("name", "only"))))).successful())
                .isFalse();
        assertThat(coordinator.activated(runId)).containsExactly(id);
        assertThat(coordinator.project(new ModelRequest(new SessionId("session-failure"), runId, 1,
                List.of(new UserMessage("task")), List.of(tool("read")))).toolDefinitions()).isEmpty();
        coordinator.closeRun(runId);
        coordinator.closeRun(runId);
    }

    @Test
    void hookLeasesAppendAfterProjectionAndCloseExactlyOnceInReverseOrder() {
        SkillId first = new SkillId("first");
        SkillId second = new SkillId("second");
        var catalog = catalog(List.of(
                descriptor(first, SkillToolRestriction.declared(List.of("read", "test"))),
                descriptor(second, SkillToolRestriction.declared(List.of("read")))));
        List<String> events = new ArrayList<>();
        SkillHookBinder binder = (runId, descriptor) -> {
            events.add("bind:" + descriptor.id().value());
            AtomicInteger closes = new AtomicInteger();
            return () -> {
                if (closes.incrementAndGet() == 1) events.add("close:" + descriptor.id().value());
            };
        };
        var coordinator = coordinator(catalog, binder);
        RunId runId = new RunId("run-hooks");

        assertThat(coordinator.invokeExplicit(new SkillInvocationRequest(runId, first,
                SkillInvocationKind.EXPLICIT, ""), CancellationToken.none()).succeeded()).isTrue();
        assertThat(coordinator.activated(runId)).containsExactly(first);
        assertThat(coordinator.invokeExplicit(new SkillInvocationRequest(runId, second,
                SkillInvocationKind.EXPLICIT, ""), CancellationToken.none()).succeeded()).isTrue();
        assertThat(coordinator.activated(runId)).containsExactly(first, second);
        assertThat(events).containsExactly("bind:first", "bind:second");
        ModelRequest compactedProjection = new ModelRequest(new SessionId("session-skill"), runId, 3,
                List.of(new UserMessage("compacted canonical")), List.of(tool("read")));
        ModelRequest rebuiltOnce = coordinator.project(compactedProjection);
        ModelRequest rebuiltTwice = coordinator.project(compactedProjection);
        assertThat(rebuiltOnce.messages().getFirst()).isEqualTo(new UserMessage("compacted canonical"));
        assertThat(rebuiltOnce.messages()).filteredOn(SkillContextMessage.class::isInstance)
                .map(SkillContextMessage.class::cast).extracting(value -> value.skillId().value())
                .containsExactly("first", "second");
        assertThat(rebuiltOnce.toolDefinitions()).extracting(ToolDefinition::name).containsExactly("read");
        assertThat(rebuiltTwice).isEqualTo(rebuiltOnce);
        assertThat(coordinator.activated(runId)).containsExactly(first, second);
        assertThat(events).containsExactly("bind:first", "bind:second");

        coordinator.closeRun(runId);
        coordinator.closeRun(runId);
        assertThat(events).containsExactly("bind:first", "bind:second", "close:second", "close:first");
    }

    @Test
    void hookBindFailureAndCancellationLeaveNoActivationOrLease() {
        SkillId id = new SkillId("only");
        var catalog = catalog(List.of(descriptor(id, SkillToolRestriction.unspecified())));
        AtomicInteger closes = new AtomicInteger();
        SkillHookBinder failing = (runId, descriptor) -> { throw new IllegalStateException("rejected"); };
        var rejected = coordinator(catalog, failing);
        RunId rejectedRun = new RunId("run-hook-rejected");

        assertThat(rejected.invokeExplicit(new SkillInvocationRequest(rejectedRun, id,
                SkillInvocationKind.EXPLICIT, ""), CancellationToken.none()).succeeded()).isFalse();
        assertThat(rejected.activated(rejectedRun)).isEmpty();

        AtomicInteger checks = new AtomicInteger();
        CancellationToken token = new CancellationToken() {
            @Override public boolean isCancellationRequested() { return checks.incrementAndGet() >= 4; }
            @Override public Registration onCancellation(Runnable action) { return () -> { }; }
        };
        SkillHookBinder cancellable = (runId, descriptor) -> () -> closes.incrementAndGet();
        var cancelled = coordinator(catalog, cancellable);
        RunId cancelledRun = new RunId("run-hook-cancelled");
        assertThat(cancelled.invokeExplicit(new SkillInvocationRequest(cancelledRun, id,
                SkillInvocationKind.EXPLICIT, ""), token).errorCode())
                .isEqualTo(io.github.liumaishenjian.ccjava.domain.skill.SkillErrorCode.CANCELLED);
        assertThat(cancelled.activated(cancelledRun)).isEmpty();
        assertThat(closes).hasValue(1);
    }

    private static SkillRunCoordinator coordinator(ImmutableSkillCatalog catalog) {
        return coordinator(catalog, SkillHookBinder.none());
    }

    private static SkillRunCoordinator coordinator(ImmutableSkillCatalog catalog, SkillHookBinder binder) {
        SkillContentLoader content = (snapshot, descriptor, cancellation) ->
                new SkillContentSnapshot(descriptor.id(), snapshot.snapshotId(), descriptor.contentDigest(),
                        "body-" + descriptor.id().value());
        SkillInvoker invoker = new SkillInvoker(catalog, content, (snapshot, descriptor, cancellation) -> List.of(),
                new SkillToolScopeNarrower());
        String empty = emptyDigest();
        SkillRecoveryIdentityCatalog identities = id -> catalog.find(id).map(descriptor ->
                new io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryIdentity(id,
                        "1".repeat(64), "2".repeat(64), descriptor.contentDigest(), empty,
                        empty, empty, empty, empty, empty));
        return new SkillRunCoordinator(catalog, invoker, List.of("read", "test", "write"),
                io.github.liumaishenjian.ccjava.core.SessionJournal.noop(), binder, identities);
    }

    private static ImmutableSkillCatalog catalog(List<SkillDescriptor> entries) {
        return new ImmutableSkillCatalog(new SkillCatalogSnapshot(SNAPSHOT, entries, List.of()));
    }

    private static SkillDescriptor descriptor(SkillId id, SkillToolRestriction restriction) {
        return new SkillDescriptor(id, "description", SkillInvocationPolicy.BOTH, SkillSource.USER,
                "user/" + id.value(), DIGEST, restriction, List.of(), List.of());
    }

    private static String emptyDigest() {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ToolDefinition tool(String name) {
        return ToolDefinition.readOnlyText(name, name, "{\"type\":\"object\"}");
    }
}
