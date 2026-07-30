package io.github.liumaishenjian.ccjava.tools.local.workspace;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 为全部本地文件 Tool 强制执行 Workspace 真实路径边界。
 *
 * <p>Guard 先拒绝绝对、UNC、drive-relative 和 lexical traversal，再对存在目标执行
 * {@link Path#toRealPath(LinkOption...)}，最后确认真实目标仍被启动时固定的真实 Workspace
 * 包含。Symlink 与 Windows Junction 不靠名称区分，统一以真实目标 containment 判定。</p>
 *
 * <p>该边界不是 OS Sandbox；它只约束使用本 Guard 的本地 Tool。</p>
 *
 * @since 0.3.0
 */
public final class WorkspaceGuard {

    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");

    private final Path workspace;
    private final SensitivePathPolicy sensitivePaths;

    /**
     * 固定真实 Workspace。
     *
     * @param workspace 已存在的 Workspace 目录
     * @throws IOException Workspace 无法解析真实路径时
     * @throws IllegalArgumentException Workspace 不是普通目录时
     */
    public WorkspaceGuard(Path workspace) throws IOException {
        this(workspace, new SensitivePathPolicy());
    }

    /**
     * 为测试或后续配置注入敏感策略。
     *
     * @param workspace 已存在的 Workspace 目录
     * @param sensitivePaths 固定敏感路径策略
     * @throws IOException Workspace 无法解析真实路径时
     * @throws IllegalArgumentException Workspace 不是目录时
     */
    public WorkspaceGuard(Path workspace, SensitivePathPolicy sensitivePaths) throws IOException {
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空").toRealPath();
        this.sensitivePaths = Objects.requireNonNull(sensitivePaths, "sensitivePaths 不能为空");
        if (!Files.isDirectory(this.workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Workspace 必须是目录");
        }
    }

    /**
     * 校验一个必须存在的路径。
     *
     * @param input 模型提供的 Workspace-relative 路径
     * @return 已验证路径
     * @throws WorkspaceAccessException 路径非法、越界、不存在、链接逃逸或敏感时
     */
    public ValidatedWorkspacePath requireExisting(String input) throws WorkspaceAccessException {
        Path logicalRelative = parseRelative(input);
        rejectSensitive(logicalRelative);
        Path logicalTarget = workspace.resolve(logicalRelative).normalize();
        if (!logicalTarget.startsWith(workspace)) {
            throw error(ToolErrorCode.WORKSPACE_BOUNDARY_VIOLATION, "路径不能越过 Workspace");
        }
        if (!Files.exists(logicalTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw error(ToolErrorCode.PATH_NOT_FOUND, "目标路径不存在");
        }

        Path realTarget;
        try {
            realTarget = logicalTarget.toRealPath();
        } catch (IOException exception) {
            throw error(ToolErrorCode.PATH_NOT_FOUND, "目标路径无法解析");
        }
        if (!realTarget.startsWith(workspace)) {
            throw error(ToolErrorCode.LINK_ESCAPE, "链接目标位于 Workspace 外");
        }
        Path realRelative = workspace.relativize(realTarget);
        rejectSensitive(realRelative);
        return new ValidatedWorkspacePath(realTarget, protocol(logicalRelative));
    }

    /**
     * 校验 Git pathspec 使用的逻辑路径；目标可以因删除而不存在。
     *
     * <p>存在目标仍执行完整 realpath containment；不存在目标只允许作为固定只读 Git
     * pathspec，不代表文件 Tool 可以绕过真实路径校验。</p>
     *
     * @param input Git 返回或模型提供的 Workspace-relative 路径
     * @return 稳定协议路径
     * @throws WorkspaceAccessException 路径越界、链接逃逸或敏感时
     */
    public String requireSafeGitPath(String input) throws WorkspaceAccessException {
        Path logicalRelative = parseRelative(input);
        rejectSensitive(logicalRelative);
        Path target = workspace.resolve(logicalRelative).normalize();
        if (!target.startsWith(workspace)) {
            throw error(ToolErrorCode.WORKSPACE_BOUNDARY_VIOLATION, "路径不能越过 Workspace");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Path realTarget;
            try {
                realTarget = target.toRealPath();
            } catch (IOException exception) {
                throw error(ToolErrorCode.PATH_NOT_FOUND, "目标路径无法解析");
            }
            if (!realTarget.startsWith(workspace)) {
                throw error(ToolErrorCode.LINK_ESCAPE, "链接目标位于 Workspace 外");
            }
            rejectSensitive(workspace.relativize(realTarget));
        }
        return protocol(logicalRelative);
    }

    /**
     * 要求目标是普通文件。
     *
     * @param input 模型路径
     * @return 已验证普通文件
     * @throws WorkspaceAccessException 不满足安全或类型约束时
     */
    public ValidatedWorkspacePath requireRegularFile(String input)
            throws WorkspaceAccessException {
        ValidatedWorkspacePath validated = requireExisting(input);
        if (!Files.isRegularFile(validated.realPath())) {
            throw error(ToolErrorCode.PATH_TYPE_MISMATCH, "目标不是普通文件");
        }
        return validated;
    }

    /**
     * 校验一个尚不存在、且直接父目录已经安全存在的新文件目标。
     *
     * <p>该方法不创建目录或文件。目标的逻辑路径、敏感策略与直接父目录 realpath
     * 都在返回前固定；调用方在真正落盘前仍必须再次调用本方法，以防审批期间
     * Workspace 状态发生变化。</p>
     *
     * @param input 模型提供的 Workspace-relative 新文件路径
     * @return 由真实父目录解析出的安全目标
     * @throws WorkspaceAccessException 路径非法、目标已存在、父目录不存在或链接逃逸时
     */
    public ValidatedWorkspacePath requireNewFile(String input)
            throws WorkspaceAccessException {
        Path logicalRelative = parseRelative(input);
        if (logicalRelative.toString().isEmpty()
                || ".".equals(logicalRelative.toString())
                || logicalRelative.getFileName() == null) {
            throw error(ToolErrorCode.INVALID_PATH, "新文件路径必须包含文件名");
        }
        rejectSensitive(logicalRelative);
        Path logicalTarget = workspace.resolve(logicalRelative).normalize();
        if (!logicalTarget.startsWith(workspace)) {
            throw error(ToolErrorCode.WORKSPACE_BOUNDARY_VIOLATION, "路径不能越过 Workspace");
        }
        if (Files.exists(logicalTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw error(ToolErrorCode.FILE_CONFLICT, "新文件目标已经存在");
        }

        Path logicalParent = logicalTarget.getParent();
        if (logicalParent == null
                || !Files.exists(logicalParent, LinkOption.NOFOLLOW_LINKS)) {
            throw error(ToolErrorCode.PATH_NOT_FOUND, "新文件的直接父目录不存在");
        }
        Path realParent;
        try {
            realParent = logicalParent.toRealPath();
        } catch (IOException exception) {
            throw error(ToolErrorCode.PATH_NOT_FOUND, "新文件的直接父目录无法解析");
        }
        if (!realParent.startsWith(workspace)) {
            throw error(ToolErrorCode.LINK_ESCAPE, "新文件父目录链接到 Workspace 外");
        }
        if (!Files.isDirectory(realParent, LinkOption.NOFOLLOW_LINKS)) {
            throw error(ToolErrorCode.PATH_TYPE_MISMATCH, "新文件父路径不是目录");
        }
        rejectSensitive(workspace.relativize(realParent));
        return new ValidatedWorkspacePath(
                realParent.resolve(logicalTarget.getFileName()),
                protocol(logicalRelative));
    }

    /**
     * 要求目标是目录。
     *
     * @param input 模型路径
     * @return 已验证目录
     * @throws WorkspaceAccessException 不满足安全或类型约束时
     */
    public ValidatedWorkspacePath requireDirectory(String input)
            throws WorkspaceAccessException {
        ValidatedWorkspacePath validated = requireExisting(input);
        if (!Files.isDirectory(validated.realPath())) {
            throw error(ToolErrorCode.PATH_TYPE_MISMATCH, "目标不是目录");
        }
        return validated;
    }

    /**
     * 返回真实 Workspace，仅供同模块 Adapter 固定工作目录。
     *
     * @return 启动时固定的真实目录
     */
    public Path workspace() {
        return workspace;
    }

    private Path parseRelative(String input) throws WorkspaceAccessException {
        if (input == null || input.isBlank()) {
            throw error(ToolErrorCode.INVALID_PATH, "路径不能为空");
        }
        String value = input.trim();
        if (value.startsWith("/")
                || value.startsWith("\\")
                || value.startsWith("//")
                || value.startsWith("\\\\")
                || WINDOWS_DRIVE.matcher(value).matches()) {
            throw error(ToolErrorCode.INVALID_PATH, "路径必须相对 Workspace");
        }
        String platformValue = value.replace('\\', java.io.File.separatorChar)
                .replace('/', java.io.File.separatorChar);
        Path parsed;
        try {
            parsed = Path.of(platformValue);
        } catch (InvalidPathException exception) {
            throw error(ToolErrorCode.INVALID_PATH, "路径格式无效");
        }
        if (parsed.isAbsolute()) {
            throw error(ToolErrorCode.INVALID_PATH, "路径必须相对 Workspace");
        }
        Path normalized = parsed.normalize();
        if (normalized.startsWith("..") || hasTraversal(parsed)) {
            throw error(ToolErrorCode.WORKSPACE_BOUNDARY_VIOLATION, "路径不能包含越界 traversal");
        }
        return normalized;
    }

    private static boolean hasTraversal(Path parsed) {
        for (Path segment : parsed) {
            if (segment.toString().equals("..")) {
                return true;
            }
        }
        return false;
    }

    private void rejectSensitive(Path relative) throws WorkspaceAccessException {
        if (sensitivePaths.isSensitive(relative)) {
            throw new WorkspaceAccessException(new ToolError(
                    ToolErrorCode.SENSITIVE_PATH,
                    "安全策略拒绝访问敏感路径",
                    new JsonObject(Map.of("path", protocol(relative)))));
        }
    }

    private static String protocol(Path relative) {
        String value = SensitivePathPolicy.protocolPath(relative);
        return value.isEmpty() ? "." : value;
    }

    private static WorkspaceAccessException error(ToolErrorCode code, String message) {
        return new WorkspaceAccessException(ToolError.of(code, message));
    }
}
