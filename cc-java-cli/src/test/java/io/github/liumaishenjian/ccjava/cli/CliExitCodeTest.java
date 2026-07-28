package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import org.junit.jupiter.api.Test;

class CliExitCodeTest {

    private static final SessionId SESSION = new SessionId("exit-session");
    private static final RunId RUN = new RunId("exit-run");

    @Test
    void mapsRuntimeStopReasonsToStableProcessCategories() {
        assertThat(CliExitCode.from(AgentRunResult.completed(
                        SESSION,
                        RUN,
                        "done",
                        1,
                        0)))
                .isEqualTo(CliExitCode.SUCCESS);
        assertThat(CliExitCode.from(stopped(StopReason.USER_CANCELLED)))
                .isEqualTo(CliExitCode.CANCELLED);
        assertThat(CliExitCode.from(stopped(StopReason.MODEL_ERROR)))
                .isEqualTo(CliExitCode.MODEL_FAILURE);
        assertThat(CliExitCode.from(stopped(StopReason.TIME_LIMIT_REACHED)))
                .isEqualTo(CliExitCode.LIMIT_REACHED);
        assertThat(CliExitCode.from(stopped(StopReason.MODEL_OUTPUT_LIMIT_REACHED)))
                .isEqualTo(CliExitCode.LIMIT_REACHED);
        assertThat(CliExitCode.from(stopped(StopReason.INTERNAL_ERROR)))
                .isEqualTo(CliExitCode.INTERNAL_ERROR);
        assertThat(CliExitCode.from(stopped(StopReason.INVALID_MODEL_RESPONSE)))
                .isEqualTo(CliExitCode.RUNTIME_STOPPED);
    }

    @Test
    void keepsAllPublishedExitCodesDistinct() {
        assertThat(CliExitCode.values())
                .extracting(CliExitCode::code)
                .doesNotHaveDuplicates();
    }

    private static AgentRunResult stopped(StopReason reason) {
        return AgentRunResult.stopped(SESSION, RUN, reason, 1, 0);
    }
}
