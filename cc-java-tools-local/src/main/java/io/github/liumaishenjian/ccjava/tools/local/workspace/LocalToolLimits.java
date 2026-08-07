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

    /**
     * 单次有界行范围读取允许扫描的最大字节数。
     *
     * <p>该 ceiling 独立于整文件读取的 {@link #MAX_TEXT_FILE_BYTES}：范围读取以固定字节
     * 窗口流式解码，峰值内存由页行数与单行字符预算决定，与文件大小无关，因此可以在明确
     * 请求有界范围时安全越过整文件 ceiling。取值按“固定 5 秒 Tool 期限内可完成的顺序扫描
     * 量”选取，与 {@link #MAX_SEARCH_BYTES} 同量级，仍受取消信号和 Tool 超时约束。</p>
     */
    public static final long MAX_RANGE_SCAN_BYTES = 64L * 1024 * 1024;

    /** 单行允许返回给模型的最大字符数，防止超长行无界累积。 */
    public static final int MAX_READ_LINE_CHARACTERS = 4_000;

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
