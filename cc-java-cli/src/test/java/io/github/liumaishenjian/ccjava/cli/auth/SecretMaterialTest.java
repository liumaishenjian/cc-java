package io.github.liumaishenjian.ccjava.cli.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SecretMaterialTest {
    @Test
    void validatesRedactsCopiesAndClearsOwnedArray() throws Exception {
        char[] source = "unit-secret-sentinel".toCharArray();
        SecretMaterial material = new SecretMaterial(source);
        Arrays.fill(source, 'x');
        char[] copy = material.copyChars();
        assertThat(copy).containsExactly("unit-secret-sentinel".toCharArray());
        Arrays.fill(copy, '\0');
        assertThat(material.toString()).isEqualTo("<redacted>");
        material.close();
        material.close();
        Field field = SecretMaterial.class.getDeclaredField("value");
        field.setAccessible(true);
        assertThat((char[]) field.get(material)).containsOnly('\0');
        assertThatThrownBy(material::copyChars).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankForbiddenAndOversizedValues() {
        assertThatThrownBy(() -> new SecretMaterial(new char[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretMaterial("   ".toCharArray())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretMaterial("a\nb".toCharArray())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretMaterial(new char[16 * 1024 + 1])).isInstanceOf(IllegalArgumentException.class);
    }
}
