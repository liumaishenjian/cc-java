package io.github.liumaishenjian.ccjava.cli.instructions;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionActivation;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionCandidate;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionScopeKind;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionSourceKind;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserInstructionRootGuardTest {

    @TempDir
    Path temporary;

    @Test
    void readsOnlyFixedUserInstructionTarget() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path directory = Files.createDirectories(home.resolve(".cc-java/instructions"));
        Files.writeString(directory.resolve("AGENTS.md"), "# user rules\n只读");
        Files.writeString(directory.resolve("other.md"), "must not load");

        var result = new UserInstructionRootGuard(home).load();

        assertThat(result.text()).contains("# user rules\n只读");
        assertThat(result.diagnostic()).isEmpty();
        assertThat(result.toString()).doesNotContain("# user rules", home.toString());
    }

    @Test
    void loaderRejectsForgedUserCandidateIdentifier() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path directory = Files.createDirectories(home.resolve(".cc-java/instructions"));
        Files.writeString(directory.resolve("AGENTS.md"), "user rules");
        UserInstructionLoader loader = new UserInstructionLoader(new UserInstructionRootGuard(home));
        InstructionCandidate forged = new InstructionCandidate(InstructionSourceKind.USER,
                InstructionScopeKind.USER_GLOBAL, "other-user-source", 0, InstructionActivation.STARTUP);

        var result = loader.load(forged, CancellationToken.none());

        assertThat(result.loaded()).isEmpty();
        assertThat(result.failureCode()).contains(InstructionDiagnosticCode.UNREADABLE);
    }

    @Test
    void rejectsSameSizeMutationAfterReadWithoutPublishingEitherBody() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path directory = Files.createDirectories(home.resolve(".cc-java/instructions"));
        Path target = Files.writeString(directory.resolve("AGENTS.md"), "before");
        Files.setLastModifiedTime(target, FileTime.from(Instant.parse("2026-08-06T00:00:00Z")));
        UserInstructionRootGuard guard = new UserInstructionRootGuard(home);

        var result = guard.load(() -> {
            try {
                Files.writeString(target, "after!");
                Files.setLastModifiedTime(target, FileTime.from(Instant.parse("2026-08-06T00:00:01Z")));
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        });

        assertSafeDiagnostic(result, UserInstructionRootGuard.UserInstructionDiagnostic.IDENTITY_CHANGED);
        assertThat(result.toString()).doesNotContain("before", "after!");
    }

    @Test
    void returnsSafeDiagnosticForMissingAndInvalidContent() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        UserInstructionRootGuard missing = new UserInstructionRootGuard(home);
        assertThat(missing.load().text()).isEmpty();
        assertThat(missing.load().diagnostic()).contains(UserInstructionRootGuard.UserInstructionDiagnostic.ROOT_UNAVAILABLE);

        Path directory = Files.createDirectories(home.resolve(".cc-java/instructions"));
        Path target = directory.resolve("AGENTS.md");
        Files.write(target, new byte[] {'a', 0, 'b'});
        assertSafeDiagnostic(new UserInstructionRootGuard(home).load(), UserInstructionRootGuard.UserInstructionDiagnostic.NUL_BYTE);

        Files.write(target, new byte[] {(byte) 0xC3, 0x28});
        assertSafeDiagnostic(new UserInstructionRootGuard(home).load(), UserInstructionRootGuard.UserInstructionDiagnostic.INVALID_UTF8);
    }

    @Test
    void rejectsByteAndLineLimitsWithoutEchoingContent() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path directory = Files.createDirectories(home.resolve(".cc-java/instructions"));
        Path target = directory.resolve("AGENTS.md");
        Files.write(target, new byte[UserInstructionRootGuard.MAX_BYTES + 1]);
        assertSafeDiagnostic(new UserInstructionRootGuard(home).load(), UserInstructionRootGuard.UserInstructionDiagnostic.BYTE_LIMIT);

        String content = "line\n".repeat(UserInstructionRootGuard.MAX_LINES);
        Files.writeString(target, content);
        assertSafeDiagnostic(new UserInstructionRootGuard(home).load(), UserInstructionRootGuard.UserInstructionDiagnostic.LINE_LIMIT);
    }

    @Test
    void rejectsExternalSymbolicLinkWhenPlatformAllowsCreation() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path directory = Files.createDirectories(home.resolve(".cc-java/instructions"));
        Path outside = Files.writeString(temporary.resolve("outside.md"), "private content");
        try {
            Files.createSymbolicLink(directory.resolve("AGENTS.md"), outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.abort("当前环境不能创建 Symlink: " + exception.getClass().getSimpleName());
        }

        var result = new UserInstructionRootGuard(home).load();

        assertSafeDiagnostic(result, UserInstructionRootGuard.UserInstructionDiagnostic.TARGET_LINK_OR_TYPE);
        assertThat(result.toString()).doesNotContain(home.toString()).doesNotContain("private content");
    }

    private static void assertSafeDiagnostic(
            UserInstructionRootGuard.UserInstructionLoadResult result,
            UserInstructionRootGuard.UserInstructionDiagnostic expected) {
        assertThat(result.text()).isEmpty();
        assertThat(result.diagnostic()).contains(expected);
    }
}
