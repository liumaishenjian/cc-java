package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillErrorCode;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 单 Run Skill 激活状态机。
 *
 * <p>不同 Skill 按成功提交顺序各激活一次；准备期间设置 reentrant guard，禁止正文、资源或
 * 回调嵌套调用。只有正文、资源和 Hook lease 都成功后调用 {@link #commit(SkillId)} 才改变
 * Scope；失败路径必须调用 {@link #abort(SkillId)}。</p>
 *
 * @since 0.11.0
 */
public final class SkillScope implements AutoCloseable {
    private final RunId runId;
    private final LinkedHashSet<SkillId> active = new LinkedHashSet<>();
    private SkillId preparing;
    private boolean closed;

    /**
     * 创建绑定单个 Run 的空激活 Scope。
     *
     * @param runId Scope 所属 Run
     */
    public SkillScope(RunId runId) { this.runId = Objects.requireNonNull(runId, "runId 不能为空"); }

    /**
     * 开始准备一个尚未激活的 Skill，并设置嵌套调用 guard。
     *
     * @param id 待激活 Skill ID
     * @return 允许准备时为空，否则为固定拒绝码
     */
    public synchronized SkillErrorCode begin(SkillId id) {
        if (closed) return SkillErrorCode.CANCELLED;
        if (preparing != null) return SkillErrorCode.NESTED_INVOCATION;
        if (active.contains(id)) return SkillErrorCode.ALREADY_ACTIVATED;
        preparing = id;
        return null;
    }

    /**
     * 提交已经通过内容、资源和 Hook Gate 的 Skill。
     *
     * @param id 当前 preparing Skill ID
     */
    public synchronized void commit(SkillId id) {
        if (!Objects.equals(preparing, id) || closed) throw new IllegalStateException("Skill 未处于准备状态");
        active.add(id);
        preparing = null;
    }

    /**
     * 放弃当前 Skill 准备，不改变已激活顺序。
     *
     * @param id 当前 preparing Skill ID
     */
    public synchronized void abort(SkillId id) { if (Objects.equals(preparing, id)) preparing = null; }

    /**
     * 回滚刚提交但 durable activation 未完成的最后一个 Skill。
     *
     * @param id 必须是最后提交的 Skill ID
     */
    public synchronized void rollbackLast(SkillId id) {
        List<SkillId> ordered = new ArrayList<>(active);
        if (ordered.isEmpty() || !ordered.getLast().equals(id)) {
            throw new IllegalStateException("只能回滚最后提交的 Skill");
        }
        active.remove(id);
    }

    /**
     * 返回已激活 Skill 的稳定提交顺序。
     *
     * @return 不可变 Skill ID 列表
     */
    public synchronized List<SkillId> activatedInOrder() { return List.copyOf(new ArrayList<>(active)); }
    /**
     * 返回 Scope 所属 Run identity。
     *
     * @return Run identity
     */
    public RunId runId() { return runId; }
    /** 清除准备 guard 与激活投影，后续调用均视为取消。 */
    @Override public synchronized void close() { closed = true; preparing = null; active.clear(); }
}
