package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import io.github.liumaishenjian.ccjava.domain.skill.SkillResourceSnapshot;
import java.util.List;

/** 按 descriptor 已声明逻辑名读取有界资源的 Port。 @since 0.11.0 */
@FunctionalInterface
public interface SkillResourceReader {
    List<SkillResourceSnapshot> read(SkillDescriptor descriptor, CancellationToken cancellationToken);
}
