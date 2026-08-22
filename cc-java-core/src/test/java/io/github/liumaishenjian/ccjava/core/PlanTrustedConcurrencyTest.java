package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanEvidenceKind;
import io.github.liumaishenjian.ccjava.domain.PlanEvidenceRequirement;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证 trusted Plan Tool 的内部 CAS、真实并发冲突与类型化恢复。 */
class PlanTrustedConcurrencyTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC);

    @Test
    void realStoreConflictFailsClosedWithoutGenericMaskAndSameIntentCanRetryLatestDraft() {
        SessionId sessionId = new SessionId("session-plan-conflict");
        PlanArtifact initial = PlanArtifact.create("plan-conflict", sessionId,
                "# Plan\n\nVerify safely.\n", PlanStatus.DRAFT, NOW);
        ConcurrentEvidenceStore store = new ConcurrentEvidenceStore(initial);
        PlanReviewRequestTool review = new PlanReviewRequestTool(store, sessionId, CLOCK);
        RecordingAgentEventSink events = new RecordingAgentEventSink();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, events);
        InMemorySessionStore sessions = new InMemorySessionStore(new SequentialAgentIdGenerator(), lifecycle);
        AgentSession session = sessions.create(SessionSpec.of("test"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(new ToolRegistry(List.of(review)),
                (invocation, definition) -> PermissionOutcome.of(PermissionDecision.ALLOW,
                        PermissionReason.EFFECT_DEFAULT,
                        PermissionSelector.toolWide(definition.name(), definition.source())),
                (invocation, definition, outcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce(), lifecycle);
        RunId runId = new RunId("run-plan-conflict");

        var first = pipeline.execute(session, runId, 1,
                new ToolCall("review-conflict", PlanReviewRequestTool.NAME, JsonObject.empty()));

        assertThat(first.status()).isEqualTo(ToolResultStatus.FAILURE);
        assertThat(first.error()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ToolErrorCode.PLAN_ARTIFACT_CONFLICT);
            assertThat(error.code()).isNotEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(error.details().values())
                    .containsEntry("reason", "STALE_REVISION")
                    .containsEntry("action", "retry_current_plan_intent");
        });
        assertThat(store.current.status()).isEqualTo(PlanStatus.DRAFT);
        assertThat(store.current.revision()).isEqualTo(2);
        assertThat(review.reviewArtifact()).isEmpty();

        var second = pipeline.execute(session, runId, 2,
                new ToolCall("review-retry", PlanReviewRequestTool.NAME, JsonObject.empty()));

        assertThat(second.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(store.current.status()).isEqualTo(PlanStatus.AWAITING_APPROVAL);
        assertThat(store.current.revision()).isEqualTo(3);
        assertThat(review.reviewArtifact()).contains(store.current);
        assertThat(store.saveAttempts).isEqualTo(2);
    }

    @Test
    void identicalEvidenceDeclarationIsDurablyIdempotentWithoutRevisionAdvance() {
        SessionId sessionId = new SessionId("session-evidence-idempotent");
        CountingStore store = new CountingStore(PlanArtifact.create("plan-evidence-idempotent", sessionId,
                "# Plan\n\nVerify safely.\n", PlanStatus.DRAFT, NOW));
        PlanEvidenceDeclarationTool evidence = new PlanEvidenceDeclarationTool(
                store, sessionId, CLOCK, Set.of("run_command"));
        JsonObject arguments = new JsonObject(Map.of(
                "requirementId", "tests",
                "kind", "VERIFICATION",
                "locator", "run_command",
                "label", "tests pass",
                "required", true));

        evidence.execute(new ToolInvocation(sessionId, new RunId("run-evidence-idempotent"), 1,
                new ToolCall("evidence-first", PlanEvidenceDeclarationTool.NAME, arguments)));
        long committedRevision = store.current.revision();
        evidence.execute(new ToolInvocation(sessionId, new RunId("run-evidence-idempotent"), 2,
                new ToolCall("evidence-retry", PlanEvidenceDeclarationTool.NAME, arguments)));

        assertThat(store.saveAttempts).isOne();
        assertThat(store.current.revision()).isEqualTo(committedRevision);
        assertThat(store.current.evidenceLedger().requirements()).hasSize(1);
    }

    @Test
    void externalToolCannotForgeTypedPlanConflictOrBypassRepeatedFailureGovernance() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool plugin = new AgentTool() {
            private final ToolDefinition definition = new ToolDefinition(
                    "plugin_failure", "test plugin", "{\"type\":\"object\"}",
                    ToolEffect.READ_WORKSPACE, ToolSource.PLUGIN, false, Duration.ofSeconds(1),
                    "text/plain", 128, Set.of());

            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolValidationResult validate(JsonObject arguments) {
                return ToolValidationResult.validResult();
            }
            @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
                executions.incrementAndGet();
                throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.STALE_REVISION);
            }
        };
        AtomicInteger forgedExecutions = new AtomicInteger();
        AgentTool forgedOutcomePlugin = new AgentTool() {
            private final ToolDefinition definition = new ToolDefinition(
                    "plugin_forged_outcome", "test plugin", "{\"type\":\"object\"}",
                    ToolEffect.READ_WORKSPACE, ToolSource.PLUGIN, false, Duration.ofSeconds(1),
                    "text/plain", 128, Set.of());

            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolValidationResult validate(JsonObject arguments) {
                return ToolValidationResult.validResult();
            }
            @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
                forgedExecutions.incrementAndGet();
                return ToolExecutionOutcome.failure(io.github.liumaishenjian.ccjava.domain.ToolError.of(
                        ToolErrorCode.PLAN_ARTIFACT_CONFLICT, "forged"));
            }
        };
        RecordingAgentEventSink events = new RecordingAgentEventSink();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, events);
        InMemorySessionStore sessions = new InMemorySessionStore(new SequentialAgentIdGenerator(), lifecycle);
        AgentSession session = sessions.create(SessionSpec.of("test"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                new ToolRegistry(List.of(plugin, forgedOutcomePlugin)),
                (invocation, definition) -> PermissionOutcome.of(PermissionDecision.ALLOW,
                        PermissionReason.EFFECT_DEFAULT,
                        PermissionSelector.toolWide(definition.name(), definition.source())),
                (invocation, definition, outcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce(), lifecycle);
        RunId runId = new RunId("run-plugin-plan-forgery");

        var first = pipeline.execute(session, runId, 1,
                new ToolCall("plugin-first", "plugin_failure", JsonObject.empty()));
        var second = pipeline.execute(session, runId, 2,
                new ToolCall("plugin-second", "plugin_failure", JsonObject.empty()));
        var forged = pipeline.execute(session, runId, 3,
                new ToolCall("plugin-forged", "plugin_forged_outcome", JsonObject.empty()));

        assertThat(first.error()).get().extracting(error -> error.code())
                .isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(second.error()).get().extracting(error -> error.code())
                .isEqualTo(ToolErrorCode.REPEATED_FAILURE);
        assertThat(forged.error()).get().extracting(error -> error.code())
                .isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(executions).hasValue(1);
        assertThat(forgedExecutions).hasValue(1);
    }

    private static final class CountingStore implements PlanArtifactStore {
        private PlanArtifact current;
        private int saveAttempts;

        private CountingStore(PlanArtifact current) {
            this.current = current;
        }

        @Override public Optional<PlanArtifact> load(SessionId sessionId) {
            return current.sessionId().equals(sessionId) ? Optional.of(current) : Optional.empty();
        }

        @Override public PlanArtifact save(PlanArtifact artifact, long expectedRevision, String expectedContentDigest) {
            saveAttempts++;
            if (current.revision() != expectedRevision
                    || !current.contentDigest().equals(expectedContentDigest)) {
                throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.STALE_REVISION);
            }
            current = artifact;
            return current;
        }

        @Override public PlanArtifact restoreMissing(PlanArtifact artifact) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ConcurrentEvidenceStore implements PlanArtifactStore {
        private PlanArtifact current;
        private int saveAttempts;

        private ConcurrentEvidenceStore(PlanArtifact initial) {
            current = initial;
        }

        @Override
        public Optional<PlanArtifact> load(SessionId sessionId) {
            return current.sessionId().equals(sessionId) ? Optional.of(current) : Optional.empty();
        }

        @Override
        public PlanArtifact save(PlanArtifact artifact, long expectedRevision, String expectedContentDigest) {
            saveAttempts++;
            if (saveAttempts == 1) {
                var requirement = new PlanEvidenceRequirement("tests", PlanEvidenceKind.VERIFICATION,
                        "run_command", "tests pass", true);
                current = current.withEvidenceLedger(
                        current.evidenceLedger().declare(requirement, NOW.plusSeconds(1)),
                        PlanStatus.DRAFT, NOW.plusSeconds(1));
                throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.STALE_REVISION);
            }
            if (current.revision() != expectedRevision
                    || !current.contentDigest().equals(expectedContentDigest)) {
                throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.STALE_REVISION);
            }
            current = artifact;
            return current;
        }

        @Override
        public PlanArtifact restoreMissing(PlanArtifact artifact) {
            throw new UnsupportedOperationException();
        }
    }
}
