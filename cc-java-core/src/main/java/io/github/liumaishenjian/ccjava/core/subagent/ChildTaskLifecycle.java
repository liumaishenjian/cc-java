package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskRequest;
import java.util.Optional;

/**
 * 子任务 Scope 物化前与唯一终态持久后的生命周期端口。
 *
 * <p>Start 返回值只能作为独立子 Session 的非可信 Context；Stop 必须 observe-only。</p>
 *
 * @since 0.12.0
 */
public interface ChildTaskLifecycle {
    /**
     * 在 scope 与模型副作用前运行可阻断 start Hook。
     *
     * @param request 当前委托请求
     * @param cancellationToken 父级取消身份
     * @return 可选的有界不可信 child Context
     */
    Optional<String> beforeStart(ChildTaskRequest request, CancellationToken cancellationToken);

    /**
     * 在 durable terminal 后执行只观察的 Stop 生命周期。
     *
     * @param report 已持久化的隐私安全终态
     * @return 可选的有界非可信 Context；宿主只可投影到父 Session 下一模型回合
     */
    Optional<String> afterTerminal(ChildTaskReport report);

    /**
     * 创建不附加 Context 且不阻断的默认生命周期。
     *
     * @return no-op lifecycle
     */
    static ChildTaskLifecycle noop() {
        return new ChildTaskLifecycle() {
            @Override
            public Optional<String> beforeStart(
                    ChildTaskRequest request, CancellationToken token) {
                return Optional.empty();
            }

            @Override
            public Optional<String> afterTerminal(ChildTaskReport report) {
                return Optional.empty();
            }
        };
    }
}
