package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 由有序 Catalog 元数据与诊断稳定派生的 revision。
 *
 * <p>Revision 不包含文件系统路径，可用于检测 M3 派生目录是否变化。</p>
 *
 * @param value 小写十六进制 SHA-256 摘要
 * @since 0.7.0
 */
public record MemoryCatalogRevision(String value) {

    /** 校验 revision 格式。 */
    public MemoryCatalogRevision {
        value = Objects.requireNonNull(value, "value 不能为空");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Memory Catalog revision 必须是 SHA-256 十六进制摘要");
        }
    }
}
