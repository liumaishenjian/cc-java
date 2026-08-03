package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FixedPermissionGateTest {

    private final ToolInvocation invocation = new ToolInvocation(
            new SessionId("session-1"),
            new RunId("run-1"),
            1,
            new ToolCall("call-1", "fake", JsonObject.empty()));

    @Test
    void defaultAllowsReadsAsksForLocalSideEffectsAndDeniesExternalEffects() {
        FixedPermissionGate gate = new FixedPermissionGate(PermissionMode.DEFAULT);

        assertThat(gate.evaluate(invocation, definition(ToolEffect.READ_WORKSPACE)).decision())
                .isEqualTo(PermissionDecision.ALLOW);
        assertThat(gate.evaluate(invocation, definition(ToolEffect.WRITE_WORKSPACE)).decision())
                .isEqualTo(PermissionDecision.ASK);
        assertThat(gate.evaluate(invocation, definition(ToolEffect.EXECUTE_PROCESS)).decision())
                .isEqualTo(PermissionDecision.ASK);
        assertThat(gate.evaluate(invocation, definition(ToolEffect.NETWORK_OR_REMOTE)).decision())
                .isEqualTo(PermissionDecision.DENY);
        assertThat(gate.evaluate(invocation, definition(ToolEffect.SYSTEM_OR_DESTRUCTIVE)).decision())
                .isEqualTo(PermissionDecision.DENY);
    }

    @Test
    void planAllowsOnlyReads() {
        FixedPermissionGate gate = new FixedPermissionGate(PermissionMode.PLAN);

        for (ToolEffect effect : ToolEffect.values()) {
            PermissionDecision expected = effect == ToolEffect.READ_WORKSPACE
                    ? PermissionDecision.ALLOW
                    : PermissionDecision.DENY;
            assertThat(gate.evaluate(invocation, definition(effect)).decision())
                    .isEqualTo(expected);
        }
    }

    private ToolDefinition definition(ToolEffect effect) {
        return new ToolDefinition(
                "fake",
                "Fake tool",
                "{\"type\":\"object\"}",
                effect,
                ToolSource.BUILT_IN,
                true,
                Duration.ofSeconds(1),
                "text/plain",
                1024);
    }
}
