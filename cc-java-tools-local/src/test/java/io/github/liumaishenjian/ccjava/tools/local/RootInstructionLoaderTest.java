package io.github.liumaishenjian.ccjava.tools.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RootInstructionLoaderTest {

    @TempDir
    Path workspace;

    @Test
    void missingFileIsNormalAndExistingUtf8LoadsOncePerCall() throws Exception {
        RootInstructionLoader loader = new RootInstructionLoader(new WorkspaceGuard(workspace));
        assertThat(loader.load()).isEmpty();

        Files.writeString(workspace.resolve("AGENTS.md"), "# rules\n只读");
        assertThat(loader.load()).contains("# rules\n只读");
    }

    @Test
    void rejectsOversizedAndInvalidUtf8Instructions() throws Exception {
        Files.write(workspace.resolve("AGENTS.md"),
                new byte[(int) LocalToolLimits.MAX_INSTRUCTION_BYTES + 1]);
        RootInstructionLoader oversized = new RootInstructionLoader(new WorkspaceGuard(workspace));
        assertCode(oversized, ToolErrorCode.FILE_TOO_LARGE);

        Files.write(workspace.resolve("AGENTS.md"), new byte[] {(byte) 0xC3, 0x28});
        RootInstructionLoader invalid = new RootInstructionLoader(new WorkspaceGuard(workspace));
        assertCode(invalid, ToolErrorCode.UNSUPPORTED_ENCODING);
    }

    private static void assertCode(RootInstructionLoader loader, ToolErrorCode code) {
        assertThatThrownBy(loader::load)
                .isInstanceOf(WorkspaceAccessException.class)
                .satisfies(exception -> assertThat(((WorkspaceAccessException) exception)
                        .error().code()).isEqualTo(code));
    }
}
