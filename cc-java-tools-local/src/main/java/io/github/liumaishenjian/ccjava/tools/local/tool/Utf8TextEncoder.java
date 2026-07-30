package io.github.liumaishenjian.ccjava.tools.local.tool;

import java.nio.charset.StandardCharsets;

/** 为 S04 文本写入生成可选 UTF-8 BOM 字节。 */
final class Utf8TextEncoder {

    private Utf8TextEncoder() {
    }

    static byte[] encode(String text, boolean bom) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (!bom) {
            return body;
        }
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);
        return bytes;
    }
}
