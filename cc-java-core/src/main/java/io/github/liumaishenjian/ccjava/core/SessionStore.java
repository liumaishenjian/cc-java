package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import java.util.Optional;

/**
 * 管理当前进程内可用 Agent Session 的核心端口。
 *
 * <p>S01 的契约只保证内存生命周期，不承诺持久化或恢复语义。
 * 版本化 JSONL、resume 和 fork 在 S06 通过新的存储适配器扩展。</p>
 *
 * @since 0.1.0
 */
public interface SessionStore {

    /**
     * 创建并注册一个新 Session。
     *
     * @param spec Session 初始配置
     * @return 新 Session
     */
    AgentSession create(SessionSpec spec);

    /**
     * 按 ID 查找 Session。
     *
     * @param id Session ID
     * @return 未注册时为空
     */
    Optional<AgentSession> find(SessionId id);

    /**
     * 显式关闭一个 Session。
     *
     * @param id Session ID
     * @throws IllegalArgumentException Session 不存在时抛出
     */
    void close(SessionId id);
}
