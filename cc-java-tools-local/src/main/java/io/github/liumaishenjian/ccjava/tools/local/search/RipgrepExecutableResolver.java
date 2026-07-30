package io.github.liumaishenjian.ccjava.tools.local.search;

import java.util.List;
import java.util.Objects;

/**
 * 解析可信 ripgrep 可执行入口的适配器缝隙。
 *
 * <p>解析结果只能来自应用配置或发行物，不得包含模型输入。当前默认实现选择系统
 * {@code PATH} 中的 {@code rg}；未来可以在不改变 Tool 协议的情况下接入随发行物提供的
 * 二进制或平台专用解析器。</p>
 *
 * @since 0.3.1
 */
@FunctionalInterface
public interface RipgrepExecutableResolver {

    /**
     * 返回可执行文件及可信固定前置参数。
     *
     * @return 非空、不可由模型修改的命令前缀
     */
    List<String> resolveCommandPrefix();

    /**
     * 创建使用系统 {@code PATH} 的默认解析器。
     *
     * @return 系统 ripgrep 解析器
     */
    static RipgrepExecutableResolver systemPath() {
        return fixed(List.of("rg"));
    }

    /**
     * 创建固定命令前缀解析器，主要供发行物装配和进程边界测试使用。
     *
     * @param commandPrefix 可执行文件及固定前置参数
     * @return 固定解析器
     */
    static RipgrepExecutableResolver fixed(List<String> commandPrefix) {
        List<String> snapshot = validate(commandPrefix);
        return () -> snapshot;
    }

    /**
     * 校验并复制解析器返回值，防止空命令或调用方后续修改。
     *
     * @param commandPrefix 待校验命令前缀
     * @return 不可变快照
     */
    static List<String> validate(List<String> commandPrefix) {
        List<String> snapshot = List.copyOf(
                Objects.requireNonNull(commandPrefix, "commandPrefix 不能为空"));
        if (snapshot.isEmpty()
                || snapshot.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("commandPrefix 不能为空");
        }
        return snapshot;
    }
}
