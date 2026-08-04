package io.github.liumaishenjian.ccjava.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 不携带文件系统路径或正文的 Memory topic 元数据。
 *
 * @param name 受限、稳定的 kebab-case topic 名称
 * @param kind 记忆语义分类
 * @param description 用于索引与召回的一行 hook
 * @param updatedAt 内容最近确认日期
 * @param contentDigest topic 文件内容摘要，不包含原始路径
 * @since 0.7.0
 */
public record MemoryTopicHeader(
        String name,
        MemoryKind kind,
        String description,
        LocalDate updatedAt,
        String contentDigest) {

    /** 校验公开 Catalog 元数据不为空。 */
    public MemoryTopicHeader {
        name = requireText(name, "name");
        if (name.codePointCount(0, name.length()) > 64
                || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("name 必须是不超过 64 字符的受限 kebab-case slug");
        }
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        description = requireText(description, "description");
        if (description.codePointCount(0, description.length()) > 512
                || description.chars().anyMatch(character -> character == '\r'
                        || character == '\n'
                        || Character.isISOControl(character))) {
            throw new IllegalArgumentException("description 必须是不超过 512 字符的单行文本");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        contentDigest = requireText(contentDigest, "contentDigest");
        if (!contentDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentDigest 必须是 SHA-256 十六进制摘要");
        }
    }

    private static String requireText(String value, String field) {
        String checked = Objects.requireNonNull(value, field + " 不能为空");
        if (checked.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
        return checked;
    }
}
