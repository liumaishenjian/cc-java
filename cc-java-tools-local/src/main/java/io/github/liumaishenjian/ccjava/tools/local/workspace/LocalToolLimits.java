package io.github.liumaishenjian.ccjava.tools.local.workspace;

/**
 * S03-S04 本地文件工具的固定资源边界。
 *
 * <p>这些值在 S08 Settings 进入前不可由模型、仓库指令或 Tool 参数放宽。调用参数可以选择
 * 更小的预算；Pipeline 仍会执行独立的最终字符上限。</p>
 *
 * @since 0.3.0
 */
public final class LocalToolLimits {

    /** 普通文本文件最大字节数。 */
    public static final long MAX_TEXT_FILE_BYTES = 2L * 1024 * 1024;

    /** 根 AGENTS.md 最大字节数。 */
    public static final long MAX_INSTRUCTION_BYTES = 64L * 1024;

    /** 单次 read_file 最大行数。 */
    public static final int MAX_READ_LINES = 500;

    /** list_files 最大深度。 */
    public static final int MAX_LIST_DEPTH = 20;

    /** list_files 最大条目数。 */
    public static final int MAX_LIST_RESULTS = 1_000;

    /** search_text 最大访问文件数。 */
    public static final int MAX_SEARCH_FILES = 5_000;

    /** search_text 最大累计扫描字节。 */
    public static final long MAX_SEARCH_BYTES = 32L * 1024 * 1024;

    /** search_text 最大匹配数。 */
    public static final int MAX_SEARCH_RESULTS = 500;

    /** 本地只读 Tool 声明的最大模型可见字符数。 */
    public static final int MAX_TOOL_OUTPUT_CHARACTERS = 64_000;

    /** 单个精确替换片段的最大字符数。 */
    public static final int MAX_PATCH_FRAGMENT_CHARACTERS = 512 * 1024;

    /** 单次 Patch 结果中返回给模型的变更预览字符数。 */
    public static final int MAX_PATCH_PREVIEW_CHARACTERS = 16 * 1024;

    /** 单条 Shell 命令的最大字符数。 */
    public static final int MAX_COMMAND_CHARACTERS = 8 * 1024;

    /** 命令 stdout/stderr 合计保留给模型和 TUI 的最大字符数。 */
    public static final int MAX_COMMAND_OUTPUT_CHARACTERS = 48 * 1024;

    /** 命令默认超时秒数。 */
    public static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 30;

    /** 模型可请求的命令最大超时秒数。 */
    public static final int MAX_COMMAND_TIMEOUT_SECONDS = 120;

    private LocalToolLimits() {
    }
}
