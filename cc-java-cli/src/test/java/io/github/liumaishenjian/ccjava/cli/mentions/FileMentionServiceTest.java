package io.github.liumaishenjian.ccjava.cli.mentions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.UserFileAttachment;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 证伪显式文件提及绕过 Workspace 边界、行/字节预算或读前读后一致性检查。 */
class FileMentionServiceTest {

    @TempDir
    Path root;

    private Path workspace;
    private FileMentionService service;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(root.resolve("workspace"));
        service = new FileMentionService(new WorkspaceGuard(workspace));
    }

    private Path write(String relative, String content) throws IOException {
        Path target = workspace.resolve(relative);
        Files.createDirectories(target.getParent());
        return Files.writeString(target, content);
    }

    @Test
    void resolvesMentionAtStartAndAfterWhitespaceKeepingUserText() throws IOException {
        write("src/App.java", "one\ntwo\nthree\n");
        write("notes.md", "note\n");

        UserMessage message = service.resolve("@src/App.java explain and @notes.md too");

        assertThat(message.content()).isEqualTo("@src/App.java explain and @notes.md too");
        assertThat(message.attachments()).extracting(UserFileAttachment::protocolPath)
                .containsExactly("src/App.java", "notes.md");
        assertThat(message.attachments().getFirst().textSnapshot()).isEqualTo("one\ntwo\nthree");
        assertThat(message.attachments().getFirst().startLine()).isEqualTo(1);
        assertThat(message.attachments().getFirst().endLine()).isEqualTo(3);
        assertThat(message.attachments().getFirst().truncated()).isFalse();
    }

    @Test
    void ignoresTokensNotPrecededByStartOrWhitespace() throws IOException {
        write("a.txt", "x\n");

        UserMessage message = service.resolve("mail user@a.txt and path/@a.txt");

        assertThat(message.attachments()).isEmpty();
    }

    @Test
    void supportsQuotedPathsWithSpaces() throws IOException {
        write("dir with space/file name.txt", "hello\n");

        UserMessage message = service.resolve("look at @\"dir with space/file name.txt\" now");

        assertThat(message.attachments()).extracting(UserFileAttachment::protocolPath)
                .containsExactly("dir with space/file name.txt");
    }

    @Test
    void supportsSingleLineAndRangeSelectors() throws IOException {
        write("r.txt", "l1\nl2\nl3\nl4\n");

        UserMessage single = service.resolve("@r.txt#L2 check");
        UserMessage range = service.resolve("@r.txt#L2-3 check");

        assertThat(single.attachments().getFirst().textSnapshot()).isEqualTo("l2");
        assertThat(single.attachments().getFirst().startLine()).isEqualTo(2);
        assertThat(single.attachments().getFirst().endLine()).isEqualTo(2);
        assertThat(range.attachments().getFirst().textSnapshot()).isEqualTo("l2\nl3");
        assertThat(range.attachments().getFirst().endLine()).isEqualTo(3);
    }

    @Test
    void snapshotDigestIsLowercaseSha256OfSelectedText() throws IOException {
        write("d.txt", "alpha\n");

        UserFileAttachment attachment = service.resolve("@d.txt").attachments().getFirst();

        assertThat(attachment.sha256Digest()).isEqualTo(sha256("alpha"));
        assertThat(attachment.sha256Digest()).matches("[0-9a-f]{64}");
    }

    @Test
    void stripsUtf8BomFromTheModelVisibleSnapshot() throws IOException {
        Path target = workspace.resolve("bom.txt");
        byte[] body = "first\nsecond\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);
        Files.write(target, bytes);

        UserFileAttachment attachment = service.resolve("@bom.txt").attachments().getFirst();

        assertThat(attachment.textSnapshot()).isEqualTo("first\nsecond");
        assertThat(attachment.sha256Digest()).isEqualTo(sha256("first\nsecond"));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    void dedupesRepeatedMentionsInStableFirstSeenOrder() throws IOException {
        write("a.txt", "a\n");
        write("b.txt", "b\n");

        UserMessage message = service.resolve("@b.txt @a.txt @b.txt @a.txt");

        assertThat(message.attachments()).extracting(UserFileAttachment::protocolPath)
                .containsExactly("b.txt", "a.txt");
    }

    @Test
    void dedupesLexicalAliasesAfterWorkspaceNormalization() throws IOException {
        write("a.txt", "a\n");

        UserMessage message = service.resolve("@a.txt @./a.txt");

        assertThat(message.attachments()).extracting(UserFileAttachment::protocolPath)
                .containsExactly("a.txt");
    }

    @Test
    void distinctRangesOfSameFileAreSeparateAttachments() throws IOException {
        write("a.txt", "l1\nl2\nl3\n");

        UserMessage message = service.resolve("@a.txt#L1 @a.txt#L3");

        assertThat(message.attachments()).hasSize(2);
        assertThat(message.attachments()).extracting(UserFileAttachment::startLine)
                .containsExactly(1, 3);
    }

    @Test
    void acceptsExactlyEightMentionsAndRejectsNine() throws IOException {
        for (int index = 0; index < 9; index++) {
            write("f" + index + ".txt", "x\n");
        }
        String eight = IntStream.range(0, 8)
                .mapToObj(index -> "@f" + index + ".txt").reduce("ask", (a, b) -> a + " " + b);
        String nine = eight + " @f8.txt";

        assertThat(service.resolve(eight).attachments()).hasSize(8);
        assertThatThrownBy(() -> service.resolve(nine))
                .isInstanceOf(FileMentionException.class);
    }

    @Test
    void truncatesAtSelectedLineBudgetAndMarksTruncated() throws IOException {
        write("big.txt", "line\n".repeat(600));

        UserFileAttachment attachment = service.resolve("@big.txt").attachments().getFirst();

        assertThat(attachment.endLine()).isEqualTo(FileMentionService.MAX_SELECTED_LINES);
        assertThat(attachment.truncated()).isTrue();
        assertThat(attachment.textSnapshot().lines().count())
                .isEqualTo(FileMentionService.MAX_SELECTED_LINES);
    }

    @Test
    void exactlyFiveHundredSelectedLinesIsNotTruncated() throws IOException {
        write("exact.txt", "line\n".repeat(500));

        UserFileAttachment attachment = service.resolve("@exact.txt").attachments().getFirst();

        assertThat(attachment.endLine()).isEqualTo(500);
        assertThat(attachment.truncated()).isFalse();
    }

    @Test
    void truncatesAtPerAttachmentByteBudget() throws IOException {
        write("wide.txt", ("x".repeat(1_000) + "\n").repeat(100));

        UserFileAttachment attachment = service.resolve("@wide.txt").attachments().getFirst();

        assertThat(attachment.textSnapshot().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(FileMentionService.MAX_ATTACHMENT_BYTES);
        assertThat(attachment.truncated()).isTrue();
    }

    @Test
    void rejectsWhenTotalAttachmentBudgetExceeded() throws IOException {
        String line = "y".repeat(1_000) + "\n";
        for (int index = 0; index < 4; index++) {
            write("t" + index + ".txt", line.repeat(70));
        }

        assertThatThrownBy(() -> service.resolve("@t0.txt @t1.txt @t2.txt @t3.txt"))
                .isInstanceOf(FileMentionException.class);
    }

    @Test
    void rejectsAbsoluteTraversalMissingSensitiveAndDirectoryTargets() throws IOException {
        Files.writeString(root.resolve("outside.txt"), "secret\n");
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve(".git").resolve("config"), "token\n");
        Files.createDirectories(workspace.resolve("plaindir"));

        assertThatThrownBy(() -> service.resolve("@/etc/passwd"))
                .isInstanceOf(FileMentionException.class);
        assertThatThrownBy(() -> service.resolve("@../outside.txt"))
                .isInstanceOf(FileMentionException.class);
        assertThatThrownBy(() -> service.resolve("@missing.txt"))
                .isInstanceOf(FileMentionException.class);
        assertThatThrownBy(() -> service.resolve("@.git/config"))
                .isInstanceOf(FileMentionException.class);
        assertThatThrownBy(() -> service.resolve("@plaindir"))
                .isInstanceOf(FileMentionException.class);
    }

    @Test
    void rejectsBinaryAndInvalidUtf8Content() throws IOException {
        Files.write(workspace.resolve("bin.dat"), new byte[] {'a', 0, 'b'});
        Files.write(workspace.resolve("bad.txt"), new byte[] {(byte) 0xC3, (byte) 0x28});

        assertThatThrownBy(() -> service.resolve("@bin.dat"))
                .isInstanceOf(FileMentionException.class);
        assertThatThrownBy(() -> service.resolve("@bad.txt"))
                .isInstanceOf(FileMentionException.class);
    }

    @Test
    void rejectsSourceLargerThanBoundedReadBudget() throws IOException {
        Files.writeString(workspace.resolve("huge.txt"),
                "x".repeat(FileMentionService.MAX_SOURCE_FILE_BYTES + 1));

        assertThatThrownBy(() -> service.resolve("@huge.txt"))
                .isInstanceOf(FileMentionException.class);
    }

    @Test
    void rejectsMalformedSelectorsAndUnterminatedQuote() throws IOException {
        write("a.txt", "l1\nl2\n");

        assertThatThrownBy(() -> service.resolve("@a.txt#L"))
                .isInstanceOf(FileMentionException.class);
        assertThatThrownBy(() -> service.resolve("@a.txt#L0"))
                .isInstanceOf(FileMentionException.class);
        assertThatThrownBy(() -> service.resolve("@a.txt#L3-1"))
                .isInstanceOf(FileMentionException.class);
        assertThatThrownBy(() -> service.resolve("@a.txt#L1-"))
                .isInstanceOf(FileMentionException.class);
        assertThatThrownBy(() -> service.resolve("@\"unterminated.txt"))
                .isInstanceOf(FileMentionException.class);
        assertThatThrownBy(() -> service.resolve("@\"a.txt\"junk"))
                .isInstanceOf(FileMentionException.class);
        assertThatThrownBy(() -> service.resolve("@a.txt#L9"))
                .isInstanceOf(FileMentionException.class);
    }

    @Test
    void failureCodeIsFixedAndExposesNoPathOrContent() throws IOException {
        Files.writeString(root.resolve("outside.txt"), "SECRET_CONTENT\n");

        FileMentionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                FileMentionException.class, () -> service.resolve("@../outside.txt"));

        assertThat(failure.code()).isEqualTo(FileMentionException.CODE);
        assertThat(failure.getMessage()).isEqualTo(FileMentionException.CODE);
        assertThat(failure.toString())
                .doesNotContain("SECRET_CONTENT")
                .doesNotContain("outside.txt")
                .doesNotContain(workspace.toString())
                .doesNotContain(root.toString());
    }

    @Test
    void emptyFileIsRejectedRatherThanProducingEmptySnapshot() throws IOException {
        write("empty.txt", "");

        assertThatThrownBy(() -> service.resolve("@empty.txt"))
                .isInstanceOf(FileMentionException.class);
    }

    @Test
    void textWithoutMentionsProducesPlainMessage() {
        UserMessage message = service.resolve("no mentions here");

        assertThat(message.content()).isEqualTo("no mentions here");
        assertThat(message.attachments()).isEmpty();
    }
}
