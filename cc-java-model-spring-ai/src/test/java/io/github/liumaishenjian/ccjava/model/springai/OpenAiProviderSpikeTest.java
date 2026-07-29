package io.github.liumaishenjian.ccjava.model.springai;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings;
import io.github.liumaishenjian.ccjava.model.springai.config.ProviderSettingsLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用维护者本地配置执行真实 OpenAI-compatible Provider Spike。
 *
 * <p>该测试默认不运行，也不属于普通 CI 前提。显式设置
 * {@code -Dccjava.real-provider=true} 后，测试只断言协议结构，
 * 不断言固定自然语言，更不会输出 API Key、完整地址或 Prompt。</p>
 *
 * @since 0.1.0
 */
@EnabledIfSystemProperty(named = "ccjava.real-provider", matches = "true")
class OpenAiProviderSpikeTest {

    @Test
    void streamsTextThenReturnsRawToolCallWithUsageAndFinishReason() throws Exception {
        OpenAiCompatibleSettings settings = new ProviderSettingsLoader().load(repositoryRoot());
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new OpenAiCompatibleModelFactory().create(settings),
                settings.model());

        List<String> deltas = new ArrayList<>();
        ModelTurn textTurn = gateway.complete(
                request(
                        "text-run",
                        List.of(
                                new SystemMessage("Return one short plain-text greeting."),
                                new UserMessage("Say hello.")),
                        List.of()),
                deltas::add,
                CancellationToken.none());

        assertThat(deltas).isNotEmpty().doesNotContainNull();
        assertThat(String.join("", deltas)).isEqualTo(textTurn.assistantMessage().text());
        assertThat(textTurn.assistantMessage().text()).isNotBlank();
        assertThat(textTurn.assistantMessage().toolCalls()).isEmpty();
        assertThat(textTurn.metadata().finishReason()).isEqualTo(ModelFinishReason.STOP);
        assertThat(textTurn.metadata().usage()).isPresent();
        assertThat(textTurn.metadata().providerModel()).isPresent();

        ToolDefinition probe = ToolDefinition.readOnlyText(
                "record_probe",
                "Record exactly one probe value. Use this tool whenever the user asks to record a probe.",
                """
                {
                  "type": "object",
                  "properties": {"value": {"type": "string"}},
                  "required": ["value"],
                  "additionalProperties": false
                }
                """);
        ModelTurn toolTurn = gateway.complete(
                request(
                        "tool-run",
                        List.of(
                                new SystemMessage(
                                        "You must call record_probe exactly once and must not answer with text."),
                                new UserMessage("Record the probe value S02_REAL_TOOL_CALL.")),
                        List.of(probe)),
                ignored -> {
                },
                CancellationToken.none());

        assertThat(toolTurn.metadata().finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(toolTurn.metadata().usage()).isPresent();
        assertThat(toolTurn.assistantMessage().toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isNotBlank();
            assertThat(call.name()).isEqualTo("record_probe");
            assertThat(call.arguments().values())
                    .containsEntry("value", "S02_REAL_TOOL_CALL");
        });
    }

    /**
     * 单独验证当前真实 Provider 是否能在同一 Assistant Turn 产生两个调用。
     *
     * <p>该能力与 Adapter 的本机 SSE Contract Test 分开启用；Provider 不支持时，
     * 失败结果作为兼容性证据，不影响文本和单 Tool Call 连通性验证。</p>
     */
    @Test
    @EnabledIfSystemProperty(named = "ccjava.real-provider-multi-tool", matches = "true")
    void returnsTwoToolCallsInOneAssistantTurn() throws Exception {
        OpenAiCompatibleSettings settings = new ProviderSettingsLoader().load(repositoryRoot());
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new OpenAiCompatibleModelFactory().create(settings),
                settings.model());
        ToolDefinition firstProbe = ToolDefinition.readOnlyText(
                "record_first_probe",
                "Record the first probe. Always use this tool when asked for the first probe.",
                """
                {
                  "type": "object",
                  "properties": {"value": {"type": "string"}},
                  "required": ["value"],
                  "additionalProperties": false
                }
                """);
        ToolDefinition secondProbe = ToolDefinition.readOnlyText(
                "record_second_probe",
                "Record the second probe. Always use this tool when asked for the second probe.",
                """
                {
                  "type": "object",
                  "properties": {"value": {"type": "string"}},
                  "required": ["value"],
                  "additionalProperties": false
                }
                """);
        ModelTurn multiToolTurn = gateway.complete(
                request(
                        "multi-tool-run",
                        List.of(
                                new SystemMessage(
                                        "Call record_first_probe once with FIRST, then "
                                                + "record_second_probe once with SECOND in the same "
                                                + "assistant turn. Do not answer with text."),
                                new UserMessage("Record both probes now.")),
                        List.of(firstProbe, secondProbe)),
                ignored -> {
                },
                CancellationToken.none());

        assertThat(multiToolTurn.metadata().finishReason())
                .isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(multiToolTurn.assistantMessage().toolCalls())
                .extracting(call -> call.name())
                .containsExactly("record_first_probe", "record_second_probe");
        assertThat(multiToolTurn.assistantMessage().toolCalls())
                .extracting(call -> call.arguments().values().get("value"))
                .containsExactly("FIRST", "SECOND");
        assertThat(multiToolTurn.assistantMessage().toolCalls())
                .extracting(call -> call.id())
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).isNotBlank());
    }

    private static ModelRequest request(
            String runId,
            List<io.github.liumaishenjian.ccjava.domain.AgentMessage> messages,
            List<ToolDefinition> definitions) {
        return new ModelRequest(
                new SessionId("s02-real-provider-spike"),
                new RunId(runId),
                1,
                messages,
                definitions);
    }

    private static Path repositoryRoot() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
    }
}
