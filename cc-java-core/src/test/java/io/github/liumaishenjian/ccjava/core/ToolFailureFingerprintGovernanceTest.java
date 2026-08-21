package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolFailureFingerprintGovernanceTest {
    @Test
    void canonicalizesObjectKeysButAllowsChangedArgumentsAndCategories() {
        ToolFailureFingerprintGovernance governance = new ToolFailureFingerprintGovernance();
        ToolCall first = call("one", ordered("query", "same", "limit", 5));
        ToolCall reordered = call("two", ordered("limit", 5, "query", "same"));
        governance.record(first, ToolError.of(ToolErrorCode.WEB_SEARCH_FORBIDDEN, "ignored prose"));

        assertThat(governance.repeated(reordered)).isTrue();
        assertThat(governance.repeated(call("three", ordered("query", "changed", "limit", 5)))).isFalse();
        governance.recordSuccess(call("progress", ordered("query", "changed", "limit", 5)), ToolEffect.READ_WORKSPACE);
        assertThat(governance.repeated(reordered)).isFalse();
        assertThat(ToolFailureFingerprintGovernance.repeatedFailure().details().values())
                .containsEntry("requiredStrategyChange", true)
                .containsEntry("allowedChanges", List.of("query", "provider", "source", "arguments", "explanation"));
    }

    @Test
    void unrelatedToolSuccessDoesNotClearFailureButSameToolSuccessDoes() {
        ToolFailureFingerprintGovernance governance = new ToolFailureFingerprintGovernance();
        ToolCall forbidden = call("forbidden", ordered("query", "same"));
        governance.record(forbidden, ToolError.of(ToolErrorCode.WEB_SEARCH_FORBIDDEN, "ignored prose"));

        governance.recordSuccess(
                new ToolCall("status", "git_status", JsonObject.empty()), ToolEffect.READ_WORKSPACE);
        assertThat(governance.repeated(call("retry", ordered("query", "same")))).isTrue();
        governance.recordSuccess(
                new ToolCall("patch", "apply_patch", JsonObject.empty()), ToolEffect.WRITE_WORKSPACE);
        assertThat(governance.repeated(call("retry-after-write", ordered("query", "same")))).isTrue();

        governance.recordSuccess(
                call("changed", ordered("query", "different")), ToolEffect.NETWORK_OR_REMOTE);
        assertThat(governance.repeated(call("retry-after-success", ordered("query", "same")))).isFalse();
    }

    @Test
    void workspaceWriteReleasesOnlyProcessFailureWhosePreconditionsItCanChange() {
        ToolFailureFingerprintGovernance governance = new ToolFailureFingerprintGovernance();
        ToolCall test = new ToolCall("test", "run_command", new JsonObject(Map.of("command", "test")));
        governance.record(test, ToolError.of(ToolErrorCode.PROCESS_EXIT, "test failed"));

        governance.recordSuccess(
                new ToolCall("patch", "apply_patch", JsonObject.empty()), ToolEffect.WRITE_WORKSPACE);

        assertThat(governance.repeated(new ToolCall(
                "retry", "run_command", new JsonObject(Map.of("command", "test"))))).isFalse();
    }

    private static ToolCall call(String id, Map<String, Object> args) {
        return new ToolCall(id, "web_search", new JsonObject(args));
    }
    private static Map<String, Object> ordered(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
