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
final class Utf8TextDocument {

    private final String text;
    private final byte[] bytes;
    private final boolean bom;

    Utf8TextDocument(String text, byte[] bytes, boolean bom) {
        this.text = Objects.requireNonNull(text, "text 不能为空");
        this.bytes = Objects.requireNonNull(bytes, "bytes 不能为空").clone();
        this.bom = bom;
    }

    String text() {
        return text;
    }

    byte[] bytes() {
        return bytes.clone();
    }

    boolean bom() {
        return bom;
    }
}
