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
 * 回调嵌套调用。只有正文/资源准备成功后调用 {@link #commit(SkillId)} 才改变 Scope。</p>
 *
 * @since 0.11.0
 */
public final class SkillScope implements AutoCloseable {
    private final RunId runId;
    private final LinkedHashSet<SkillId> active = new LinkedHashSet<>();
    private SkillId preparing;
    private boolean closed;

    public SkillScope(RunId runId) { this.runId = Objects.requireNonNull(runId, "runId 不能为空"); }

    public synchronized SkillErrorCode begin(SkillId id) {
        if (closed) return SkillErrorCode.CANCELLED;
        if (preparing != null) return SkillErrorCode.NESTED_INVOCATION;
        if (active.contains(id)) return SkillErrorCode.ALREADY_ACTIVATED;
        preparing = id;
        return null;
    }

    public synchronized void commit(SkillId id) {
        if (!Objects.equals(preparing, id) || closed) throw new IllegalStateException("Skill 未处于准备状态");
        active.add(id);
        preparing = null;
    }

    public synchronized void abort(SkillId id) { if (Objects.equals(preparing, id)) preparing = null; }
    public synchronized List<SkillId> activatedInOrder() { return List.copyOf(new ArrayList<>(active)); }
    public RunId runId() { return runId; }
    @Override public synchronized void close() { closed = true; preparing = null; active.clear(); }
}
