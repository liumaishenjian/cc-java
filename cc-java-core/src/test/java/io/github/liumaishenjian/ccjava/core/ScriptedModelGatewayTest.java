package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptedModelGatewayTest {

    @Test
    void consumesTurnsInFifoOrderAndRecordsEveryRequest() {
        ModelTurn firstTurn = ModelTurn.text("first");
        ModelTurn secondTurn = ModelTurn.text("second");
        ScriptedModelGateway gateway = ScriptedModelGateway.of(firstTurn, secondTurn);
        ModelRequest firstRequest = request(1, "run-1", "问题一");
        ModelRequest secondRequest = request(2, "run-1", "问题二");

        ModelTurn actualFirst = gateway.complete(firstRequest);
        ModelTurn actualSecond = gateway.complete(secondRequest);

        assertThat(actualFirst).isEqualTo(firstTurn);
        assertThat(actualSecond).isEqualTo(secondTurn);
        assertThat(gateway.requests()).containsExactly(firstRequest, secondRequest);
        assertThat(gateway.remainingTurns()).isZero();
    }

    @Test
    void keepsImmutableRequestSnapshots() {
        List<AgentMessage> mutableMessages = new ArrayList<>();
        mutableMessages.add(new SystemMessage("系统指令"));
        mutableMessages.add(new UserMessage("最初问题"));
        List<ToolDefinition> mutableDefinitions = new ArrayList<>();
        mutableDefinitions.add(definition("first_tool"));
        ModelRequest request = new ModelRequest(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                mutableMessages,
                mutableDefinitions);
        ScriptedModelGateway gateway = ScriptedModelGateway.of(ModelTurn.text("完成"));

        gateway.complete(request);
        mutableMessages.add(new UserMessage("事后修改"));
        mutableDefinitions.add(definition("late_tool"));

        ModelRequest recorded = gateway.requests().get(0);
        assertThat(recorded.messages()).containsExactly(
                new SystemMessage("系统指令"),
                new UserMessage("最初问题"));
        assertThat(recorded.toolDefinitions())
                .extracting(ToolDefinition::name)
                .containsExactly("first_tool");
        assertThatThrownBy(() -> gateway.requests().add(request))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> recorded.messages().add(new UserMessage("不能修改")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ModelRequest request(
            int turnNumber,
            String runId,
            String userMessage) {
        return new ModelRequest(
                new SessionId("session-1"),
                new RunId(runId),
                turnNumber,
                List.of(
                        new SystemMessage("系统指令"),
                        new UserMessage(userMessage)),
                List.of(definition("echo")));
    }

    private static ToolDefinition definition(String name) {
        return ToolDefinition.readOnlyText(
                name,
                "测试 Tool",
                """
                {
                  "type": "object"
                }
                """);
    }
}
