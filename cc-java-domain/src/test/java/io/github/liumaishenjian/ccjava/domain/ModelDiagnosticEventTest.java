package io.github.liumaishenjian.ccjava.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证模型诊断 schema 在类型层保持封闭且无任意文本字段。 */
class ModelDiagnosticEventTest {

    @Test
    void exposesOnlyStrictWhitelistedFieldsAndCannotLeakSentinelsThroughToString() {
        Set<String> fields = Arrays.stream(ModelDiagnosticEvent.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        ModelDiagnosticEvent event = new ModelDiagnosticEvent(
                1,
                ModelDiagnosticKind.FAILURE,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                2,
                3,
                ModelFailureStage.STREAM_TRANSPORT,
                ModelFailureReason.TRANSPORT_CLOSED,
                ModelDiagnosticStatusClass.NONE,
                true,
                false,
                17,
                Instant.EPOCH);
        String rendered = event.toString();

        assertThat(fields).containsExactlyInAnyOrder(
                "schemaVersion", "kind", "sessionCorrelation", "runCorrelation", "turnNumber",
                "attemptNumber", "stage", "reason", "statusClass",
                "receivedProviderFrame", "emittedUserText", "elapsedMillis", "recordedAt");
        assertThat(Arrays.stream(ModelDiagnosticEvent.class.getRecordComponents())
                .map(RecordComponent::getType))
                .noneMatch(String.class::equals);
        assertThat(rendered).doesNotContain(
                "PROMPT_SENTINEL", "RESPONSE_SENTINEL", "ENDPOINT_SENTINEL",
                "HEADER_SENTINEL", "REQUEST_ID_SENTINEL", "PATH_SENTINEL",
                "EXCEPTION_SENTINEL", "OpenAIRetryableException");
    }

    @Test
    void enumerationsAreClosedToTheAdrContract() {
        assertThat(ModelDiagnosticMode.values()).containsExactly(
                ModelDiagnosticMode.OFF, ModelDiagnosticMode.SAFE, ModelDiagnosticMode.VERBOSE);
        assertThat(ModelFailureStage.values()).containsExactly(
                ModelFailureStage.REQUEST_TRANSPORT, ModelFailureStage.STREAM_TRANSPORT,
                ModelFailureStage.RESPONSE_DECODE, ModelFailureStage.FINISH_METADATA,
                ModelFailureStage.TOOL_ARGUMENTS);
        assertThat(ModelFailureReason.values()).containsExactly(
                ModelFailureReason.TRANSPORT_CLOSED, ModelFailureReason.NETWORK_IO,
                ModelFailureReason.TIMEOUT, ModelFailureReason.INVALID_RESPONSE,
                ModelFailureReason.FINISH_MISSING, ModelFailureReason.FINISH_INCONSISTENT,
                ModelFailureReason.TOOL_JSON_INVALID, ModelFailureReason.UNKNOWN);
    }
}
