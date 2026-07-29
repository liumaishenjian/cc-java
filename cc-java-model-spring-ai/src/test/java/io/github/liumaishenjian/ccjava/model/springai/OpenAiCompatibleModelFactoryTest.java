package io.github.liumaishenjian.ccjava.model.springai;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 OpenAI-compatible Base URL 的确定性规范化规则。
 *
 * @since 0.1.0
 */
class OpenAiCompatibleModelFactoryTest {

    @Test
    void appendsV1ToServiceRootAndRemovesTrailingSlash() {
        assertThat(OpenAiCompatibleModelFactory.chatApiBaseUrl(
                URI.create("https://gateway.example.test/")))
                .isEqualTo("https://gateway.example.test/v1");
    }

    @Test
    void preservesAnExistingV1Path() {
        assertThat(OpenAiCompatibleModelFactory.chatApiBaseUrl(
                URI.create("https://gateway.example.test/v1/")))
                .isEqualTo("https://gateway.example.test/v1");
    }
}
