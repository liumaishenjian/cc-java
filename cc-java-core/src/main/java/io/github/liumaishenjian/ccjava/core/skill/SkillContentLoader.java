package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillContentSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;

/**
 * 调用时绑定不可变 snapshot、重验 identity/digest 并懒加载正文的 Port。
 *
 * <p>Adapter 必须拒绝任意 snapshot ID 和同 ID 伪造 descriptor。</p>
 *
 * @since 0.11.0
 */
@FunctionalInterface
public interface SkillContentLoader {
    /**
     * @param snapshot 当前 Session 固定 catalog
     * @param descriptor snapshot 内原始 descriptor
     * @param cancellationToken 取消令牌
     * @return 正文快照
     */
    SkillContentSnapshot load(SkillCatalogSnapshot snapshot, SkillDescriptor descriptor,
            CancellationToken cancellationToken);
}
