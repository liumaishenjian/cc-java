package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.MemoryTopic;
import java.util.Optional;

/**
 * M5 按已选择名称安全加载完整 topic 的框架无关 Port。
 *
 * @since 0.7.0
 */
@FunctionalInterface
public interface MemoryBodyLoader {

    /**
     * 加载 topic；缺失、损坏或安全校验失败时为空。
     *
     * @param name 已验证 topic slug
     * @return 已验证完整 topic
     */
    Optional<MemoryTopic> load(String name);
}
