package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import org.junit.jupiter.api.Test;

class SkillIdTest {
    @Test
    void pluginGlobalLengthIsDerivedFromNamespaceAndLocalLimits() {
        String maximum = "plugin__" + "a".repeat(SkillId.MAX_LOCAL_LENGTH)
                + "__skills__" + "b".repeat(SkillId.MAX_LOCAL_LENGTH);

        assertThat(maximum).hasSize(SkillId.MAX_GLOBAL_LENGTH);
        assertThat(new SkillId(maximum).value()).isEqualTo(maximum);
        assertThatThrownBy(() -> new SkillId(maximum + "b"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
