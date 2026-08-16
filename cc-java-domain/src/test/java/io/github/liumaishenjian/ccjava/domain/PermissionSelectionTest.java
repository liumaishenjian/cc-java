package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PermissionSelectionTest {

    @Test
    void mapsThreeSelectionsWithoutProducingAcceptEdits() {
        assertThat(PermissionSelection.PLAN.mode()).isEqualTo(PermissionMode.PLAN);
        assertThat(PermissionSelection.PLAN.reviewer()).isEqualTo(ApprovalReviewer.USER);
        assertThat(PermissionSelection.ASK.mode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(PermissionSelection.ASK.reviewer()).isEqualTo(ApprovalReviewer.USER);
        assertThat(PermissionSelection.AUTO.mode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(PermissionSelection.AUTO.reviewer()).isEqualTo(ApprovalReviewer.AUTO_REVIEW);
        assertThat(PermissionSelection.values())
                .extracting(PermissionSelection::mode)
                .doesNotContain(PermissionMode.ACCEPT_EDITS);
    }
}
