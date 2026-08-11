package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import java.util.Objects;

/**
 * 在 Skill 正文/资源验证成功后，为当前 Run 绑定 descriptor 引用的可信 Hook templates。
 *
 * <p>实现不得从 descriptor 路径或正文构造可信 Handler；只能查找 Session composition 已冻结的
 * template catalog。返回 lease 由 Skill Run scope 持有到唯一终态并 exactly-once 关闭。</p>
 *
 * @since 0.11.0
 */
@FunctionalInterface
public interface SkillHookBinder {
    /**
     * 绑定当前 Skill 的 Hook 引用；没有引用时返回 no-op lease。
     *
     * @param runId 当前 Run identity
     * @param descriptor 已通过正文与资源验证的 Skill descriptor
     * @return 必须在 Run 唯一终态关闭的 binding lease
     */
    AutoCloseable bind(RunId runId, SkillDescriptor descriptor);

    /**
     * 返回不绑定 Hook 的共享实现。
     *
     * @return 始终返回 no-op lease 的 binder
     */
    static SkillHookBinder none() {
        return (runId, descriptor) -> {
            Objects.requireNonNull(runId, "runId 不能为空");
            Objects.requireNonNull(descriptor, "descriptor 不能为空");
            return () -> { };
        };
    }
}
