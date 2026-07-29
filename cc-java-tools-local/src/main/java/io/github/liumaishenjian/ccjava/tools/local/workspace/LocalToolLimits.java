package io.github.liumaishenjian.ccjava.tools.local.workspace;

/**
 * S03 本地只读工具的固定资源边界。
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

    private LocalToolLimits() {
    }
}
