package io.github.liumaishenjian.ccjava.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 经过 M5 校验、可作为短生命周期 Context 输入的一条记忆正文。
 *
 * @param name topic slug
 * @param kind 记忆分类
 * @param description 来源 hook
 * @param body 已验证正文
 * @param contentDigest 与 Catalog header 匹配的文件摘要
 * @param utf8Bytes 正文 UTF-8 字节数
 * @since 0.7.0
 */
public record MemoryProjectionItem(
        String name,
        MemoryKind kind,
        String description,
        String body,
        String contentDigest,
        int utf8Bytes) {

    /** 校验来源、摘要与字节计数。 */
    public MemoryProjectionItem {
        name = Objects.requireNonNull(name, "name 不能为空");
        if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("name 必须是受限 slug");
        }
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        description = requireText(description, "description");
        body = requireText(body, "body");
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest 不能为空");
        if (!contentDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentDigest 必须是 SHA-256");
        }
        int actual = body.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes <= 0 || utf8Bytes != actual) {
            throw new IllegalArgumentException("utf8Bytes 必须准确表示正文");
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
