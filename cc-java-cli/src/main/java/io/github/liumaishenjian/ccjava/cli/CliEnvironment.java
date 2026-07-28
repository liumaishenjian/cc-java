package io.github.liumaishenjian.ccjava.cli;

import java.util.Optional;

/**
 * 提供 CLI 可读取的环境变量边界。
 *
 * <p>生产实现委托 {@link System#getenv(String)}；测试通过内存映射注入。调用者不得
 * 把返回的 Secret 写入日志、事件或 {@code toString()}。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface CliEnvironment {

    /**
     * 读取一个环境变量。
     *
     * @param name 环境变量名称
     * @return 未定义时为空
     */
    Optional<String> read(String name);

    /**
     * 返回进程环境变量适配器。
     *
     * @return 只读系统环境
     */
    static CliEnvironment system() {
        return name -> Optional.ofNullable(System.getenv(name));
    }
}
