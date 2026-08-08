package io.github.liumaishenjian.ccjava.domain.hook;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 对事件种类和有界主体名执行确定性匹配的值对象。
 *
 * <p>首版只匹配事件和主体，不把文件系统路径或任意 Prompt 文本作为隐式范围。
 * 正则在构造时编译并限制长度；执行层仍应对来自配置的 Matcher 做信任和资源
 * 上限校验。</p>
 *
 * @param event 目标事件
 * @param subjectRegex 可选的主体正则；为空表示匹配该事件的所有主体
 * @since 0.1.0
 */
public record HookMatcher(HookEventKind event, Optional<String> subjectRegex) {

    /** Matcher 正则表达式最大字符数。 */
    public static final int MAX_PATTERN_CHARACTERS = 256;

    /**
     * 校验并创建 Matcher。
     */
    public HookMatcher {
        event = Objects.requireNonNull(event, "event 不能为空");
        subjectRegex = Objects.requireNonNull(subjectRegex, "subjectRegex 不能为空")
                .map(HookMatcher::validatePattern);
    }

    /** 创建只按事件匹配的 Matcher。 */
    public static HookMatcher event(HookEventKind event) {
        return new HookMatcher(event, Optional.empty());
    }

    /** 创建按事件和主体正则匹配的 Matcher。 */
    public static HookMatcher subject(HookEventKind event, String regex) {
        return new HookMatcher(event, Optional.of(regex));
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
        return subjectRegex.map(regex -> Pattern.matches(regex, invocation.subject())).orElse(true);
    }

    private static String validatePattern(String value) {
        Objects.requireNonNull(value, "subjectRegex 不能为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("subjectRegex 不能为空白");
        }
        if (value.codePointCount(0, value.length()) > MAX_PATTERN_CHARACTERS) {
            throw new IllegalArgumentException("subjectRegex 超过字符上限");
        }
        try {
            Pattern.compile(value);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("subjectRegex 不是有效正则", exception);
        }
        return value;
    }
}
