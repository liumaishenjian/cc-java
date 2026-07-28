package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import picocli.CommandLine;

/**
 * 验证 Spring Boot Composition Root 不连接 Provider 也能完成 CLI 启动。
 */
class CcJavaApplicationTest {

    @Test
    void bootContextCreatesPicocliAndExplicitRuntimeFactory() {
        try (var context = new SpringApplicationBuilder(CcJavaApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .registerShutdownHook(false)
                .properties("logging.level.root=OFF")
                .run()) {
            assertThat(context.getBean(CommandLine.class)).isNotNull();
            assertThat(context.getBean(CliRuntimeFactory.class))
                    .isInstanceOf(CoreCliRuntimeFactory.class);
        }
    }
}
