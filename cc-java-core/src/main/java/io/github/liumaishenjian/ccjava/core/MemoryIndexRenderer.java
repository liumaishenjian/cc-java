package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.MemoryCatalog;
import io.github.liumaishenjian.ccjava.domain.MemoryIndex;

/**
 * 把无路径 M3 Catalog 渲染为有界 M2 索引的 Port。
 *
 * @since 0.7.0
 */
@FunctionalInterface
public interface MemoryIndexRenderer {

    /**
     * 按 Catalog 顺序渲染相对链接和 hook。
     *
     * @param catalog 已重建 Catalog
     * @return 有界索引和截断诊断
     */
    MemoryIndex render(MemoryCatalog catalog);
}
