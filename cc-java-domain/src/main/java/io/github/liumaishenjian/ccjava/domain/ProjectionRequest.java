package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * 从 Canonical Transcript 构建短生命周期 Context Projection 的不可变请求。
 *
 * <p>当前 G3-A 只接受已经组装成消息的 System 输入和规范消息。{@code protectedMessageCount}
 * 从列表尾部保护当前活动区间；该区间及任何未完成 Tool 批次不得被 C1/C2 修改。</p>
 *
 * @param canonicalMessages 请求所见的完整消息快照
 * @param capacity Context 容量
 * @param sourceRevision 规范历史版本，提交候选时用于检测来源变化
 * @param protectedMessageCount 从末尾开始不可缩减的活动消息数
 * @param overflowRecoveryAvailable 同一 Model Turn 是否仍允许一次 Overflow 恢复
 * @since 0.7.0
 */
public record ProjectionRequest(
        List<AgentMessage> canonicalMessages,
        ContextCapacity capacity,
        long sourceRevision,
        int protectedMessageCount,
        boolean overflowRecoveryAvailable) {

    /**
     * 防御性复制消息并校验活动边界。
     *
     * @throws NullPointerException 消息或容量为空时
     * @throws IllegalArgumentException revision 或活动区间非法时
     */
    public ProjectionRequest {
        canonicalMessages = List.copyOf(
                Objects.requireNonNull(canonicalMessages, "canonicalMessages 不能为空"));
        capacity = Objects.requireNonNull(capacity, "capacity 不能为空");
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision 不能为负数");
        }
        if (protectedMessageCount < 0 || protectedMessageCount > canonicalMessages.size()) {
            throw new IllegalArgumentException("protectedMessageCount 超出消息边界");
        }
    }
}
