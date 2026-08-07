package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 证伪附件契约接受绝对路径、traversal、坏 digest、非法行号或超限正文。 */
class UserFileAttachmentTest {

    private static final String DIGEST = "a".repeat(64);

    private static UserFileAttachment attachment(String path, String text) {
        return new UserFileAttachment(path, text, DIGEST, 1, 1, false);
    }

    @Test
    void acceptsWorkspaceRelativeSnapshot() {
        UserFileAttachment attachment = new UserFileAttachment(
                "src/main/java/App.java", "line", DIGEST, 3, 7, true);

        assertThat(attachment.protocolPath()).isEqualTo("src/main/java/App.java");
        assertThat(attachment.startLine()).isEqualTo(3);
        assertThat(attachment.endLine()).isEqualTo(7);
        assertThat(attachment.truncated()).isTrue();
        assertThat(attachment("notes..backup.md", "x").protocolPath())
                .isEqualTo("notes..backup.md");
    }

    @Test
    void rejectsUnsafePaths() {
        assertThatThrownBy(() -> attachment("/etc/passwd", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attachment("..\\outside.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attachment("a/../../b.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attachment("dir\\file.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attachment("C:/outside.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attachment("a/./b.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attachment("a//b.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attachment("  ", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonLowercaseSha256() {
        assertThatThrownBy(() -> new UserFileAttachment("a.txt", "x", "A".repeat(64), 1, 1, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserFileAttachment("a.txt", "x", "a".repeat(63), 1, 1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidLineRangeAndOversizedOrBinarySnapshot() {
        assertThatThrownBy(() -> new UserFileAttachment("a.txt", "x", DIGEST, 0, 1, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserFileAttachment("a.txt", "x", DIGEST, 5, 4, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attachment("a.txt", "a\0b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attachment("a.txt", "x".repeat(65_537)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactAttachmentByteLimitIsAccepted() {
        assertThat(attachment("a.txt", "x".repeat(65_536)).textSnapshot()).hasSize(65_536);
    }

    @Test
    void userMessageKeepsTextOnlyConstructorAndBoundsAttachments() {
        UserMessage textOnly = new UserMessage("hello");
        assertThat(textOnly.attachments()).isEmpty();
        assertThat(textOnly.content()).isEqualTo("hello");

        List<UserFileAttachment> nine = java.util.stream.IntStream.range(0, 9)
                .mapToObj(index -> attachment("f" + index + ".txt", "x"))
                .toList();
        assertThatThrownBy(() -> new UserMessage("hi", nine))
                .isInstanceOf(IllegalArgumentException.class);

        List<UserFileAttachment> oversizedTotal = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> attachment("f" + index + ".txt", "x".repeat(65_536)))
                .toList();
        assertThatThrownBy(() -> new UserMessage("hi", oversizedTotal))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userMessageAttachmentsAreImmutableCopies() {
        List<UserFileAttachment> mutable = new java.util.ArrayList<>();
        mutable.add(attachment("a.txt", "x"));
        UserMessage message = new UserMessage("hi", mutable);
        mutable.clear();

        assertThat(message.attachments()).hasSize(1);
        assertThatThrownBy(() -> message.attachments().add(attachment("b.txt", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
