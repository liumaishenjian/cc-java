package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ContextPreparationConfig;
import io.github.liumaishenjian.ccjava.core.ContextSummarizer;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.command.CommandId;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandIntent;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandResultCode;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionCommandDispatcherTest {
    @TempDir Path root;

    @Test
    void helpContextAndDoctorUseOnlySafePublishedViews() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        try (HeadlessRuntimeSession runtime = contextRuntime(workspace)) {
            runtime.open();
            runtime.run("create context usage");
            assertThat(new DoctorReportService(runtime).report()).isNotNull();
            SessionCommandDispatcher dispatcher = dispatcher(runtime);

            var help = dispatcher.dispatch(new CommandId("help"), new SessionCommandIntent.Help(), CancellationToken.none());
            var context = dispatcher.dispatch(new CommandId("context"), new SessionCommandIntent.Context(), CancellationToken.none());
            var doctor = dispatcher.dispatch(new CommandId("doctor"), new SessionCommandIntent.Doctor(), CancellationToken.none());

            assertThat(help.event().status()).isEqualTo(SessionCommandStatus.SUCCEEDED);
            assertThat(help.event().payload().toString()).doesNotContain(workspace.toString());
            assertThat(context.event().status()).isEqualTo(SessionCommandStatus.SUCCEEDED);
            assertThat(context.event().code()).isEqualTo(SessionCommandResultCode.OK);
            assertThat(context.event().payload().toString()).doesNotContain("create context usage", workspace.toString());
            assertThat(doctor.event().status()).isEqualTo(SessionCommandStatus.SUCCEEDED);
            assertThat(doctor.event().payload().toString()).doesNotContain(workspace.toString(), "create context usage");
        }
    }

    @Test
    void helpReflectsWhetherTheCurrentSurfaceCanClear() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        try (HeadlessRuntimeSession runtime = runtime(workspace, root.resolve("sessions"))) {
            runtime.open();
            var defaultHelp = (io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent.HelpPayload) dispatcher(runtime)
                    .dispatch(new CommandId("default-help"), new SessionCommandIntent.Help(), CancellationToken.none()).event().payload();
            SessionCommandDispatcher surfaceDispatcher = new SessionCommandDispatcher(runtime,
                    new DoctorReportService(runtime), () -> { });
            var surfaceHelp = (io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent.HelpPayload) surfaceDispatcher
                    .dispatch(new CommandId("surface-help"), new SessionCommandIntent.Help(), CancellationToken.none()).event().payload();

            assertThat(support(defaultHelp, io.github.liumaishenjian.ccjava.domain.command.SessionCommandKind.CLEAR))
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent.CommandSupport.DEFERRED);
            assertThat(support(surfaceHelp, io.github.liumaishenjian.ccjava.domain.command.SessionCommandKind.CLEAR))
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent.CommandSupport.AVAILABLE);
        }
    }

    @Test
    void unavailableAndDeferredCommandsDoNotChangeJsonlOrRuntimeConfiguration() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path sessions = root.resolve("sessions");
        try (HeadlessRuntimeSession runtime = runtime(workspace, sessions)) {
            runtime.open();
            Path journal = Files.walk(sessions).filter(path -> path.getFileName().toString().endsWith(".jsonl")).findFirst().orElseThrow();
            byte[] before = Files.readAllBytes(journal);
            var configuration = runtime.runtimeConfiguration();
            SessionCommandDispatcher dispatcher = dispatcher(runtime);

            assertCode(dispatcher, "compact", new SessionCommandIntent.Compact(List.of()), SessionCommandResultCode.UNAVAILABLE);
            assertCode(dispatcher, "model", new SessionCommandIntent.ModelChange("other"), SessionCommandResultCode.NOT_AVAILABLE);
            var permissions = dispatcher.dispatch(new CommandId("permissions"),
                    new SessionCommandIntent.Permissions(new SessionCommandIntent.PermissionsOperation.Query()),
                    CancellationToken.none());
            assertThat(permissions.event().status()).isEqualTo(SessionCommandStatus.SUCCEEDED);
            assertThat(permissions.event().code()).isEqualTo(SessionCommandResultCode.OK);
            assertThat(permissions.event().payload()).isEqualTo(
                    new io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent.PermissionsPayload(
                            "DEFAULT", "BASELINE", "runtime-baseline", "BASELINE", 0, List.of()));
            assertCode(dispatcher, "resume", new SessionCommandIntent.Resume(runtime.sessionId()), SessionCommandResultCode.CURRENT_SESSION);
            assertCode(dispatcher, "clear", new SessionCommandIntent.Clear(), SessionCommandResultCode.DEFERRED);

            assertThat(Files.readAllBytes(journal)).isEqualTo(before);
            assertThat(runtime.runtimeConfiguration()).isSameAs(configuration);
        }
    }

    @Test
    void resumeAtomicallySwitchesToCleanCandidateAndPreservesCanonicalHistory() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path sessions = root.resolve("sessions");
        io.github.liumaishenjian.ccjava.domain.SessionId candidateId;
        try (HeadlessRuntimeSession candidate = runtime(workspace, sessions)) {
            candidateId = candidate.open();
            candidate.run("candidate durable history");
        }
        try (HeadlessRuntimeSession runtime = runtime(workspace, sessions)) {
            var previousId = runtime.open();
            runtime.run("current durable history");
            SessionCommandDispatcher dispatcher = dispatcher(runtime);

            var result = dispatcher.dispatch(new CommandId("resume-candidate"),
                    new SessionCommandIntent.Resume(candidateId), CancellationToken.none());

            assertThat(result.event().status()).isEqualTo(SessionCommandStatus.SUCCEEDED);
            assertThat(result.event().code()).isEqualTo(SessionCommandResultCode.OK);
            assertThat(result.event().sessionId()).isEqualTo(candidateId);
            assertThat(result.event().payload()).isEqualTo(
                    new io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent.ResumePayload(
                            previousId.value(), candidateId.value()));
            assertThat(runtime.sessionId()).isEqualTo(candidateId);
            runtime.run("after resume");

            try (HeadlessRuntimeSession releasedPrevious = runtime(
                    workspace, sessions, SessionOpenRequest.resume(previousId))) {
                assertThat(releasedPrevious.open()).isEqualTo(previousId);
            }
        }
    }

    @Test
    void resumeRejectedForActiveWriterAndKeepsCurrentSession() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path sessions = root.resolve("sessions");
        try (HeadlessRuntimeSession candidate = runtime(workspace, sessions);
             HeadlessRuntimeSession runtime = runtime(workspace, sessions)) {
            var candidateId = candidate.open();
            var currentId = runtime.open();
            var result = dispatcher(runtime).dispatch(new CommandId("resume-locked"),
                    new SessionCommandIntent.Resume(candidateId), CancellationToken.none());

            assertThat(result.event().code()).isEqualTo(SessionCommandResultCode.SESSION_ACTIVE);
            assertThat(runtime.sessionId()).isEqualTo(currentId);
        }
    }

    @Test
    void resumeRejectedForWorkspaceMismatchAndKeepsCurrentSession() throws Exception {
        Path sourceWorkspace = Files.createDirectory(root.resolve("source-workspace"));
        Path candidateWorkspace = Files.createDirectory(root.resolve("candidate-workspace"));
        Path sessions = root.resolve("sessions");
        io.github.liumaishenjian.ccjava.domain.SessionId candidateId;
        try (HeadlessRuntimeSession candidate = runtime(candidateWorkspace, sessions)) {
            candidateId = candidate.open();
        }
        try (HeadlessRuntimeSession runtime = runtime(sourceWorkspace, sessions)) {
            var currentId = runtime.open();

            var result = dispatcher(runtime).dispatch(new CommandId("resume-wrong-workspace"),
                    new SessionCommandIntent.Resume(candidateId), CancellationToken.none());

            assertThat(result.event().status()).isEqualTo(SessionCommandStatus.REJECTED);
            assertThat(result.event().code()).isEqualTo(SessionCommandResultCode.RECOVERY_REQUIRED);
            assertThat(runtime.sessionId()).isEqualTo(currentId);
        }
    }

    @Test
    void resumeCancellationAndDuplicateCommandIdPreserveCurrentSession() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path sessions = root.resolve("sessions");
        io.github.liumaishenjian.ccjava.domain.SessionId candidateId;
        try (HeadlessRuntimeSession candidate = runtime(workspace, sessions)) {
            candidateId = candidate.open();
        }
        try (HeadlessRuntimeSession runtime = runtime(workspace, sessions)) {
            var currentId = runtime.open();
            CancellationSource cancelled = new CancellationSource();
            cancelled.cancel();
            SessionCommandDispatcher dispatcher = dispatcher(runtime);
            var first = dispatcher.dispatch(new CommandId("resume-cancelled"),
                    new SessionCommandIntent.Resume(candidateId), cancelled.token());
            var repeated = dispatcher.dispatch(new CommandId("resume-cancelled"),
                    new SessionCommandIntent.Resume(candidateId), CancellationToken.none());

            assertThat(first.event().status()).isEqualTo(SessionCommandStatus.CANCELLED);
            assertThat(repeated).isSameAs(first);
            assertThat(runtime.sessionId()).isEqualTo(currentId);
        }
    }

    @Test
    void requestBudgetFailsClosedWithoutEvictingPriorClearResult() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        try (HeadlessRuntimeSession runtime = runtime(workspace, root.resolve("sessions"))) {
            runtime.open();
            AtomicInteger clears = new AtomicInteger();
            SessionCommandDispatcher dispatcher = new SessionCommandDispatcher(runtime, new DoctorReportService(runtime),
                    clears::incrementAndGet, 1);

            var clear = dispatcher.dispatch(new CommandId("clear"), new SessionCommandIntent.Clear(), CancellationToken.none());
            var exhausted = dispatcher.dispatch(new CommandId("new-id"), new SessionCommandIntent.Help(), CancellationToken.none());
            var repeatedClear = dispatcher.dispatch(new CommandId("clear"), new SessionCommandIntent.Clear(), CancellationToken.none());

            assertThat(clear.event().code()).isEqualTo(SessionCommandResultCode.OK);
            assertThat(exhausted.event().status()).isEqualTo(SessionCommandStatus.REJECTED);
            assertThat(exhausted.event().code()).isEqualTo(SessionCommandResultCode.REQUEST_BUDGET_EXHAUSTED);
            assertThat(repeatedClear).isSameAs(clear);
            assertThat(clears).hasValue(1);
        }
    }

    @Test
    void cancellationAndSurfaceClearProduceOneTerminalEventWithoutCancellingRun() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path sessions = root.resolve("sessions");
        try (HeadlessRuntimeSession runtime = runtime(workspace, sessions)) {
            runtime.open();
            Path journal = Files.walk(sessions).filter(path -> path.getFileName().toString().endsWith(".jsonl")).findFirst().orElseThrow();
            byte[] before = Files.readAllBytes(journal);
            AtomicInteger clears = new AtomicInteger();
            SessionCommandDispatcher dispatcher = new SessionCommandDispatcher(runtime, new DoctorReportService(runtime), clears::incrementAndGet);
            CancellationSource cancelled = new CancellationSource();
            cancelled.cancel();

            var cancellation = dispatcher.dispatch(new CommandId("cancelled"), new SessionCommandIntent.Help(), cancelled.token());
            var clear = dispatcher.dispatch(new CommandId("clear"), new SessionCommandIntent.Clear(), CancellationToken.none());
            var repeatedClear = dispatcher.dispatch(new CommandId("clear"), new SessionCommandIntent.Clear(), CancellationToken.none());

            assertThat(cancellation.event().commandId().value()).isEqualTo("cancelled");
            assertThat(cancellation.event().status()).isEqualTo(SessionCommandStatus.CANCELLED);
            assertThat(clear.event().status()).isEqualTo(SessionCommandStatus.SUCCEEDED);
            assertThat(repeatedClear).isSameAs(clear);
            assertThat(clears).hasValue(1);
            assertThat(Files.readAllBytes(journal)).isEqualTo(before);
            assertThat(runtime.cancelActive()).isFalse();
        }
    }

    @Test
    void duplicateCompactCommandIdAdoptsOnceAndReusesTerminalResult() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        AtomicInteger summaries = new AtomicInteger();
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(
                request -> ModelTurn.text("done"), AgentEventSink.noop(),
                options(workspace, root.resolve("sessions")),
                (a, b, c) -> io.github.liumaishenjian.ccjava.domain.ApprovalResponse.deny(),
                (request, token) -> {
                    summaries.incrementAndGet();
                    String text = "short";
                    return Optional.of(new io.github.liumaishenjian.ccjava.domain.SummaryCandidate(
                            request.tier(), text, request.sourceRevision(), request.sourceMessageIds(),
                            text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, 1));
                })) {
            runtime.open();
            runtime.run("history one " + "x".repeat(70));
            runtime.run("history two " + "y".repeat(70));
            SessionCommandDispatcher dispatcher = dispatcher(runtime);

            var first = dispatcher.dispatch(new CommandId("compact"),
                    new SessionCommandIntent.Compact(List.of()), CancellationToken.none());
            var repeated = dispatcher.dispatch(new CommandId("compact"),
                    new SessionCommandIntent.Compact(List.of()), CancellationToken.none());

            assertThat(first.event().status()).isEqualTo(SessionCommandStatus.SUCCEEDED);
            assertThat(repeated).isSameAs(first);
            assertThat(summaries).hasValue(1);
        }
    }

    @Test
    void activeRunRejectsAllMutationIntentsAndDoctorRemainsReadable() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(request -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException exception) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
            }
            return ModelTurn.text("done");
        }, AgentEventSink.noop(), options(workspace, root.resolve("sessions")))) {
            runtime.open();
            Thread runner = Thread.ofPlatform().start(() -> runtime.run("blocked"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            SessionCommandDispatcher dispatcher = dispatcher(runtime);

            assertCode(dispatcher, "compact", new SessionCommandIntent.Compact(List.of()), SessionCommandResultCode.ACTIVE_RUN);
            assertCode(dispatcher, "model", new SessionCommandIntent.ModelChange("fake-model"), SessionCommandResultCode.ACTIVE_RUN);
            assertCode(dispatcher, "permissions", new SessionCommandIntent.Permissions(new SessionCommandIntent.PermissionsOperation.ModeChange(io.github.liumaishenjian.ccjava.domain.PermissionMode.PLAN)), SessionCommandResultCode.ACTIVE_RUN);
            assertCode(dispatcher, "resume", new SessionCommandIntent.Resume(runtime.sessionId()), SessionCommandResultCode.ACTIVE_RUN);
            assertThat(dispatcher.dispatch(new CommandId("help"), new SessionCommandIntent.Help(), CancellationToken.none()).event().status())
                    .isEqualTo(SessionCommandStatus.SUCCEEDED);
            assertThat(dispatcher.dispatch(new CommandId("context"), new SessionCommandIntent.Context(), CancellationToken.none()).event().code())
                    .isEqualTo(SessionCommandResultCode.UNAVAILABLE);
            assertThat(dispatcher.dispatch(new CommandId("clear"), new SessionCommandIntent.Clear(), CancellationToken.none()).event().code())
                    .isEqualTo(SessionCommandResultCode.DEFERRED);
            assertThat(dispatcher.dispatch(new CommandId("doctor"), new SessionCommandIntent.Doctor(), CancellationToken.none()).event().status())
                    .isEqualTo(SessionCommandStatus.SUCCEEDED);
            release.countDown();
            runner.join(5_000);
        }
    }

    private static io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent.CommandSupport support(
            io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent.HelpPayload help,
            io.github.liumaishenjian.ccjava.domain.command.SessionCommandKind kind) {
        return help.commands().stream().filter(command -> command.kind() == kind).findFirst().orElseThrow().support();
    }

    private static void assertCode(SessionCommandDispatcher dispatcher, String id, SessionCommandIntent intent,
                                   SessionCommandResultCode code) {
        var result = dispatcher.dispatch(new CommandId(id), intent, CancellationToken.none());
        assertThat(result.event().code()).isEqualTo(code);
        assertThat(result.event().commandId().value()).isEqualTo(id);
        assertThat(result.event().status()).isEqualTo(SessionCommandStatus.REJECTED);
    }

    private static SessionCommandDispatcher dispatcher(HeadlessRuntimeSession runtime) {
        return new SessionCommandDispatcher(runtime, new DoctorReportService(runtime));
    }

    private HeadlessRuntimeSession runtime(Path workspace, Path sessions) {
        return new HeadlessRuntimeSession(request -> ModelTurn.text("done"), AgentEventSink.noop(), options(workspace, sessions));
    }

    private HeadlessRuntimeSession runtime(Path workspace, Path sessions, SessionOpenRequest openRequest) {
        return new HeadlessRuntimeSession(
                request -> ModelTurn.text("done"), AgentEventSink.noop(), options(workspace, sessions, openRequest));
    }

    private HeadlessRuntimeSession contextRuntime(Path workspace) {
        return new HeadlessRuntimeSession(request -> ModelTurn.text("done"), AgentEventSink.noop(),
                options(workspace, root.resolve("sessions")), (a, b, c) -> io.github.liumaishenjian.ccjava.domain.ApprovalResponse.deny(),
                (ContextSummarizer) (request, token) -> Optional.empty());
    }

    private static HeadlessRuntimeOptions options(Path workspace, Path sessions) {
        return options(workspace, sessions, SessionOpenRequest.create());
    }

    private static HeadlessRuntimeOptions options(
            Path workspace, Path sessions, SessionOpenRequest openRequest) {
        return new HeadlessRuntimeOptions(workspace, "fake-model", Duration.ofSeconds(5), PermissionMode.DEFAULT,
                List.of(), openRequest, sessions, Optional.of(new ContextPreparationConfig(
                        new ContextCapacity("fake-model", 4_000, 100, 100), 200, 0, 1_024, 256)));
    }
}
