package io.github.liumaishenjian.ccjava.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Objects;

/**
 * M1 中一个完整且不携带文件系统路径的不可变 Memory topic。
 *
 * <p>{@code contentDigest} 为空表示尚未持久化的创建候选；从 Repository 读取或成功写入的 topic
 * 必须携带 64 位小写 SHA-256。更新候选继续携带读取时摘要，Core Port 会把它作为乐观并发前提，
 * Adapter 不得仅凭调用者文本覆盖磁盘内容。</p>
 *
 * @param name 受限 kebab-case topic 名称
 * @param kind 记忆语义分类
 * @param description M2 使用的一行有界 hook
 * @param body 不包含 frontmatter 的 Markdown 正文
 * @param contentDigest 空字符串表示新建候选，否则为读取时的文件摘要
 * @param updatedAt 内容最近确认日期
 * @since 0.7.0
 */
public record MemoryTopic(
        String name,
        MemoryKind kind,
        String description,
        String body,
        String contentDigest,
        LocalDate updatedAt) {

    /** 单个 topic 的独立 UTF-8 字节上限；Adapter 还会校验序列化后的完整文件。 */
    public static final int MAX_UTF8_BYTES = 64 * 1024;

    /** 单个 topic 的独立行数上限；Adapter 还会计入 frontmatter。 */
    public static final int MAX_LINES = 2_000;

    /** 校验不依赖文件系统的字段与保守内容上限。 */
    public MemoryTopic {
        name = requireText(name, "name");
        strictUtf8Length(name, "name");
        if (name.codePointCount(0, name.length()) > 64
                || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("name 必须是不超过 64 字符的受限 kebab-case slug");
        }
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        description = requireText(description, "description");
        strictUtf8Length(description, "description");
        if (description.codePointCount(0, description.length()) > 512
                || description.chars().anyMatch(character -> character == '\r'
                        || character == '\n'
                        || Character.isISOControl(character))) {
            throw new IllegalArgumentException("description 必须是不超过 512 字符的单行文本");
        }
        body = Objects.requireNonNull(body, "body 不能为空");
        if (body.split("\\R", -1).length > MAX_LINES) {
            throw new IllegalArgumentException("body 超过行数上限");
        }
        if (strictUtf8Length(body, "body") > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("body 超过 UTF-8 字节上限");
        }
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest 不能为空");
        if (!contentDigest.isEmpty() && !contentDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentDigest 必须为空或 SHA-256 十六进制摘要");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
    }

    /** 创建尚未持久化、没有读取摘要的新建候选。 */
    public static MemoryTopic candidate(
            String name,
            MemoryKind kind,
            String description,
            String body,
            LocalDate updatedAt) {
        return new MemoryTopic(name, kind, description, body, "", updatedAt);
    }

    /**
     * 用新内容派生更新候选，并保留当前读取摘要作为 expected digest。
     *
     * @param kind 新分类
     * @param description 新 hook
     * @param body 新正文
     * @param updatedAt 新确认日期
     * @return 携带当前摘要的更新候选
     * @throws IllegalStateException 当前对象没有持久化摘要时
     */
    public MemoryTopic updated(
            MemoryKind kind,
            String description,
            String body,
            LocalDate updatedAt) {
        if (contentDigest.isEmpty()) {
            throw new IllegalStateException("尚未持久化的 topic 不能派生更新候选");
        }
        return new MemoryTopic(name, kind, description, body, contentDigest, updatedAt);
    }

    /** 返回不含正文的 Catalog header。 */
    public MemoryTopicHeader header() {
        if (contentDigest.isEmpty()) {
            throw new IllegalStateException("尚未持久化的 topic 没有 Catalog header");
        }
        return new MemoryTopicHeader(name, kind, description, updatedAt, contentDigest);
    }

    private static int strictUtf8Length(String value, String field) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            return encoded.remaining();
        } catch (CharacterCodingException invalidUnicode) {
            throw new IllegalArgumentException(field + " 必须能严格编码为 UTF-8", invalidUnicode);
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
