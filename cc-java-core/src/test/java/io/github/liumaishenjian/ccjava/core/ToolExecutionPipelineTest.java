package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMetadata;
import io.github.liumaishenjian.ccjava.domain.ToolResultTruncationReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ToolExecutionPipelineTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void keepsContentAtExactDefinitionLimit() {
        PipelineFixture fixture = fixture(toolWithLimit("abcd", 4));

        ToolResult result = fixture.execute();

        assertThat(result.content()).isEqualTo("abcd");
        assertThat(result.metadata().truncated()).isFalse();
        assertThat(result.metadata().returnedCharacters()).isEqualTo(4);
    }

    @Test
    void truncatesOneCharacterOverLimitAndCountsMarkerInsideLimit() {
        PipelineFixture fixture = fixture(toolWithLimit("abcdefghijklmnopqrstuvwxyz", 20));

        ToolResult result = fixture.execute();

        assertThat(result.content().codePointCount(0, result.content().length())).isEqualTo(20);
        assertThat(result.content()).contains("truncated");
        assertThat(result.metadata().truncated()).isTrue();
        assertThat(result.metadata().truncationReason())
                .isEqualTo(ToolResultTruncationReason.PIPELINE_CHARACTER_LIMIT);
        assertThat(result.metadata().knownOriginalCharacters()).hasValue(26);
    }

    @Test
    void neverSplitsUnicodeCodePoint() {
        PipelineFixture fixture = fixture(toolWithLimit("A😀BCDEFGHIJKLMNOPQRSTUVWXYZ", 20));

        ToolResult result = fixture.execute();

        assertThat(result.content()).doesNotContain("�");
        assertThat(result.content().codePointCount(0, result.content().length())).isEqualTo(20);
    }

    @Test
    void preservesSemanticTruncationWhenPipelineDoesNotTrimAgain() {
        String content = "line one";
        ToolResultMetadata metadata = new ToolResultMetadata(
                true,
                ToolResultTruncationReason.LINE_LIMIT,
                content.codePointCount(0, content.length()),
                java.util.OptionalLong.empty(),
                1,
                0,
                new JsonObject(Map.of("startLine", 2)));
        AgentTool tool = new RecordingAgentTool(
                "read_file",
                ignored -> ToolValidationResult.validResult(),
                ignored -> ToolExecutionOutcome.success(content, metadata));
        PipelineFixture fixture = fixture(tool);

        ToolResult result = fixture.execute();

        assertThat(result.metadata().truncationReason())
                .isEqualTo(ToolResultTruncationReason.LINE_LIMIT);
        assertThat(result.metadata().continuation().values()).containsEntry("startLine", 2);
    }

    @Test
    void propagatesTheExactRunCancellationTokenToToolInvocation() {
        AtomicReference<CancellationToken> observed = new AtomicReference<>();
        AgentTool tool = new RecordingAgentTool(
                "cancel-aware",
                ignored -> ToolValidationResult.validResult(),
                invocation -> {
                    observed.set(invocation.cancellationToken());
                    return ToolExecutionOutcome.success("ok");
                });
        PipelineFixture fixture = fixture(tool);
        CancellationSource cancellation = new CancellationSource();

        fixture.pipeline().execute(
                fixture.session(),
                new RunId("run-1"),
                1,
                new ToolCall("call-1", fixture.toolName(), JsonObject.empty()),
                cancellation.token());

        assertThat(observed.get()).isSameAs(cancellation.token());
    }

    private static AgentTool toolWithLimit(String content, int limit) {
        return new AgentTool() {
            private final ToolDefinition definition = new ToolDefinition(
                    "bounded",
                    "测试 Pipeline 最终输出上限",
                    "{\"type\":\"object\"}",
                    io.github.liumaishenjian.ccjava.domain.ToolEffect.READ_WORKSPACE,
                    io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN,
                    false,
                    Duration.ofSeconds(1),
                    "text/plain",
                    limit);

            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolExecutionOutcome execute(ToolInvocation invocation) {
                return ToolExecutionOutcome.success(content);
            }
        };
    }

    private static PipelineFixture fixture(AgentTool tool) {
        RecordingAgentEventSink events = new RecordingAgentEventSink();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, events);
        SequentialAgentIdGenerator ids = new SequentialAgentIdGenerator();
        InMemorySessionStore sessions = new InMemorySessionStore(ids, lifecycle);
        AgentSession session = sessions.create(SessionSpec.of("test"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                new ToolRegistry(List.of(tool)),
                (ignoredInvocation, ignoredDefinition) -> PermissionDecision.ALLOW,
                (ignoredInvocation, ignoredDefinition) -> PermissionDecision.ALLOW,
                lifecycle);
        return new PipelineFixture(pipeline, session, tool.definition().name());
    }

    private record PipelineFixture(
            ToolExecutionPipeline pipeline,
            AgentSession session,
            String toolName) {

        ToolResult execute() {
            return pipeline.execute(
                    session,
                    new RunId("run-1"),
                    1,
                    new ToolCall("call-1", toolName, JsonObject.empty()));
        }
    }
}
