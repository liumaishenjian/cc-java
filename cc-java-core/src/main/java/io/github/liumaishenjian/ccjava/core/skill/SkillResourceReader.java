package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import io.github.liumaishenjian.ccjava.domain.skill.SkillResourceSnapshot;
import java.util.List;

/**
 * 按不可变 snapshot 中 descriptor 声明的逻辑名读取有界资源的 Port。
 *
 * <p>Adapter 必须同时校验 snapshot identity 和 descriptor 结构相等，不能接受同 ID 伪造输入。</p>
 *
 * @since 0.11.0
 */
@FunctionalInterface
public interface SkillResourceReader {
    /**
     * @param snapshot 当前 Session 固定 catalog
     * @param descriptor snapshot 内原始 descriptor
     * @param cancellationToken 取消令牌
     * @return 不可变资源快照
     */
    List<SkillResourceSnapshot> read(SkillCatalogSnapshot snapshot, SkillDescriptor descriptor,
            CancellationToken cancellationToken);
}
