package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;

/**
 * 为 Session 和 Run 提供可替换的稳定标识生成策略。
 *
 * <p>测试可以使用顺序 ID 保持回放确定性，生产装配可使用 UUID。</p>
 *
 * @since 0.1.0
 */
public interface AgentIdGenerator {

    /**
     * 生成新的 Session ID。
     *
     * @return 在当前 Store 中唯一的 Session ID
     */
    SessionId newSessionId();

    /**
     * 生成新的 Run ID。
     *
     * @return 在所属 Session 中唯一的 Run ID
     */
    RunId newRunId();
}
