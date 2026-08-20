package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentLimitsTest {

    @Test
    void keepsCompatibilityConstructorAndValidatesWallClockLimit() {
        AgentLimits compatible = new AgentLimits(2, 3);
        AgentLimits explicit = new AgentLimits(2, 3, Duration.ofSeconds(4));

        assertThat(compatible.maxDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(explicit.maxDuration()).isEqualTo(Duration.ofSeconds(4));
        assertThat(compatible.budgetPolicy()).isEqualTo(AgentBudgetPolicy.EXPLICIT_HARD);
        AgentLimits interactive = AgentLimits.interactive(Duration.ofSeconds(9));
        assertThat(interactive.budgetPolicy()).isEqualTo(AgentBudgetPolicy.INTERACTIVE_ADAPTIVE);
        assertThat(interactive.absoluteMaxModelTurns()).isGreaterThan(interactive.maxModelTurns());
        assertThat(interactive.absoluteMaxToolCalls()).isGreaterThan(interactive.maxToolCalls());
        assertThatThrownBy(() -> new AgentLimits(2, 3, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentLimits(2, 3, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
