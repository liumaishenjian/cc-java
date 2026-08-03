package io.github.liumaishenjian.ccjava.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证脱敏模型失败摘要不能退化成任意文本通道。
 */
class ModelFailureSummaryTest {

    @Test
    void acceptsProviderUnavailableWithBoundedAttempts() {
        ModelFailureSummary summary = new ModelFailureSummary(
                ModelFailureCategory.PROVIDER_UNAVAILABLE,
                Optional.of(ModelHttpStatusClass.SERVER_ERROR),
                3,
                false);

        assertThat(summary.attempts()).isEqualTo(3);
        assertThat(summary.statusClass()).contains(ModelHttpStatusClass.SERVER_ERROR);
    }

    @Test
    void rejectsInvalidStatusAndTerminalCombinations() {
        assertThatThrownBy(() -> new ModelFailureSummary(
                ModelFailureCategory.PROVIDER_UNAVAILABLE,
                Optional.of(ModelHttpStatusClass.CLIENT_ERROR),
                1,
                false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelFailureSummary(
                ModelFailureCategory.NETWORK_ERROR,
                Optional.of(ModelHttpStatusClass.SERVER_ERROR),
                1,
                false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelFailureSummary(
                ModelFailureCategory.INCOMPLETE_STREAM,
                Optional.empty(),
                1,
                false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelFailureSummary(
                ModelFailureCategory.PROVIDER_ERROR,
                Optional.empty(),
                0,
                false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentRunResultOnlyAllowsSummaryForModelFailure() {
        ModelFailureSummary summary = ModelFailureSummary.firstAttempt(
                ModelFailureCategory.NETWORK_ERROR,
                Optional.empty(),
                false);

        assertThatThrownBy(() -> AgentRunResult.stopped(
                new SessionId("session-1"),
                new RunId("run-1"),
                StopReason.USER_CANCELLED,
                Optional.of(summary),
                1,
                0)).isInstanceOf(IllegalArgumentException.class);
    }
}
