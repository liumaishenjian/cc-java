package io.github.liumaishenjian.ccjava.tools.local.tool;

import java.util.Objects;

/**
 * 保存一次严格 UTF-8 读取的文本和原始字节快照。
 *
 * <p>Patch Tool 使用原始字节检测审批期间的并发修改，并使用 BOM 标记保持已有文件
 * 编码外观。字节数组在构造和访问时都复制，避免调用方改变冲突检测基线。</p>
 *
 * @since 0.4.0
 */
public final class Utf8TextDocument {

    private final String text;
    private final byte[] bytes;
    private final boolean bom;

    Utf8TextDocument(String text, byte[] bytes, boolean bom) {
        this.text = Objects.requireNonNull(text, "text 不能为空");
        this.bytes = Objects.requireNonNull(bytes, "bytes 不能为空").clone();
        this.bom = bom;
    }

    /**
     * 返回同一次严格解码得到的文本。
     *
     * @return 不可变 UTF-8 文本快照
     */
    public String text() {
        return text;
    }

    /**
     * 返回原始字节的防御性副本。
     *
     * @return 不可由调用者修改内部基线的字节副本
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * 指示原始字节是否带 UTF-8 BOM。
     *
     * @return 带 BOM 时为 {@code true}
     */
    public boolean bom() {
        return bom;
    }
}
