package io.github.liumaishenjian.ccjava.cli.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.LifecycleDispatcher;
import io.github.liumaishenjian.ccjava.core.UuidAgentIdGenerator;
import io.github.liumaishenjian.ccjava.core.session.RetentionAction;
import io.github.liumaishenjian.ccjava.core.session.SessionLifecycleStatus;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 S14 Session control 只信服务端 canonical 与实际 writer fence。 */
class SessionLifecycleServiceTest {
    @TempDir Path temp;

    @Test void rebuildsIndexExportsServerRedactionAndRejectsActiveRetention() throws Exception {
        Path workspace = temp.resolve("workspace"); Files.createDirectories(workspace);
        Path root = temp.resolve("sessions");
        var ids = new UuidAgentIdGenerator();
        var lifecycle = new LifecycleDispatcher(Clock.systemUTC(), AgentEventSink.noop());
        String id;
        try (FileSessionStore store = new FileSessionStore(root, workspace, ids, lifecycle, Clock.systemUTC())) {
            var opened = store.open(SessionOpenRequest.create(),
                    new SessionSpec("system", Map.of("model", "fixture")));
            id = opened.session().id().value();
            RunId run = new RunId("run-one");
            store.runStarted(opened.session().id(), run,
                    new UserMessage("token=SECRET_SENTINEL ordinary"));
            store.runCompleted(opened.session().id(), run, StopReason.COMPLETED);

            SessionLifecycleService active = new SessionLifecycleService(root);
            assertThat(active.list(0, 10)).extracting(value -> value.sessionId()).contains(id);
            assertThat(active.list(0, 10).getFirst().status()).isEqualTo(SessionLifecycleStatus.ACTIVE);
            assertThat(active.retain(id, RetentionAction.ARCHIVE, false, false).status()).isEqualTo("ACTIVE");
        }

        SessionLifecycleService service = new SessionLifecycleService(root);
        assertThat(service.search("fixture", 10)).hasSize(1);
        String metadata = new String(service.export(id, false, false, false), StandardCharsets.UTF_8);
        assertThat(metadata).doesNotContain("SECRET_SENTINEL", "ordinary");
        String content = new String(service.export(id, true, true, true), StandardCharsets.UTF_8);
        assertThat(content).contains("[REDACTED]", "ordinary").doesNotContain("SECRET_SENTINEL");
        assertThat(service.retain(id, RetentionAction.ARCHIVE, false, false).success()).isTrue();
        assertThat(service.list(0, 10).getFirst().status()).isEqualTo(SessionLifecycleStatus.ARCHIVED);
    }

    @Test void incompleteSideEffectAndMigrationFenceBlockRetention() throws Exception {
        Path workspace = temp.resolve("workspace-two"); Files.createDirectories(workspace);
        Path root = temp.resolve("sessions-two");
        var ids = new UuidAgentIdGenerator();
        var lifecycle = new LifecycleDispatcher(Clock.systemUTC(), AgentEventSink.noop());
        String id;
        try (FileSessionStore store = new FileSessionStore(root, workspace, ids, lifecycle, Clock.systemUTC())) {
            var opened = store.open(SessionOpenRequest.create(), new SessionSpec("system", Map.of("model", "fixture")));
            id = opened.session().id().value();
            RunId run = new RunId("run-two");
            store.runStarted(opened.session().id(), run, new UserMessage("work"));
            store.toolStarted(opened.session().id(), run, 0, "call-one", "run_command",
                    io.github.liumaishenjian.ccjava.domain.ToolEffect.EXECUTE_PROCESS);
        }
        SessionLifecycleService service = new SessionLifecycleService(root);
        assertThat(service.retain(id, RetentionAction.ARCHIVE, false, false).status())
                .isEqualTo("INCOMPLETE_SIDE_EFFECT");
        Files.writeString(root.resolve(id).resolve("session.jsonl.migration.journal"), "proof");
        assertThat(service.retain(id, RetentionAction.ARCHIVE, false, false).status()).isEqualTo("MIGRATING");
    }
}
