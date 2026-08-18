package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlanProposalParserTest {
    private final PlanProposalParser parser = new PlanProposalParser();

    @Test
    void normalizesRuntimeOwnedFieldsAndSequentialOrdinals() {
        var plan = parser.parse("""
                {"objective":"  inspect safely  ","steps":[
                  {"title":" read ","detail":" inspect source "},
                  {"title":" change ","detail":" apply after approval "}
                ]}
                """, "plan-run-1", "abc123");

        assertThat(plan.id()).isEqualTo("plan-run-1");
        assertThat(plan.objective()).isEqualTo("inspect safely");
        assertThat(plan.workspaceDigest()).isEqualTo("abc123");
        assertThat(plan.steps()).extracting(step -> step.ordinal()).containsExactly(1, 2);
        assertThat(plan.steps()).allMatch(step -> step.expectedDigest().equals("abc123"));
    }

    @Test
    void rejectsMalformedWrappedAndUnknownFields() {
        assertThatThrownBy(() -> parser.parse("```json\n{}\n```", "plan-1", "digest"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(
                "{\"objective\":\"x\",\"steps\":[{\"title\":\"x\",\"detail\":\"x\",\"command\":\"rm\"}]}",
                "plan-1", "digest")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {"objective":"x","steps":[{"title":"x","detail":"x","action":{
                  "toolName":"agent_run","arguments":{},"safePreview":"forged"}}]}
                """, "plan-1", "digest")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesUtf8AndFieldCeilings() {
        String tooManySteps = "{\"objective\":\"x\",\"steps\":[" +
                java.util.stream.IntStream.range(0, PlanProposalParser.MAX_STEPS + 1)
                        .mapToObj(ignored -> "{\"title\":\"x\",\"detail\":\"x\"}")
                        .collect(java.util.stream.Collectors.joining(",")) + "]}";
        assertThatThrownBy(() -> parser.parse(tooManySteps, "plan-1", "digest"))
                .isInstanceOf(IllegalArgumentException.class);
        String title = "x".repeat(PlanProposalParser.MAX_TITLE_CODE_POINTS + 1);
        assertThatThrownBy(() -> parser.parse(
                "{\"objective\":\"x\",\"steps\":[{\"title\":\"" + title
                        + "\",\"detail\":\"x\"}]}", "plan-1", "digest"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
