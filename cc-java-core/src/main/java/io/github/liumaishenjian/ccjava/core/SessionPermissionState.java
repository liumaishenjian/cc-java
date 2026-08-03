package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionRule;
import io.github.liumaishenjian.ccjava.domain.PermissionRuleSource;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.List;

/**
 * S05 当前进程内 Session 的 Permission Grant 与拒绝状态端口。
 *
 * <p>实现必须按 Session ID 隔离，关闭 Session 后清空；不得写入磁盘或尝试实现 S06
 * Checkpoint。连续拒绝只按规范化 scope 计数。</p>
 *
 * @since 0.5.0
 */
public interface SessionPermissionState {

    /**
     * 返回当前 Session 的不可变规则快照。
     *
     * @param sessionId 当前 Session
     * @return 不可变规则列表
     */
    List<PermissionRule> rules(SessionId sessionId);

    /**
     * 写入当前 Session 的具体 Allow Grant。
     *
     * @param sessionId 当前 Session
     * @param selector 具体且绑定可信 ToolSource 的范围
     */
    void grant(SessionId sessionId, PermissionSelector selector);

    /**
     * 记录一次拒绝。
     *
     * @param sessionId 当前 Session
     * @param selector 被拒绝的规范化范围
     * @return 记录后的连续拒绝次数
     */
    int recordDenial(SessionId sessionId, PermissionSelector selector);

    /**
     * 只在尚未达到给定次数时记录拒绝，避免已经固定拒绝的 scope 无界增长。
     *
     * @param sessionId 当前 Session
     * @param selector 被拒绝的规范化范围
     * @param maximum 允许记录的最大次数
     * @return 记录后或已有的次数
     */
    int recordDenialUpTo(
            SessionId sessionId,
            PermissionSelector selector,
            int maximum);

    /**
     * 返回当前 scope 的连续拒绝次数。
     *
     * @param sessionId 当前 Session
     * @param selector 查询的规范化范围
     * @return 未记录时为 0
     */
    int denialCount(SessionId sessionId, PermissionSelector selector);

    /**
     * 同 scope 成功执行后清除连续拒绝。
     *
     * @param sessionId 当前 Session
     * @param selector 已成功执行的规范化范围
     */
    void clearDenials(SessionId sessionId, PermissionSelector selector);

    /**
     * Session 关闭时清除全部内存状态。
     *
     * @param sessionId 要清理的 Session
     */
    void clear(SessionId sessionId);

    /**
     * 创建 Session Allow Rule 的共享校验逻辑。
     *
     * @param selector 具体且绑定可信 ToolSource 的范围
     * @return SESSION 来源的 ALLOW 规则
     */
    static PermissionRule sessionAllow(PermissionSelector selector) {
        return new PermissionRule(
                PermissionRuleSource.SESSION,
                PermissionDecision.ALLOW,
                selector);
    }
}
