package io.github.liumaishenjian.ccjava.domain;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 描述创建内存 Session 所需的稳定上下文。
 *
 * <p>系统指令和 Runtime Metadata 只用于组装模型上下文，不能扩大工具权限。
 * Metadata 按键排序并复制为不可变快照，以保证 Fake Model 回放具有确定性。</p>
 *
 * @param systemInstructions Runtime 的稳定系统指令
 * @param runtimeMetadata    OS、Workspace 等非敏感运行信息
 * @since 0.1.0
 */
public record SessionSpec(String systemInstructions, Map<String, String> runtimeMetadata) {

    /**
     * 校验系统指令并规范化 Runtime Metadata 后创建 Session 配置。
     *
     * @param systemInstructions Runtime 的稳定系统指令
     * @param runtimeMetadata OS、Workspace 等非敏感运行信息
     * @throws NullPointerException 系统指令或元数据映射为空时
     * @throws IllegalArgumentException 指令为空白，或元数据包含无效键值时
     */
    public SessionSpec {
        Objects.requireNonNull(systemInstructions, "systemInstructions 不能为空");
        Objects.requireNonNull(runtimeMetadata, "runtimeMetadata 不能为空");
        if (systemInstructions.isBlank()) {
            throw new IllegalArgumentException("systemInstructions 不能为空白");
        }

        TreeMap<String, String> sorted = new TreeMap<>();
        runtimeMetadata.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("runtimeMetadata 的键不能为空");
            }
            if (value == null) {
                throw new IllegalArgumentException("runtimeMetadata 的值不能为空");
            }
            sorted.put(key, value);
        });
        runtimeMetadata = Collections.unmodifiableMap(sorted);
    }

    /**
     * 创建不包含额外 Runtime Metadata 的 Session 配置。
     *
     * @param systemInstructions Runtime 的稳定系统指令
     * @return 不包含额外元数据的配置
     */
    public static SessionSpec of(String systemInstructions) {
        return new SessionSpec(systemInstructions, Map.of());
    }
}
