package io.github.liumaishenjian.ccjava.cli;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import picocli.CommandLine;

/**
 * cc-java 的进程入口。
 *
 * <p>当前类只拥有进程退出动作，并把已解析配置装配为 Spring AI Ollama 单回合
 * Gateway 与显式 Core Runtime。框架不会接管 Agent Loop 或自动执行 Tool。</p>
 *
 * @since 0.1.0
 */
public final class CcJavaMain {

    private CcJavaMain() {
    }

    /**
     * 解析参数、执行命令并终止进程。
     *
     * @param args CLI 参数
     */
    public static void main(String[] args) {
        int exitCode;
        try (var context = new SpringApplicationBuilder(CcJavaApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .registerShutdownHook(false)
                .properties("logging.level.root=OFF")
                .run()) {
            exitCode = context.getBean(CommandLine.class).execute(args);
        }
        System.exit(exitCode);
    }
}
