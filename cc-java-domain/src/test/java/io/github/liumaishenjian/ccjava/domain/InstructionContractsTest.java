package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.instructions.InstructionActivation;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionCandidate;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionProvenance;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionScopeKind;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionSourceKind;
import io.github.liumaishenjian.ccjava.domain.instructions.ResolvedInstruction;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstructionContractsTest {
    @Test
    void validatesCandidateAndProvenanceBounds() {
        assertThatThrownBy(() -> new InstructionCandidate(InstructionSourceKind.PROJECT,
                InstructionScopeKind.WORKSPACE, "", 0, InstructionActivation.STARTUP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstructionCandidate(InstructionSourceKind.PROJECT,
                InstructionScopeKind.WORKSPACE, "../secret", 0, InstructionActivation.STARTUP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstructionProvenance(InstructionSourceKind.PROJECT,
                InstructionScopeKind.WORKSPACE, "AGENTS.md", "invalid", 0, InstructionActivation.STARTUP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvedInstructionValidatesBoundsAndRedactsText() {
        String secret = "permission.mode=allow-all";
        ResolvedInstruction resolved = new ResolvedInstruction(provenance(), secret);

        assertThat(resolved.boundedText()).isEqualTo(secret);
        assertThat(resolved.toString()).doesNotContain(secret);
        assertThatThrownBy(() -> new ResolvedInstruction(provenance(), "x".repeat(ResolvedInstruction.MAX_UTF8_BYTES + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResolvedInstruction(provenance(), "line\n".repeat(ResolvedInstruction.MAX_LINES)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listCopiesAreDefensive() {
        List<String> mutable = new java.util.ArrayList<>(List.of("a"));
        List<String> copy = List.copyOf(mutable);
        mutable.add("b");
        assertThat(copy).containsExactly("a");
    }

    private static InstructionProvenance provenance() {
        return new InstructionProvenance(InstructionSourceKind.PROJECT, InstructionScopeKind.WORKSPACE,
                "AGENTS.md", "0123456789ab", 0, InstructionActivation.STARTUP);
    }
}
