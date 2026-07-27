package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void rejectsDuplicateToolNamesDuringRegistration() {
        RecordingAgentTool first = RecordingAgentTool.succeeding("duplicate", "first");
        RecordingAgentTool second = RecordingAgentTool.succeeding("duplicate", "second");

        assertThatThrownBy(() -> new ToolRegistry(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复 Tool 名称")
                .hasMessageContaining("duplicate");
    }

    @Test
    void preservesRegistrationOrderAndReturnsImmutableDefinitions() {
        ToolRegistry registry = new ToolRegistry(List.of(
                RecordingAgentTool.succeeding("first", "1"),
                RecordingAgentTool.succeeding("second", "2")));

        assertThat(registry.definitions())
                .extracting(ToolDefinition::name)
                .containsExactly("first", "second");
        assertThatThrownBy(() -> registry.definitions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
