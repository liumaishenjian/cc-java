package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalReviewRequestTest {

    @Test
    void rejectsOversizedOrControlBearingSafeProjection() {
        assertThatThrownBy(() -> request("x".repeat(513), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("safe\nunsafe", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsRecentContextByItemCountAndTotalAndCopiesIt() {
        ApprovalReviewContextItem item = new ApprovalReviewContextItem(
                ApprovalReviewContextItem.Role.ASSISTANT, "安全摘要");
        assertThatThrownBy(() -> request("安全", java.util.Collections.nCopies(9, item)))
                .isInstanceOf(IllegalArgumentException.class);
        ApprovalReviewContextItem large = new ApprovalReviewContextItem(
                ApprovalReviewContextItem.Role.TOOL_RESULT, "x".repeat(256));
        assertThatThrownBy(() -> request("安全", List.of(large, large, large, large, large)))
                .isInstanceOf(IllegalArgumentException.class);

        List<ApprovalReviewContextItem> mutable = new ArrayList<>(List.of(item));
        ApprovalReviewRequest request = request("安全", mutable);
        mutable.clear();
        assertThat(request.recentContext()).containsExactly(item);
        assertThatThrownBy(() -> request.recentContext().add(item))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ApprovalReviewRequest request(String summary, List<ApprovalReviewContextItem> recentContext) {
        return new ApprovalReviewRequest(
                new SessionId("session-1"),
                new RunId("run-1"),
                "call-1",
                "run_command",
                ToolEffect.EXECUTE_PROCESS,
                ToolSource.BUILT_IN,
                true,
                summary,
                recentContext);
    }
}
