package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.cli.session.SessionStorage;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewer;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanContextPolicy;
import io.github.liumaishenjian.ccjava.domain.PlanReviewDecision;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 durable Plan 一键批准、策略交接、恢复与终态。 */
class DurablePlanExecutionHandoffTest {
    @TempDir Path temporary;

    @Test
    void approveAutoAtomicallyBindsArtifactAndExecutesMarkdownThroughNormalPipeline() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-auto"));
        Path root = temporary.resolve("sessions-auto");
        String markdown = "# Approved plan\n\nCreate `result.txt` with the word done.\n";
        AtomicInteger calls = new AtomicInteger();
        List<io.github.liumaishenjian.ccjava.domain.ModelRequest> requests = new ArrayList<>();
        ModelGateway model = request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> ModelTurn.tools(List.of(new ToolCall("create", "revise_plan_artifact",
                        new JsonObject(Map.of("markdown", markdown, "expectedRevision", 0,
                                "expectedContentDigest", "")))));
                case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                        new JsonObject(Map.of("requirementId", "result-file", "kind", "DELIVERABLE",
                                "locator", "result.txt", "label", "result file exists", "required", true)))));
                case 2 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review",
                        new JsonObject(Map.of("revision", 2, "contentDigest", PlanArtifact.digest(markdown))))));
                case 3 -> ModelTurn.text("planning complete");
                case 4 -> ModelTurn.tools(List.of(new ToolCall("write", "write_file",
                        new JsonObject(Map.of("path", "result.txt", "content", "done\n")))));
                case 5 -> ModelTurn.text("{\"verdict\":\"ALLOW_ONCE\"}");
                default -> ModelTurn.text("implemented");
            };
        };
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(model, AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.create()),
                (ignoredInvocation, ignoredDefinition, ignoredOutcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce())) {
            runtime.open();
            runtime.runPlan("prepare a durable plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            String workspaceDigest = runtime.currentWorkspaceDigest();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), workspaceDigest, PlanReviewDecision.APPROVE_AUTO,
                    PlanContextPolicy.KEEP, "also verify the file");
            PlanArtifact approved = runtime.planArtifact().orElseThrow();
            assertThat(approved.status()).isEqualTo(PlanStatus.APPROVED);
            assertThat(approved.executionBrief()).contains(acceptance.brief());
            assertThat(acceptance.brief().approvalReviewer()).isEqualTo(ApprovalReviewer.AUTO_REVIEW);
            assertThat(acceptance.brief().markdownSnapshot()).isEqualTo(markdown);
            assertThat(runtime.runAcceptedPlan(acceptance).stopReason().name()).isEqualTo("COMPLETED");
            assertThat(Files.readString(workspace.resolve("result.txt"))).isEqualTo("done\n");
            assertThat(runtime.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.COMPLETED);
            assertThat(requests.get(4).messages().toString()).contains(markdown).doesNotContain("expectedDigest");
        }
    }

    @Test
    void normalApprovalClearContextAndDuplicateDecisionFailClosed() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-user"));
        Path root = temporary.resolve("sessions-user");
        String markdown = "# Plan\n\nInspect only.\n";
        AtomicInteger calls = new AtomicInteger();
        ModelGateway model = request -> switch (calls.getAndIncrement()) {
            case 0 -> ModelTurn.tools(List.of(new ToolCall("create", "revise_plan_artifact",
                    new JsonObject(Map.of("markdown", markdown, "expectedRevision", 0,
                            "expectedContentDigest", "")))));
            case 1 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review",
                    new JsonObject(Map.of("revision", 1, "contentDigest", PlanArtifact.digest(markdown))))));
            default -> ModelTurn.text("done");
        };
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(model, AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.create()))) {
            runtime.open(); runtime.runPlan("plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var accepted = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(), awaiting.contentDigest(),
                    runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER,
                    PlanContextPolicy.CLEAR, "");
            assertThat(accepted.brief().approvalReviewer()).isEqualTo(ApprovalReviewer.USER);
            assertThat(accepted.brief().contextPolicy()).isEqualTo(PlanContextPolicy.CLEAR);
            assertThatThrownBy(() -> runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(),
                    awaiting.contentDigest(), runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER,
                    PlanContextPolicy.CLEAR, "")).isInstanceOf(IllegalStateException.class);
            runtime.releaseAcceptedPlan(accepted);
            assertThat(runtime.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.APPROVED);
        }
    }

    @Test
    void approvedRestartIsExplicitWhileExecutingRestartRequiresRecoveryGate() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-recovery"));
        Path root = temporary.resolve("sessions-recovery");
        String markdown = "# Plan\n\nRecover explicitly.\n";
        AtomicInteger calls = new AtomicInteger();
        ModelGateway model = request -> switch (calls.getAndIncrement()) {
            case 0 -> ModelTurn.tools(List.of(new ToolCall("create", "revise_plan_artifact",
                    new JsonObject(Map.of("markdown", markdown, "expectedRevision", 0,
                            "expectedContentDigest", "")))));
            case 1 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review",
                    new JsonObject(Map.of("revision", 1, "contentDigest", PlanArtifact.digest(markdown))))));
            default -> ModelTurn.text("done");
        };
        SessionId id;
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(model, AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.create()))) {
            id = runtime.open(); runtime.runPlan("plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(), awaiting.contentDigest(),
                    runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER, PlanContextPolicy.KEEP, "");
            runtime.releaseAcceptedPlan(acceptance);
        }
        try (HeadlessRuntimeSession resumed = new HeadlessRuntimeSession(
                ignored -> ModelTurn.text("done"), AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.resume(id)))) {
            resumed.open();
            var accepted = resumed.resumeApprovedPlanExecution().orElseThrow();
            resumed.runAcceptedPlan(accepted);
            assertThat(resumed.planArtifact().orElseThrow().status()).isEqualTo(PlanStatus.NEEDS_VERIFICATION);
        }
    }


    @Test
    void completedAgentRunWithoutEvidenceNeedsVerificationAndTypedSkipIsDurable() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-skip"));
        Path root = temporary.resolve("sessions-skip");
        String markdown = "# Plan\n\nRun verification.\n";
        AtomicInteger calls = new AtomicInteger();
        ModelGateway model = request -> switch (calls.getAndIncrement()) {
            case 0 -> ModelTurn.tools(List.of(new ToolCall("create", "revise_plan_artifact",
                    new JsonObject(Map.of("markdown", markdown, "expectedRevision", 0,
                            "expectedContentDigest", "")))));
            case 1 -> ModelTurn.tools(List.of(new ToolCall("evidence", "declare_plan_evidence",
                    new JsonObject(Map.of("requirementId", "tests", "kind", "VERIFICATION",
                            "locator", "run_command", "label", "tests pass", "required", true)))));
            case 2 -> ModelTurn.tools(List.of(new ToolCall("review", "request_plan_review",
                    new JsonObject(Map.of("revision", 2, "contentDigest", PlanArtifact.digest(markdown))))));
            default -> ModelTurn.text("done without running tests");
        };
        SessionId id;
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(model, AgentEventSink.noop(),
                options(workspace, root, SessionOpenRequest.create()))) {
            id = runtime.open(); runtime.runPlan("plan");
            PlanArtifact awaiting = runtime.planArtifact().orElseThrow();
            var acceptance = runtime.acceptPlanExecution(awaiting.planId(), awaiting.revision(), awaiting.contentDigest(),
                    runtime.currentWorkspaceDigest(), PlanReviewDecision.APPROVE_USER, PlanContextPolicy.KEEP, "");
            runtime.runAcceptedPlan(acceptance);
            PlanArtifact needs = runtime.planArtifact().orElseThrow();
            assertThat(needs.status()).isEqualTo(PlanStatus.NEEDS_VERIFICATION);
            assertThat(needs.evidenceLedger().references()).singleElement().satisfies(reference -> {
                assertThat(reference.status().name()).isEqualTo("FAILED");
                assertThat(reference.reasonCode()).isEqualTo("SUCCESSFUL_TOOL_RESULT_MISSING");
            });
            PlanArtifact skipped = runtime.skipPlanVerification("tests", "decision-user-1");
            assertThat(skipped.status()).isEqualTo(PlanStatus.COMPLETED);
            assertThat(skipped.evidenceLedger().references()).singleElement().satisfies(reference -> {
                assertThat(reference.status().name()).isEqualTo("SKIPPED");
                assertThat(reference.sourceReference()).isEqualTo("decision-user-1");
            });
        }
        try (HeadlessRuntimeSession resumed = new HeadlessRuntimeSession(ignored -> ModelTurn.text("unused"),
                AgentEventSink.noop(), options(workspace, root, SessionOpenRequest.resume(id)))) {
            resumed.open();
            assertThat(resumed.planArtifact().orElseThrow().evidenceLedger().references())
                    .singleElement().satisfies(reference -> assertThat(reference.status().name()).isEqualTo("SKIPPED"));
        }
    }

    private static HeadlessRuntimeOptions options(Path workspace, Path root, SessionOpenRequest open) {
        return new HeadlessRuntimeOptions(workspace, "fake-model", Duration.ofSeconds(5), PermissionMode.DEFAULT,
                List.of(), open, root, Optional.empty(),
                io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode.OFF, Optional.empty(),
                ExecutionBackendPreference.LOCAL, ExecutionShell.WINDOWS_PLATFORM);
    }
}
