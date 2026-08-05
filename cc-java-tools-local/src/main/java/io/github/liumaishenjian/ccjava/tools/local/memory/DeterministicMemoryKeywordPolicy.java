package io.github.liumaishenjian.ccjava.tools.local.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 从当前用户消息提取有序、唯一且有界的 M4 关键词。
 *
 * <p>该项目自有策略只把 Unicode 字母或数字视为词项字符，逐 Unicode code point 使用 JDK
 * locale-independent 小写映射，并按首次出现顺序保留最多 32 项。每项最多 64 个 Unicode code point；
 * 超长词项保留前缀，标点、空白和其他字符只作为边界。本策略不读取 Transcript，也不声称复制自
 * 任何参考实现。</p>
 *
 * @since 0.7.0
 */
public final class DeterministicMemoryKeywordPolicy {

    /** 召回查询最多携带的词项数。 */
    public static final int MAX_KEYWORDS = 32;

    /** 单个词项最多携带的 Unicode code point 数。 */
    public static final int MAX_KEYWORD_CODE_POINTS = 64;

    /** 创建无状态的确定性策略。 */
    public DeterministicMemoryKeywordPolicy() {
    }

    /**
     * 从一条有界用户消息提取词项。
     *
     * @param userText 当前用户消息；允许空文本并返回空结果
     * @return 按首次出现顺序排列的小写唯一词项
     */
    public List<String> extract(String userText) {
        String input = Objects.requireNonNull(userText, "userText 不能为空");
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder token = new StringBuilder();
        int tokenCodePoints = 0;
        for (int offset = 0; offset < input.length();) {
            int codePoint = input.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                if (tokenCodePoints < MAX_KEYWORD_CODE_POINTS) {
                    token.appendCodePoint(codePoint);
                    tokenCodePoints++;
                }
            } else {
                add(token, seen, result);
                token.setLength(0);
                tokenCodePoints = 0;
                if (result.size() == MAX_KEYWORDS) {
                    return List.copyOf(result);
                }
            }
        }
        add(token, seen, result);
        return List.copyOf(result);
    }

    private void add(StringBuilder token, Set<String> seen, List<String> result) {
        if (token.isEmpty() || result.size() == MAX_KEYWORDS) {
            return;
        }
        StringBuilder lowered = new StringBuilder();
        token.codePoints().map(Character::toLowerCase).forEach(lowered::appendCodePoint);
        String normalized = truncate(lowered.toString(), MAX_KEYWORD_CODE_POINTS);
        if (!normalized.isEmpty() && seen.add(normalized)) {
            result.add(normalized);
        }
    }

    private String truncate(String value, int maximumCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maximumCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }
}
