package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolErrorTaxonomyTest {
    @Test
    void mapsStableCodesWithoutGuessingFromMessage() {
        assertThat(ToolError.of(ToolErrorCode.PERMISSION_DENIED, "free text").category())
                .isEqualTo(ToolFailureCategory.PERMISSION);
        assertThat(ToolError.of(ToolErrorCode.WEB_SEARCH_FORBIDDEN, "anything").category())
                .isEqualTo(ToolFailureCategory.HTTP_FORBIDDEN);
        assertThat(ToolError.of(ToolErrorCode.WEB_SEARCH_RATE_LIMITED, "anything").retryable()).isTrue();
        assertThat(ToolError.of(ToolErrorCode.WEB_SEARCH_REMOTE_SERVER_ERROR, "anything").retryable()).isTrue();
        assertThat(ToolError.of(ToolErrorCode.PROCESS_EXIT, "anything").retryable()).isFalse();
        assertThat(ToolError.of(ToolErrorCode.PROCESS_EXIT, "403 forbidden").category())
                .isEqualTo(ToolFailureCategory.PROCESS_EXIT);
    }
}
