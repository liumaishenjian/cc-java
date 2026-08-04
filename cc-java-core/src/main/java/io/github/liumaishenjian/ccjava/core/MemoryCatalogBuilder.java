package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.MemoryCatalog;

/**
 * 从 Adapter 持有的本地 M1 root 重建无路径 M3 Catalog 的 Port。
 *
 * <p>Core 不传递或感知 {@code Path}；文件枚举、真实路径校验、UTF-8 与 frontmatter 解析均由
 * Adapter 负责。逐文件失败应隔离为 Catalog 诊断，而不是使整个重建失败。</p>
 *
 * @since 0.7.0
 */
@FunctionalInterface
public interface MemoryCatalogBuilder {

    /**
     * 有界重建当前 Catalog。
     *
     * @return 不含绝对路径和 topic 正文的 Catalog
     */
    MemoryCatalog rebuild();
}
