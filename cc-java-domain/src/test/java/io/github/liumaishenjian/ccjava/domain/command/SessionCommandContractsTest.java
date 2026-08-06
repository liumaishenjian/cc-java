package io.github.liumaishenjian.ccjava.domain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.settings.SessionSettingsPatch;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionCommandContractsTest {
    @Test
    void terminalResultCarriesExactlyOneMatchingTerminalEvent() {
        CommandId id = new CommandId("command-1");
        SessionCommandEvent event = new SessionCommandEvent(SessionCommandKind.HELP, id, new SessionId("session"),
                SessionCommandStatus.SUCCEEDED, SessionCommandResultCode.OK,
                new SessionCommandEvent.EmptyPayload());

        SessionCommandResult result = new SessionCommandResult.Succeeded(event);

        assertThat(result.event().commandId()).isEqualTo(id);
        assertThat(result.event().status()).isEqualTo(SessionCommandStatus.SUCCEEDED);
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionCommandResult.Rejected(event));
    }

    @Test
    void inputTextIsBoundedAndRedactedFromStringRepresentation() {
        String anchor = "private anchor must not be emitted";
        String model = "private-model-name";

        String privateSessionId = "private-session-id";
        assertThat(new SessionCommandIntent.Compact(List.of(anchor)).toString()).doesNotContain(anchor);
        assertThat(new SessionCommandIntent.ModelChange(model).toString()).doesNotContain(model);
        assertThat(new SessionCommandIntent.Resume(new SessionId(privateSessionId)).toString()).doesNotContain(privateSessionId);
        assertThatIllegalArgumentException().isThrownBy(() -> new CommandId("\u0000"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionCommandIntent.Compact(List.of("x".repeat(513))));
    }

    @Test
    void sessionSettingsPatchAndPermissionProjectionRemainBoundedAndPrivate() {
        String model = "private-configured-model";
        assertThat(new SessionSettingsPatch.ModelName(model).toString()).doesNotContain(model);
        assertThat(new SessionSettingsPatch.PermissionModeChange(PermissionMode.PLAN).value())
                .isEqualTo(PermissionMode.PLAN);
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionSettingsPatch.ModelName("bad\nmodel"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionSettingsPatch.ModelName("x".repeat(257)));
        assertThatNullPointerException().isThrownBy(() -> new SessionSettingsPatch.PermissionModeChange(null));

        var operation = new SessionCommandIntent.PermissionsOperation.ModeChange(PermissionMode.ACCEPT_EDITS);
        assertThat(operation.mode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThatNullPointerException().isThrownBy(() -> new SessionCommandIntent.PermissionsOperation.ModeChange(null));

        var rule = new SessionCommandEvent.PermissionRuleProvenance(
                "read-docs", "PROJECT_SHARED", "project-shared", "REPLACE", "VALID");
        var payload = new SessionCommandEvent.PermissionsPayload(
                "PLAN", "PROJECT_SHARED", "project-shared", "VALID", 1, List.of(rule));
        assertThat(payload.toString()).doesNotContain("selector", "G:\\private");
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionCommandEvent.PermissionRuleProvenance(
                "read-docs", "PROJECT_SHARED", "G:\\private", "REPLACE", "VALID"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionCommandEvent.PermissionsPayload(
                "PLAN", "PROJECT_SHARED", "project-shared", "VALID", 2, List.of(rule)));
    }

    @Test
    void terminalStatusAndCodeMustBeConsistent() {
        CommandId id = new CommandId("status-code");
        SessionId sessionId = new SessionId("session");
        assertThatIllegalArgumentException().isThrownBy(() -> event(id, sessionId,
                SessionCommandStatus.SUCCEEDED, SessionCommandResultCode.ACTIVE_RUN));
        assertThatIllegalArgumentException().isThrownBy(() -> event(id, sessionId,
                SessionCommandStatus.CANCELLED, SessionCommandResultCode.OK));
        assertThatIllegalArgumentException().isThrownBy(() -> event(id, sessionId,
                SessionCommandStatus.FAILED, SessionCommandResultCode.UNAVAILABLE));
        assertThatIllegalArgumentException().isThrownBy(() -> event(id, sessionId,
                SessionCommandStatus.REJECTED, SessionCommandResultCode.OK));
        assertThatIllegalArgumentException().isThrownBy(() -> event(id, sessionId,
                SessionCommandStatus.REJECTED, SessionCommandResultCode.CANCELLED));
        assertThatIllegalArgumentException().isThrownBy(() -> event(id, sessionId,
                SessionCommandStatus.REJECTED, SessionCommandResultCode.INTERNAL_FAILURE));
    }

    @Test
    void doctorEntriesRejectPathAndFreeTextLikeValuesAndAreBounded() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionCommandEvent.DoctorEntry(
                "SETTINGS", "USER", "G:\\private", "PUBLISHED", "INFO"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionCommandEvent.DoctorEntry(
                "SETTINGS", "USER", "user", "raw error text", "INFO"));
        SessionCommandEvent.DoctorEntry entry = new SessionCommandEvent.DoctorEntry(
                "SETTINGS", "DEFAULTS", "defaults", "PUBLISHED", "INFO");
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionCommandEvent.DoctorPayload(
                false, 0, 0, false, false, java.util.Collections.nCopies(129, entry)));
    }

    private static SessionCommandEvent event(CommandId id, SessionId sessionId,
                                             SessionCommandStatus status, SessionCommandResultCode code) {
        return new SessionCommandEvent(SessionCommandKind.HELP, id, sessionId, status, code,
                new SessionCommandEvent.EmptyPayload());
    }
}
