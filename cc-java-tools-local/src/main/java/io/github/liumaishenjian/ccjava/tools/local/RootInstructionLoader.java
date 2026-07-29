package io.github.liumaishenjian.ccjava.tools.local;

import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Optional;

/**
 * 在 Session 启动时最多加载一次 Workspace 根 {@code AGENTS.md}。
 *
 * <p>不存在时返回空；存在时必须通过同一个 WorkspaceGuard、大小和严格 UTF-8 检查。内容只
 * 作为 Project Instructions，不是权限或安全策略，也不会递归解析 import。</p>
 *
 * @since 0.3.0
 */
public final class RootInstructionLoader {

    private final WorkspaceGuard guard;

    /**
     * 创建绑定真实 Workspace 的根指令加载器。
     *
     * @param guard 共享路径安全边界
     */
    public RootInstructionLoader(WorkspaceGuard guard) {
        this.guard = java.util.Objects.requireNonNull(guard, "guard 不能为空");
    }

    /**
     * 加载根 AGENTS.md。
     *
     * @return 文件不存在时为空
     * @throws WorkspaceAccessException 链接逃逸、大小、编码或读取失败时
     */
    public Optional<String> load() throws WorkspaceAccessException {
        if (!Files.exists(guard.workspace().resolve("AGENTS.md"), LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        ValidatedWorkspacePath validated = guard.requireRegularFile("AGENTS.md");
        long size;
        try {
            size = Files.size(validated.realPath());
        } catch (IOException exception) {
            throw error(ToolErrorCode.EXECUTION_FAILED, "无法读取根 AGENTS.md");
        }
        if (size > LocalToolLimits.MAX_INSTRUCTION_BYTES) {
            throw error(ToolErrorCode.FILE_TOO_LARGE, "根 AGENTS.md 超过大小上限");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(validated.realPath());
        } catch (IOException exception) {
            throw error(ToolErrorCode.EXECUTION_FAILED, "无法读取根 AGENTS.md");
        }
        int offset = bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF ? 3 : 0;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return Optional.of(decoded.toString());
        } catch (CharacterCodingException exception) {
            throw error(ToolErrorCode.UNSUPPORTED_ENCODING, "根 AGENTS.md 不是有效 UTF-8");
        }
    }

    private static WorkspaceAccessException error(ToolErrorCode code, String message) {
        return new WorkspaceAccessException(ToolError.of(code, message));
    }
}
