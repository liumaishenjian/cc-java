package io.github.liumaishenjian.ccjava.domain.hook;

import java.util.Objects;
import java.util.Optional;

/**
 * 对事件种类和有界主体名执行确定性匹配的值对象。
 *
 * <p>首版只匹配事件和主体，不把文件系统路径或任意 Prompt 文本作为隐式范围。
 * 主体表达式是只支持 {@code *} 与 {@code ?} 的有界 glob，不接受任意正则；这样
 * 配置内容不能在进入 Hook 执行器前制造灾难性回溯并绕过墙钟超时。</p>
 *
 * @param event 目标事件
 * @param subjectGlob 可选的主体 glob；为空表示匹配该事件的所有主体
 * @since 0.1.0
 */
public record HookMatcher(HookEventKind event, Optional<String> subjectGlob) {

    /** Matcher glob 最大字符数。 */
    public static final int MAX_PATTERN_CHARACTERS = 256;

    /**
     * 校验并创建 Matcher。
     */
    public HookMatcher {
        event = Objects.requireNonNull(event, "event 不能为空");
        subjectGlob = Objects.requireNonNull(subjectGlob, "subjectGlob 不能为空")
                .map(HookMatcher::validateGlob);
    }

    /**
     * 创建只按事件匹配的 Matcher。
     *
     * @param event 目标生命周期事件
     * @return 匹配该事件全部主体的 Matcher
     */
    public static HookMatcher event(HookEventKind event) {
        return new HookMatcher(event, Optional.empty());
    }

    /**
     * 创建按事件和主体 glob 匹配的 Matcher。
     *
     * @param event 目标生命周期事件
     * @param glob 只支持星号与问号的有界主体表达式
     * @return 同时匹配事件和主体的 Matcher
     */
    public static HookMatcher subject(HookEventKind event, String glob) {
        return new HookMatcher(event, Optional.of(glob));
    }

    /**
     * 判断一个生命周期请求是否匹配。
     *
     * @param invocation 生命周期请求
     * @return 匹配时为 {@code true}
     */
    public boolean matches(HookInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        if (event != invocation.event()) {
            return false;
        }
        return subjectGlob.map(glob -> globMatches(glob, invocation.subject())).orElse(true);
    }

    private static String validateGlob(String value) {
        Objects.requireNonNull(value, "subjectGlob 不能为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("subjectGlob 不能为空白");
        }
        if (value.codePointCount(0, value.length()) > MAX_PATTERN_CHARACTERS) {
            throw new IllegalArgumentException("subjectGlob 超过字符上限");
        }
        return value;
    }

    /*
     * 经典双指针 wildcard 匹配：只回退到最近一个星号，额外空间为 O(1)。
     * pattern 与 subject 均已限制为 256 个 Unicode 字符，因此最坏工作量也有硬上限。
     */
    private static boolean globMatches(String pattern, String subject) {
        int[] glob = pattern.codePoints().toArray();
        int[] value = subject.codePoints().toArray();
        int patternIndex = 0;
        int valueIndex = 0;
        int starIndex = -1;
        int starValueIndex = -1;
        while (valueIndex < value.length) {
            if (patternIndex < glob.length
                    && (glob[patternIndex] == '?' || glob[patternIndex] == value[valueIndex])) {
                patternIndex++;
                valueIndex++;
            } else if (patternIndex < glob.length && glob[patternIndex] == '*') {
                starIndex = patternIndex++;
                starValueIndex = valueIndex;
            } else if (starIndex >= 0) {
                patternIndex = starIndex + 1;
                valueIndex = ++starValueIndex;
            } else {
                return false;
            }
        }
        while (patternIndex < glob.length && glob[patternIndex] == '*') {
            patternIndex++;
        }
        return patternIndex == glob.length;
    }
}
