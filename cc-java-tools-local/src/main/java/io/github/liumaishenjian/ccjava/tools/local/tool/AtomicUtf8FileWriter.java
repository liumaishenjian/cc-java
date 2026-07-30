package io.github.liumaishenjian.ccjava.tools.local.tool;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

/**
 * 在目标真实父目录中暂存字节，并以单次 Move 完成 UTF-8 文件落盘。
 *
 * <p>该类型不解析模型路径，也不决定权限。调用方必须先经过 WorkspaceGuard，并在
 * {@code beforeCommit} 中重新验证目标路径；Writer 只负责临时文件生命周期、内容冲突
 * 检测和提交。替换已有文件时优先使用 {@code ATOMIC_MOVE}，平台不支持时退化为同目录
 * Move；创建文件始终使用不带 {@code REPLACE_EXISTING} 的同目录 Move，避免竞态文件被覆盖。</p>
 *
 * @since 0.4.0
 */
final class AtomicUtf8FileWriter {

    private AtomicUtf8FileWriter() {
    }

    /**
     * 替换仍与读取快照一致的已有文件。
     *
     * @param target 已验证的真实目标
     * @param expectedBytes 审批后读取的原始字节
     * @param updatedBytes 新字节
     * @param cancellation 当前 Run 取消信号
     * @param beforeCommit 移动前重新验证路径的无副作用动作
     * @throws WorkspaceAccessException 冲突、取消或 I/O 失败时
     */
    static void replace(
            Path target,
            byte[] expectedBytes,
            byte[] updatedBytes,
            CancellationToken cancellation,
            CheckedAction beforeCommit) throws WorkspaceAccessException {
        Objects.requireNonNull(target, "target 不能为空");
        byte[] expected = Objects.requireNonNull(expectedBytes, "expectedBytes 不能为空").clone();
        byte[] updated = Objects.requireNonNull(updatedBytes, "updatedBytes 不能为空").clone();
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        Objects.requireNonNull(beforeCommit, "beforeCommit 不能为空");
        ensureNotCancelled(cancellation);
        Path staged = stage(target.getParent(), updated);
        try {
            ensureNotCancelled(cancellation);
            beforeCommit.run();
            byte[] current;
            try {
                current = Files.readAllBytes(target);
            } catch (IOException exception) {
                throw failure(ToolErrorCode.FILE_CONFLICT, "文件在写入前已改变");
            }
            if (!Arrays.equals(expected, current)) {
                throw failure(ToolErrorCode.FILE_CONFLICT, "文件在写入前已改变");
            }
            ensureNotCancelled(cancellation);
            moveReplacing(staged, target);
            staged = null;
        } finally {
            deleteQuietly(staged);
        }
    }

    /**
     * 创建一个在移动前仍不存在的新文件。
     *
     * @param target 已由真实父目录解析的目标
     * @param content 新文件字节
     * @param cancellation 当前 Run 取消信号
     * @param beforeCommit 移动前重新验证新文件路径的动作
     * @throws WorkspaceAccessException 冲突、取消或 I/O 失败时
     */
    static void create(
            Path target,
            byte[] content,
            CancellationToken cancellation,
            CheckedAction beforeCommit) throws WorkspaceAccessException {
        Objects.requireNonNull(target, "target 不能为空");
        byte[] bytes = Objects.requireNonNull(content, "content 不能为空").clone();
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        Objects.requireNonNull(beforeCommit, "beforeCommit 不能为空");
        ensureNotCancelled(cancellation);
        Path staged = stage(target.getParent(), bytes);
        try {
            ensureNotCancelled(cancellation);
            beforeCommit.run();
            ensureNotCancelled(cancellation);
            moveCreating(staged, target);
            staged = null;
        } finally {
            deleteQuietly(staged);
        }
    }

    private static Path stage(Path parent, byte[] bytes) throws WorkspaceAccessException {
        Path staged = null;
        try {
            staged = Files.createTempFile(parent, ".cc-java-write-", ".tmp");
            Files.write(
                    staged,
                    bytes,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return staged;
        } catch (IOException exception) {
            deleteQuietly(staged);
            throw failure(ToolErrorCode.EXECUTION_FAILED, "无法暂存文件修改");
        }
    }

    private static void moveReplacing(Path source, Path target)
            throws WorkspaceAccessException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                throw failure(ToolErrorCode.EXECUTION_FAILED, "无法提交文件修改");
            }
        } catch (IOException exception) {
            throw failure(ToolErrorCode.EXECUTION_FAILED, "无法提交文件修改");
        }
    }

    private static void moveCreating(Path source, Path target)
            throws WorkspaceAccessException {
        try {
            // 不使用 ATOMIC_MOVE：其目标已存在语义由平台决定，可能意外覆盖竞态文件。
            // 同目录无 REPLACE 的单次 Move 保持“目标已存在即失败”的首要安全契约。
            Files.move(source, target);
        } catch (FileAlreadyExistsException conflict) {
            throw failure(ToolErrorCode.FILE_CONFLICT, "新文件目标已经存在");
        } catch (IOException exception) {
            throw failure(ToolErrorCode.EXECUTION_FAILED, "无法创建新文件");
        }
    }

    private static void ensureNotCancelled(CancellationToken cancellation)
            throws WorkspaceAccessException {
        if (cancellation.isCancellationRequested()) {
            throw failure(ToolErrorCode.OPERATION_CANCELLED, "文件操作已取消");
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不能覆盖原始结构化错误。
        }
    }

    private static WorkspaceAccessException failure(ToolErrorCode code, String message) {
        return new WorkspaceAccessException(ToolError.of(code, message));
    }

    /**
     * 移动前执行的路径与状态重检。
     */
    @FunctionalInterface
    interface CheckedAction {
        /** 执行重检。 */
        void run() throws WorkspaceAccessException;
    }
}
