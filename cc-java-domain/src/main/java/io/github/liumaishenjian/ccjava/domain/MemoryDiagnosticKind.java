package io.github.liumaishenjian.ccjava.domain;

/**
 * M2/M3 可观察但不回显不可信正文的结构化诊断分类。
 *
 * @since 0.7.0
 */
public enum MemoryDiagnosticKind {
    /** 注入的 Memory 根目录未通过存在性、目录类型或真实路径校验。 */
    ROOT_INVALID,

    /** 目录项不是在禁止跟随链接条件下验证过的普通文件。 */
    ENTRY_NOT_REGULAR_FILE,

    /** 目录项或根目录是 Symlink、Junction 或其他重解析点。 */
    LINK_NOT_ALLOWED,

    /** 目录项的真实路径不再是 Memory 根目录的直接子项。 */
    PATH_OUTSIDE_ROOT,

    /** 文件名不是受限 {@code <kebab-slug>.md}。 */
    INVALID_FILE_NAME,

    /** 文件超过独立字节上限。 */
    FILE_TOO_LARGE,

    /** 文件解码后的行数超过独立上限。 */
    TOO_MANY_LINES,

    /** 文件不是严格、可完整解码的 UTF-8。 */
    INVALID_UTF8,

    /** Frontmatter 缺失、未闭合、字段集合不合法或日期格式错误。 */
    INVALID_FRONTMATTER,

    /** {@code kind} 不属于四种受限 Memory 分类。 */
    UNKNOWN_KIND,

    /** Frontmatter 名称不是受限 slug，或与文件名不一致。 */
    INVALID_SLUG,

    /** Description 等受限字段超过长度或字符边界。 */
    FIELD_LIMIT_EXCEEDED,

    /** 同一次扫描中出现重复的已验证 topic 名称。 */
    DUPLICATE_TOPIC,

    /** 非索引目录项超过有界候选数量；不泄漏被舍弃项名称。 */
    TOPIC_LIMIT_REACHED,

    /** M2 Index 已达到最大行数。 */
    INDEX_LINE_LIMIT_REACHED,

    /** M2 Index 的下一行会超过 UTF-8 字节上限。 */
    INDEX_BYTE_LIMIT_REACHED,

    /** 读取前后文件身份、类型、大小或路径发生变化，文件未被提交。 */
    FILE_CHANGED_DURING_READ,

    /** 未能安全枚举或读取条目，且错误细节不适合进入 Catalog。 */
    IO_FAILURE
}
