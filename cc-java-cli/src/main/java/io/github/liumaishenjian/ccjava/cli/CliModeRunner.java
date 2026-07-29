package io.github.liumaishenjian.ccjava.cli;

/**
 * 隔离命令解析与 Headless 模式执行的应用端口。
 *
 * <p>Picocli 测试通过 Fake 实现验证参数和退出码映射；生产实现再装配 Provider、
 * Runtime 与 stdio。接口不进入 Core，因为运行模式属于 Surface 关注点。</p>
 *
 * @since 0.1.0
 */
interface CliModeRunner {

    /**
     * 执行一次性 Print。
     *
     * @param prompt 用户输入
     * @param overrides 已完成类型校验的非 Secret CLI Override
     * @return 稳定进程退出码
     */
    int runPrint(String prompt, CliOverrides overrides);

    /**
     * 启动内部 stdio v0 Server。
     *
     * @param overrides 已完成类型校验的非 Secret CLI Override
     * @return 稳定进程退出码
     */
    int runStdio(CliOverrides overrides);
}
