package io.github.liumaishenjian.ccjava.tools.local.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class DeterministicMemoryKeywordPolicyTest {

    private final DeterministicMemoryKeywordPolicy policy =
            new DeterministicMemoryKeywordPolicy();

    @Test
    void normalizesPunctuationCaseAndDuplicatesInFirstSeenOrder() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(policy.extract("Java, JAVA! java? I TEST test; fix_bug"))
                    .containsExactly("java", "i", "test", "fix", "bug");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void returnsEmptyForInputWithoutLettersOrDigits() {
        assertThat(policy.extract(" \t\n---_!?…")).isEmpty();
    }

    @Test
    void capsTermsAndCodePointsDeterministically() {
        String longTerm = "界".repeat(80);
        String many = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> "term" + index)
                .collect(java.util.stream.Collectors.joining(" "));

        assertThat(policy.extract(longTerm)).containsExactly("界".repeat(64));
        assertThat(policy.extract(many))
                .hasSize(32)
                .first()
                .isEqualTo("term0");
        assertThat(policy.extract(many).getLast()).isEqualTo("term31");
    }
}
