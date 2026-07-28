package io.github.liumaishenjian.ccjava.cli;

/**
 * 在 CLI Composition Root 中创建一个连续的进程内 Runtime Session。
 *
 * <p>该工厂是当前 CLI 与正在演进的 Spring AI Adapter/Core 取消 API 之间唯一的
 * 装配缝隙。Provider 可通过 {@link CliEnvironment} 读取 Secret，但不得把值写入
 * {@link CliConfiguration} 或事件。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface CliRuntimeFactory {

    /**
     * 创建 Runtime 与 Session。
     *
     * @param configuration 已解析且不含 Secret 的配置
     * @param environment   Provider 按需读取 Secret 的边界
     * @param listener      终端事件消费者
     * @return 可连续执行多个 Run 的 Session
     * @throws CliStartupException Provider 或 Runtime 无法安全装配时
     */
    CliRuntime create(
            CliConfiguration configuration,
            CliEnvironment environment,
            CliEventListener listener) throws CliStartupException;
}
