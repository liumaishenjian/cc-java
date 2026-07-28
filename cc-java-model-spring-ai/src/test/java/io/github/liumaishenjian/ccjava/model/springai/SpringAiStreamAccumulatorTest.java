package io.github.liumaishenjian.ccjava.model.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 验证流式文本、多个 Tool Call、Usage 与终止原因的确定性聚合。
 */
class SpringAiStreamAccumulatorTest {

    @Test
    void aggregatesInterleavedToolFragmentsAndPreservesOrder() {
        SpringAiStreamAccumulator accumulator = new SpringAiStreamAccumulator();

        assertThat(accumulator.accept(response(
                "Hel",
                List.of(new AssistantMessage.ToolCall(
                        "call-a", "function", "sum_numbers", "{\"a\":")),
                null,
                null))).isEqualTo("Hel");
        assertThat(accumulator.accept(response(
                "lo",
                List.of(
                        new AssistantMessage.ToolCall(
                                "call-a", "function", "sum_numbers", "{\"a\":2,\"b\":3}"),
                        new AssistantMessage.ToolCall(
                                "call-b", "function", "repeat_text", "{\"text\":\"s02\"}")),
                "stop",
                new DefaultUsage(12, 7, 19)))).isEqualTo("lo");

        var turn = accumulator.finish();

        assertThat(turn.assistantMessage().text()).isEqualTo("Hello");
        assertThat(turn.assistantMessage().toolCalls())
                .extracting(call -> call.id() + ":" + call.name())
                .containsExactly("call-a:sum_numbers", "call-b:repeat_text");
        assertThat(turn.assistantMessage().toolCalls().getFirst().arguments().values())
                .containsEntry("a", 2)
                .containsEntry("b", 3);
        assertThat(turn.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(turn.usage()).hasValueSatisfying(usage -> {
            assertThat(usage.inputTokens()).isEqualTo(12);
            assertThat(usage.outputTokens()).isEqualTo(7);
            assertThat(usage.totalTokens()).isEqualTo(19);
        });
    }

    @Test
    void mapsLengthAndKeepsMissingUsageUnknown() {
        SpringAiStreamAccumulator accumulator = new SpringAiStreamAccumulator();
        accumulator.accept(response("partial", List.of(), "length", null));

        var turn = accumulator.finish();

        assertThat(turn.finishReason()).isEqualTo(ModelFinishReason.LENGTH);
        assertThat(turn.usage()).isEmpty();
    }

    @Test
    void lengthTerminationTakesPriorityOverAValidToolCall() {
        SpringAiStreamAccumulator accumulator = new SpringAiStreamAccumulator();
        accumulator.accept(response(
                "partial",
                List.of(new AssistantMessage.ToolCall(
                        "call-a", "function", "sum_numbers", "{\"a\":2,\"b\":3}")),
                "length",
                null));

        var turn = accumulator.finish();

        assertThat(turn.finishReason()).isEqualTo(ModelFinishReason.LENGTH);
        assertThat(turn.assistantMessage().toolCalls())
                .extracting(call -> call.id() + ":" + call.name())
                .containsExactly("call-a:sum_numbers");
    }

    @Test
    void contentFilterTerminationTakesPriorityOverAValidToolCall() {
        SpringAiStreamAccumulator accumulator = new SpringAiStreamAccumulator();
        accumulator.accept(response(
                "",
                List.of(new AssistantMessage.ToolCall(
                        "call-a", "function", "sum_numbers", "{\"a\":2,\"b\":3}")),
                "content_filter",
                null));

        var turn = accumulator.finish();

        assertThat(turn.finishReason()).isEqualTo(ModelFinishReason.CONTENT_FILTER);
        assertThat(turn.assistantMessage().toolCalls()).hasSize(1);
    }

    @Test
    void rejectsConflictingFinishReasonsInsteadOfDowngradingSafetyTermination() {
        SpringAiStreamAccumulator accumulator = new SpringAiStreamAccumulator();
        accumulator.accept(response("partial", List.of(), "length", null));

        assertThatThrownBy(() -> accumulator.accept(response(
                "",
                List.of(new AssistantMessage.ToolCall(
                        "call-a", "function", "sum_numbers", "{\"a\":2,\"b\":3}")),
                "stop",
                null)))
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .satisfies(error -> assertThat(
                        ((SpringAiStreamAccumulator.ModelAggregationException) error).kind())
                        .isEqualTo(SpringAiStreamAccumulator.AggregationFailureKind
                                .INVALID_RESPONSE))
                .hasMessageContaining("冲突");
    }

    @Test
    void rejectsToolCallsFinishReasonWithoutAnyAggregatedCall() {
        SpringAiStreamAccumulator accumulator = new SpringAiStreamAccumulator();
        accumulator.accept(response("ordinary text", List.of(), "tool_calls", null));

        assertThatThrownBy(accumulator::finish)
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .satisfies(error -> assertThat(
                        ((SpringAiStreamAccumulator.ModelAggregationException) error).kind())
                        .isEqualTo(SpringAiStreamAccumulator.AggregationFailureKind
                                .INCOMPLETE_RESPONSE))
                .hasMessageContaining("finish reason")
                .hasMessageContaining("未返回任何 Tool Call");
    }

    @Test
    void rejectsCompletionWithoutFinishReasonAsIncomplete() {
        SpringAiStreamAccumulator accumulator = new SpringAiStreamAccumulator();
        accumulator.accept(response("partial", List.of(), null, null));

        assertThatThrownBy(accumulator::finish)
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .satisfies(error -> assertThat(
                        ((SpringAiStreamAccumulator.ModelAggregationException) error).kind())
                        .isEqualTo(SpringAiStreamAccumulator.AggregationFailureKind
                                .INCOMPLETE_RESPONSE))
                .hasMessageContaining("finish reason");
        assertThat(accumulator.hasPartialResponse()).isTrue();
    }

    @Test
    void rejectsMalformedOrNullToolArgumentsBeforeRuntimeCanExecuteThem() {
        SpringAiStreamAccumulator malformed = new SpringAiStreamAccumulator();
        malformed.accept(response(
                "",
                List.of(new AssistantMessage.ToolCall(
                        "call-a", "function", "sum_numbers", "{\"a\":")),
                "stop",
                null));

        assertThatThrownBy(malformed::finish)
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .hasMessageContaining("完整 JSON");

        SpringAiStreamAccumulator withNull = new SpringAiStreamAccumulator();
        withNull.accept(response(
                "",
                List.of(new AssistantMessage.ToolCall(
                        "call-b", "function", "repeat_text", "{\"text\":null}")),
                "stop",
                null));

        assertThatThrownBy(withNull::finish)
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .hasMessageContaining("不支持");
    }

    @Test
    void rejectsMissingAndChangingCallIdentity() {
        SpringAiStreamAccumulator missingId = new SpringAiStreamAccumulator();
        assertThatThrownBy(() -> missingId.accept(response(
                "",
                List.of(new AssistantMessage.ToolCall(
                        "", "function", "sum_numbers", "{}")),
                "stop",
                null)))
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .hasMessageContaining("稳定 ID");

        SpringAiStreamAccumulator changingName = new SpringAiStreamAccumulator();
        changingName.accept(response(
                "",
                List.of(new AssistantMessage.ToolCall(
                        "call-a", "function", "sum_numbers", "{")),
                null,
                null));
        assertThatThrownBy(() -> changingName.accept(response(
                "",
                List.of(new AssistantMessage.ToolCall(
                        "call-a", "function", "other_tool", "}")),
                "stop",
                null)))
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .hasMessageContaining("名称发生变化");
    }

    @Test
    void rejectsDuplicateToolCallIdWithinOneChunkButAllowsCrossChunkMerge() {
        SpringAiStreamAccumulator duplicate = new SpringAiStreamAccumulator();

        assertThatThrownBy(() -> duplicate.accept(response(
                "",
                List.of(
                        new AssistantMessage.ToolCall(
                                "call-a", "function", "sum_numbers", "{\"a\":"),
                        new AssistantMessage.ToolCall(
                                "call-a", "function", "sum_numbers", "2}")),
                "stop",
                null)))
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .satisfies(error -> assertThat(
                        ((SpringAiStreamAccumulator.ModelAggregationException) error).kind())
                        .isEqualTo(SpringAiStreamAccumulator.AggregationFailureKind
                                .INVALID_RESPONSE))
                .hasMessageContaining("重复 Tool Call ID");

        SpringAiStreamAccumulator crossChunk = new SpringAiStreamAccumulator();
        crossChunk.accept(response(
                "",
                List.of(new AssistantMessage.ToolCall(
                        "call-a", "function", "sum_numbers", "{\"a\":")),
                null,
                null));
        crossChunk.accept(response(
                "",
                List.of(new AssistantMessage.ToolCall(
                        "call-a", "function", "sum_numbers", "{\"a\":2}")),
                "stop",
                null));

        assertThat(crossChunk.finish().assistantMessage().toolCalls())
                .singleElement()
                .satisfies(call -> assertThat(call.arguments().values())
                        .containsEntry("a", 2));
    }

    @Test
    void enforcesUtf8ByteLimitBeforeRetainingMultibyteText() {
        SpringAiStreamAccumulator accumulator =
                new SpringAiStreamAccumulator(5, 2);

        assertThatThrownBy(() -> accumulator.accept(response(
                "你好",
                List.of(),
                "stop",
                null)))
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .satisfies(error -> assertThat(
                        ((SpringAiStreamAccumulator.ModelAggregationException) error).kind())
                        .isEqualTo(SpringAiStreamAccumulator.AggregationFailureKind
                                .RESPONSE_LIMIT_EXCEEDED));
        assertThat(accumulator.hasPartialResponse()).isFalse();
    }

    @Test
    void countsReplacedFinishReasonAgainstUtf8Limit() {
        SpringAiStreamAccumulator accumulator =
                new SpringAiStreamAccumulator(4, 2);

        assertThatThrownBy(() -> accumulator.accept(response(
                "",
                List.of(),
                "oversized",
                null)))
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .satisfies(error -> assertThat(
                        ((SpringAiStreamAccumulator.ModelAggregationException) error).kind())
                        .isEqualTo(SpringAiStreamAccumulator.AggregationFailureKind
                                .RESPONSE_LIMIT_EXCEEDED));
    }

    @Test
    void countsToolIdNameAndArgumentsAgainstSharedUtf8Limit() {
        SpringAiStreamAccumulator accumulator =
                new SpringAiStreamAccumulator(7, 2);

        assertThatThrownBy(() -> accumulator.accept(response(
                "",
                List.of(new AssistantMessage.ToolCall(
                        "id", "function", "tool", "{}")),
                "stop",
                null)))
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .satisfies(error -> assertThat(
                        ((SpringAiStreamAccumulator.ModelAggregationException) error).kind())
                        .isEqualTo(SpringAiStreamAccumulator.AggregationFailureKind
                                .RESPONSE_LIMIT_EXCEEDED));
    }

    @Test
    void enforcesDistinctToolCallCountLimitBeforeMergingChunk() {
        SpringAiStreamAccumulator accumulator =
                new SpringAiStreamAccumulator(1024, 1);

        assertThatThrownBy(() -> accumulator.accept(response(
                "",
                List.of(
                        new AssistantMessage.ToolCall(
                                "call-a", "function", "first", "{}"),
                        new AssistantMessage.ToolCall(
                                "call-b", "function", "second", "{}")),
                "stop",
                null)))
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .satisfies(error -> assertThat(
                        ((SpringAiStreamAccumulator.ModelAggregationException) error).kind())
                        .isEqualTo(SpringAiStreamAccumulator.AggregationFailureKind
                                .RESPONSE_LIMIT_EXCEEDED))
                .hasMessageContaining("数量");
    }

    @Test
    void classifiesInvalidProviderUsageWithoutLeakingRuntimeException() {
        SpringAiStreamAccumulator accumulator = new SpringAiStreamAccumulator();

        assertThatThrownBy(() -> accumulator.accept(response(
                "",
                List.of(),
                "stop",
                new DefaultUsage(-1, 1, 0))))
                .isInstanceOf(SpringAiStreamAccumulator.ModelAggregationException.class)
                .satisfies(error -> assertThat(
                        ((SpringAiStreamAccumulator.ModelAggregationException) error).kind())
                        .isEqualTo(SpringAiStreamAccumulator.AggregationFailureKind
                                .INVALID_RESPONSE))
                .hasMessageContaining("Token Usage");
    }

    private static ChatResponse response(
            String text,
            List<AssistantMessage.ToolCall> calls,
            String finishReason,
            DefaultUsage usage) {
        AssistantMessage output = AssistantMessage.builder()
                .content(text)
                .toolCalls(calls)
                .build();
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(finishReason)
                .build();
        ChatResponseMetadata responseMetadata = usage == null
                ? new ChatResponseMetadata()
                : ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(
                List.of(new Generation(output, generationMetadata)),
                responseMetadata);
    }
}
