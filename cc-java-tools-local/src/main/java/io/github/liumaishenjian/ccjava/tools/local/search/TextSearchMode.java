package io.github.liumaishenjian.ccjava.tools.local.search;

/**
 * 精确文本搜索对模型公开的结果视图。
 *
 * @since 0.3.1
 */
public enum TextSearchMode {
    /** 返回匹配内容和可选上下文。 */
    CONTENT,
    /** 只返回至少包含一个匹配的文件。 */
    FILES,
    /** 返回每个文件的匹配行数。 */
    COUNT
}
