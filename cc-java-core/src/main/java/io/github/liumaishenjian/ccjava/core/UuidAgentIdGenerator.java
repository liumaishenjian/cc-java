package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.UUID;

/**
 * 使用随机 UUID 生成 Session ID 和 Run ID 的默认实现。
 *
 * @since 0.1.0
 */
public final class UuidAgentIdGenerator implements AgentIdGenerator {

    /**
     * 创建无状态的 UUID 标识生成器。
     */
    public UuidAgentIdGenerator() {
    }

    @Override
    public SessionId newSessionId() {
        return new SessionId("session-" + UUID.randomUUID());
    }

    @Override
    public RunId newRunId() {
        return new RunId("run-" + UUID.randomUUID());
    }
}
