package io.github.liumaishenjian.ccjava.tools.local.text;

/**
 * 一个已读取文本文件的换行外观分类。
 *
 * <p>该分类是本项目自有契约，只描述“写回时应当使用什么分隔符”，不描述平台、
 * 不描述 Git 配置，也不参与路径或权限判断。模型永远只看到规范化后的 {@code \n}；
 * 该枚举保证写回时能把规范化文本恢复成原文件的外观。</p>
 *
 * <p>取值语义：</p>
 * <ul>
 *   <li>{@link #LF}：全部行分隔符都是 {@code \n}；</li>
 *   <li>{@link #CRLF}：全部行分隔符都是 {@code \r\n}；</li>
 *   <li>{@link #ABSENT}：文件不含任何行分隔符，写回风格由调用方按 {@code \n} 决定；</li>
 *   <li>{@link #MIXED}：同时存在多种分隔符或存在裸 {@code \r}，无法在不改写无关行的
 *       前提下推断唯一写回风格。</li>
 * </ul>
 *
 * @since 0.8.0
 */
public enum LineSeparatorStyle {

    /** 全部使用 {@code \n}。 */
    LF("\n"),

    /** 全部使用 {@code \r\n}。 */
    CRLF("\r\n"),

    /** 文件内没有任何行分隔符。 */
    ABSENT("\n"),

    /** 分隔符不一致或包含裸 {@code \r}，不存在安全的唯一写回风格。 */
    MIXED("\n");

    private final String separator;

    LineSeparatorStyle(String separator) {
        this.separator = separator;
    }

    /**
     * 返回该风格写回时使用的分隔符。
     *
     * <p>{@link #MIXED} 没有可信写回风格，其返回值只用于避免空值，调用方必须先通过
     * {@link #writable()} 判断是否允许合成新的分隔符。</p>
     *
     * @return 行分隔符字面量
     */
    public String separator() {
        return separator;
    }

    /**
     * 指示该风格是否允许在写回时合成新的行分隔符。
     *
     * @return 可安全合成时为 {@code true}；{@link #MIXED} 为 {@code false}
     */
    public boolean writable() {
        return this != MIXED;
    }
}
