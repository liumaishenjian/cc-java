package io.github.liumaishenjian.ccjava.core.hook;

import io.github.liumaishenjian.ccjava.domain.hook.HookFailurePolicy;
import io.github.liumaishenjian.ccjava.domain.hook.HookMatcher;
import java.util.Objects;

/**
 * 一个已装载 Hook 的 Matcher、Handler 和失败策略。
 *
 * <p>绑定顺序由装配层显式给出，聚合不会依赖 HashMap 或线程完成顺序。未信任的
 * 绑定在进入该对象前应被过滤；这里的 {@code trusted} 是安全 Gate 的结果，不是
 * Handler 自己声称的属性。</p>
 *
 * @param id 稳定绑定 ID
 * @param matcher 事件和主体匹配器
 * @param handler 外部执行端口
 * @param failurePolicy Handler 失败时的策略
 * @param trusted 是否已通过来源信任检查
 * @param order 同一配置层内的稳定顺序
 * @since 0.1.0
 */
public record HookBinding(
        String id,
        HookMatcher matcher,
        HookHandler handler,
        HookFailurePolicy failurePolicy,
        boolean trusted,
        int order) {

    /**
     * 校验绑定字段。
     */
    public HookBinding {
        id = requireText(id, "id");
        matcher = Objects.requireNonNull(matcher, "matcher 不能为空");
        handler = Objects.requireNonNull(handler, "handler 不能为空");
        failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy 不能为空");
        if (order < 0) {
            throw new IllegalArgumentException("order 不能小于 0");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
        return value;
    }
}
