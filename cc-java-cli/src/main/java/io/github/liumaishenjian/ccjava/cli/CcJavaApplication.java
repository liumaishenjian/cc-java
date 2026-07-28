package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.model.springai.OllamaModelConfiguration;
import io.github.liumaishenjian.ccjava.model.springai.SpringAiOllamaModelGateway;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import picocli.CommandLine;

/**
 * S02 CLI 的 Spring Boot Composition Root。
 *
 * <p>Boot 只负责创建配置解析器、终端适配器、真实模型 Gateway 工厂和显式
 * Core Runtime 工厂；它不扫描 Domain/Core，也不接管 Agent Loop。Provider
 * 对象在参数校验通过后才按 Run 模式惰性创建，因此 {@code --help} 和无效参数
 * 不会连接 Ollama。</p>
 *
 * @since 0.1.0
 */
@SpringBootConfiguration(proxyBeanMethods = false)
public class CcJavaApplication {

    /**
     * 创建无状态的 S02 Composition Root。
     *
     * <p>构造器不访问环境变量、不连接 Provider，也不创建终端；所有外部资源都由
     * 对应 Bean 在命令真正需要时创建。</p>
     */
    public CcJavaApplication() {
    }

    /**
     * 提供不假设本机模型名称的默认配置。
     *
     * @return S02 CLI 默认值
     */
    @Bean
    public CliDefaults cliDefaults() {
        return new CliDefaults(
                "ollama",
                "CC_JAVA_OLLAMA_API_KEY",
                false,
                Duration.ofSeconds(60),
                1,
                Path.of(""),
                "你是 cc-java 的通用 Coding Agent。",
                URI.create("http://localhost:11434"),
                4_096);
    }

    /**
     * 提供只读系统环境变量边界。
     *
     * @return 系统环境访问器
     */
    @Bean
    public CliEnvironment cliEnvironment() {
        return CliEnvironment.system();
    }

    /**
     * 提供 JLine Terminal 工厂。
     *
     * @return Terminal 工厂
     */
    @Bean
    public CliTerminalFactory cliTerminalFactory() {
        return new JLineCliTerminalFactory();
    }

    /**
     * 装配直接调用 Spring AI Ollama StreamingChatModel 的 Core 工厂。
     *
     * @return 保持显式 Agent Loop 的 CLI Runtime 工厂
     */
    @Bean
    public CliRuntimeFactory cliRuntimeFactory() {
        return new CoreCliRuntimeFactory((configuration, environment) -> {
            try {
                OllamaModelConfiguration providerConfiguration =
                        new OllamaModelConfiguration(
                                configuration.ollamaBaseUrl().value(),
                                configuration.model().value(),
                                configuration.maxOutputTokens().value(),
                                0,
                                false);
                return SpringAiOllamaModelGateway.create(providerConfiguration);
            } catch (RuntimeException exception) {
                throw new CliStartupException(
                        CliExitCode.CONFIGURATION,
                        "Ollama Provider 配置无效");
            }
        });
    }

    /**
     * 创建 Main 与测试共用的 Picocli 命令对象。
     *
     * @param defaults 默认配置
     * @param environment 环境变量边界
     * @param runtimeFactory Runtime Composition 工厂
     * @param terminalFactory Terminal 工厂
     * @return 可执行且可注入输出的命令行
     */
    @Bean
    public CommandLine commandLine(
            CliDefaults defaults,
            CliEnvironment environment,
            CliRuntimeFactory runtimeFactory,
            CliTerminalFactory terminalFactory) {
        return CcJavaCommand.commandLine(
                defaults,
                environment,
                runtimeFactory,
                terminalFactory);
    }
}
