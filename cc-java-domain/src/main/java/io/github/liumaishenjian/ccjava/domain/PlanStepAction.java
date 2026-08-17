package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Set;

/** Plan 步骤的受限结构化 Tool 意图；不允许从 detail 文本推断命令。 */
public record PlanStepAction(String toolName, JsonObject arguments, String safePreview) {
    private static final Set<String> ALLOWED = Set.of(
            "list_files", "read_file", "search_text", "git_status", "git_diff",
            "apply_patch", "write_file", "run_command");

    public PlanStepAction {
        toolName = requireText(toolName, "toolName", 64);
        if (!ALLOWED.contains(toolName)) throw new IllegalArgumentException("Plan Tool 不允许");
        arguments = Objects.requireNonNull(arguments, "arguments 不能为空");
        safePreview = requireText(safePreview, "safePreview", 1_000);
    }

    public boolean readOnly() {
        return toolName.equals("list_files") || toolName.equals("read_file")
                || toolName.equals("search_text") || toolName.equals("git_status") || toolName.equals("git_diff");
    }

    public static Set<String> allowedToolNames() { return ALLOWED; }

    private static String requireText(String value, String name, int max) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " 无效");
        }
        return value;
    }
}
