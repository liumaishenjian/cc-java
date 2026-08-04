package io.github.liumaishenjian.ccjava.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * M4 相关记忆选择使用的有界、无路径查询。
 *
 * @param userText 当前任务的非空文本
 * @param keywords 调用者提取的有序关键词，最多 32 个且不得重复
 * @param maxTopics 最多选择的 topic 数，范围 1..20
 * @param byteBudget M5 正文 UTF-8 总预算，范围 1..262144
 * @param catalogRevision 启动召回时观察到的 Catalog revision
 * @since 0.7.0
 */
public record RecallQuery(
        String userText,
        List<String> keywords,
        int maxTopics,
        int byteBudget,
        MemoryCatalogRevision catalogRevision) {

    /** 校验召回边界并防御性复制关键词。 */
    public RecallQuery {
        userText = requireText(userText, "userText");
        if (userText.length() > 16_384) {
            throw new IllegalArgumentException("userText 超过字符上限");
        }
        keywords = List.copyOf(Objects.requireNonNull(keywords, "keywords 不能为空"));
        if (keywords.size() > 32 || new LinkedHashSet<>(keywords).size() != keywords.size()) {
            throw new IllegalArgumentException("keywords 必须有序、唯一且不超过 32 个");
        }
        for (String keyword : keywords) {
            String checked = requireText(keyword, "keyword");
            if (!checked.equals(keyword) || keyword.codePointCount(0, keyword.length()) > 64) {
                throw new IllegalArgumentException("keyword 必须是最多 64 字符的原样非空文本");
            }
        }
        if (maxTopics < 1 || maxTopics > 20) {
            throw new IllegalArgumentException("maxTopics 必须在 1..20");
        }
        if (byteBudget < 1 || byteBudget > 256 * 1024) {
            throw new IllegalArgumentException("byteBudget 必须在 1..262144");
        }
        catalogRevision = Objects.requireNonNull(catalogRevision, "catalogRevision 不能为空");
    }

    private static String requireText(String value, String field) {
        String checked = Objects.requireNonNull(value, field + " 不能为空");
        if (checked.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
        return checked;
    }
}
