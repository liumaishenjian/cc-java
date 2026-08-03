package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证 S06 Tool journal 在关键崩溃点不会制造不可恢复的规范历史。 */
class SessionJournalCrashConsistencyTest {

    @Test
    void deniedResultUsesResolvedRecordBeforeEnteringMemory() {
        ToolCall call = call("deny-1", "denied");
        RecordingJournal journal = new RecordingJournal(FailurePoint.NONE);
        RecordingAgentTool tool = RecordingAgentTool.succeeding("denied", "must-not-run");
        Fixture fixture = fixture(call, tool, denyAll(), journal);

        AgentRunResult result = fixture.run();

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(tool.invocations()).isEmpty();
        assertThat(journal.records()).containsSubsequence(
                "assistant:deny-1",
                "resolved:deny-1:PERMISSION_DENIED");
        assertThat(fixture.session().messages())
                .filteredOn(ToolResultMessage.class::isInstance)
                .hasSize(1);
    }

    @Test
    void unknownToolUsesResolvedRecordWithoutStarted() {
        ToolCall call = call("unknown-1", "missing");
        RecordingJournal journal = new RecordingJournal(FailurePoint.NONE);
        Fixture fixture = fixture(call, null, allowAll(), journal);

        AgentRunResult result = fixture.run();

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(journal.records()).containsSubsequence(
                "assistant:unknown-1",
                "resolved:unknown-1:UNKNOWN_TOOL");
        assertThat(journal.records()).noneMatch(value -> value.startsWith("started:"));
    }

    @Test
    void startedWriteFailureFencesSessionBeforeExecuteOrResultAppend() {
        ToolCall call = call("start-fail", "echo");
        RecordingJournal journal = new RecordingJournal(FailurePoint.STARTED);
        RecordingAgentTool tool = RecordingAgentTool.succeeding("echo", "must-not-run");
        Fixture fixture = fixture(call, tool, allowAll(), journal);

        AgentRunResult result = fixture.run();

        assertThat(result.stopReason()).isEqualTo(StopReason.INTERNAL_ERROR);
        assertThat(tool.invocations()).isEmpty();
        assertThat(fixture.session().isFenced()).isTrue();
        assertThat(fixture.session().messages())
                .filteredOn(ToolResultMessage.class::isInstance)
                .isEmpty();
        assertThatThrownBy(() -> fixture.runtime().run(
                fixture.session().id(),
                new AgentRunRequest(new UserMessage("must not continue"), AgentLimits.DEFAULT)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fencedSessionRejectsNextRunBeforeJournalModelOrTool() {
        ToolCall call = call("fence-next", "echo");
        RecordingJournal journal = new RecordingJournal(FailurePoint.STARTED);
        RecordingAgentTool tool = RecordingAgentTool.succeeding("echo", "must-not-run");
        Fixture fixture = fixture(call, tool, allowAll(), journal);
        fixture.run();
        int journalWrites = journal.records().size();
        int modelRequests = fixture.model().requests().size();

        assertThatThrownBy(() -> fixture.runtime().run(
                fixture.session().id(),
                new AgentRunRequest(new UserMessage("must not write journal"), AgentLimits.DEFAULT)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(journal.records()).hasSize(journalWrites);
        assertThat(fixture.model().requests()).hasSize(modelRequests);
        assertThat(tool.invocations()).isEmpty();
    }

    @Test
    void runCompletedWriteFailureReturnsInternalErrorAndFencesSession() {
        RecordingJournal journal = new RecordingJournal(FailurePoint.RUN_COMPLETED);
        Fixture fixture = fixture(null, null, allowAll(), journal);

        AgentRunResult result = fixture.run();

        assertThat(result.stopReason()).isEqualTo(StopReason.INTERNAL_ERROR);
        assertThat(result.modelTurns()).isOne();
        assertThat(result.toolCalls()).isZero();
        assertThat(result.finalText()).isEmpty();
        assertThat(fixture.session().isFenced()).isTrue();
        assertThat(fixture.finishedReasons()).containsExactly(StopReason.INTERNAL_ERROR);
        int journalWrites = journal.records().size();
        int modelRequests = fixture.model().requests().size();

        assertThatThrownBy(() -> fixture.runtime().run(
                fixture.session().id(),
                new AgentRunRequest(new UserMessage("must not continue"), AgentLimits.DEFAULT)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(journal.records()).hasSize(journalWrites);
        assertThat(fixture.model().requests()).hasSize(modelRequests);
    }

    @Test
    void completedWriteFailureFencesSessionAfterExecuteWithoutResultAppend() {
        ToolCall call = call("complete-fail", "echo");
        RecordingJournal journal = new RecordingJournal(FailurePoint.COMPLETED);
        AtomicInteger executions = new AtomicInteger();
        RecordingAgentTool tool = new RecordingAgentTool(
                "echo",
                ignored -> ToolValidationResult.validResult(),
                ignored -> {
                    executions.incrementAndGet();
                    return ToolExecutionOutcome.success("executed-once");
                });
        Fixture fixture = fixture(call, tool, allowAll(), journal);

        AgentRunResult result = fixture.run();

        assertThat(result.stopReason()).isEqualTo(StopReason.INTERNAL_ERROR);
        assertThat(executions).hasValue(1);
        assertThat(journal.records()).containsSubsequence(
                "assistant:complete-fail",
                "started:complete-fail");
        assertThat(journal.records()).noneMatch(value -> value.startsWith("completed:"));
        assertThat(fixture.session().isFenced()).isTrue();
        assertThat(fixture.session().messages())
                .filteredOn(ToolResultMessage.class::isInstance)
                .isEmpty();
    }

    private static Fixture fixture(
            ToolCall call,
            RecordingAgentTool tool,
            PermissionGate permissionGate,
            RecordingJournal journal) {
        List<StopReason> finishedReasons = new ArrayList<>();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(
                Clock.systemUTC(),
                envelope -> {
                    if (envelope.event() instanceof io.github.liumaishenjian.ccjava.domain.LifecycleEvent.RunFinished finished) {
                        finishedReasons.add(finished.result().stopReason());
                    }
                });
        SequentialAgentIdGenerator ids = new SequentialAgentIdGenerator();
        InMemorySessionStore sessions = new InMemorySessionStore(ids, lifecycle);
        ToolRegistry registry = new ToolRegistry(tool == null ? List.of() : List.of(tool));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                registry,
                permissionGate,
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) -> ApprovalResponse.allowOnce(),
                new InMemorySessionPermissionState(),
                lifecycle,
                journal);
        ScriptedModelGateway model = call == null
                ? ScriptedModelGateway.of(ModelTurn.text("done"))
                : ScriptedModelGateway.of(
                        ModelTurn.tools(List.of(call)),
                        ModelTurn.text("done"));
        AgentRuntime runtime = new AgentRuntime(
                sessions,
                ids,
                model,
                new DefaultContextAssembler(),
                registry,
                pipeline,
                lifecycle,
                journal);
        AgentSession session = sessions.create(SessionSpec.of("test"));
        return new Fixture(runtime, session, model, finishedReasons);
    }

    private static PermissionGate allowAll() {
        return (ignoredInvocation, definition) -> PermissionOutcome.of(
                PermissionDecision.ALLOW,
                PermissionReason.EFFECT_DEFAULT,
                PermissionSelector.toolWide(definition.name(), definition.source()));
    }

    private static PermissionGate denyAll() {
        return (ignoredInvocation, definition) -> PermissionOutcome.of(
                PermissionDecision.DENY,
                PermissionReason.EXPLICIT_DENY,
                PermissionSelector.toolWide(definition.name(), definition.source()));
    }

    private static ToolCall call(String id, String name) {
        return new ToolCall(id, name, JsonObject.empty());
    }

    private record Fixture(
            AgentRuntime runtime,
            AgentSession session,
            ScriptedModelGateway model,
            List<StopReason> finishedReasons) {
        AgentRunResult run() {
            return runtime.run(
                    session.id(),
                    new AgentRunRequest(new UserMessage("run"), AgentLimits.DEFAULT));
        }
    }

    private enum FailurePoint {
        NONE,
        STARTED,
        COMPLETED,
        RUN_COMPLETED
    }

    private static final class RecordingJournal implements SessionJournal {
        private final FailurePoint failurePoint;
        private final List<String> records = new ArrayList<>();

        private RecordingJournal(FailurePoint failurePoint) {
            this.failurePoint = failurePoint;
        }

        @Override
        public void runStarted(SessionId sessionId, RunId runId, UserMessage message) {
            records.add("run.started");
        }

        @Override
        public void assistantAppended(SessionId sessionId, RunId runId, AssistantMessage message) {
            records.add("assistant:" + message.toolCalls().stream()
                    .findFirst().map(ToolCall::id).orElse("text"));
        }

        @Override
        public void toolResolved(
                SessionId sessionId,
                RunId runId,
                int ordinal,
                ToolResult result,
                ToolResolutionReason reason) {
            records.add("resolved:" + result.callId() + ":" + reason);
        }

        @Override
        public void toolStarted(
                SessionId sessionId,
                RunId runId,
                int ordinal,
                String callId,
                String toolName,
                ToolEffect effect) {
            if (failurePoint == FailurePoint.STARTED) {
                throw new IllegalStateException("injected started failure");
            }
            records.add("started:" + callId);
        }

        @Override
        public void toolCompleted(
                SessionId sessionId,
                RunId runId,
                int ordinal,
                ToolResult result) {
            if (failurePoint == FailurePoint.COMPLETED) {
                throw new IllegalStateException("injected completed failure");
            }
            records.add("completed:" + result.callId());
        }

        @Override
        public void runCompleted(SessionId sessionId, RunId runId, StopReason stopReason) {
            if (failurePoint == FailurePoint.RUN_COMPLETED) {
                throw new IllegalStateException("injected run completed failure");
            }
            records.add("run.completed:" + stopReason);
        }

        List<String> records() {
            return List.copyOf(records);
        }
    }
}
