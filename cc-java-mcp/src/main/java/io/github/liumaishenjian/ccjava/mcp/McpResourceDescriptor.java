package io.github.liumaishenjian.ccjava.mcp;

import java.util.Objects;

/**
 * MCP Resource 的有界元数据投影，不读取正文。
 *
 * @param uri 远端 Resource URI 字符串
 * @param name 远端显示名称
 * @param description 可为空字符串的说明
 * @param mimeType 缺失时规范为二进制类型
 */
public record McpResourceDescriptor(String uri, String name, String description, String mimeType) {
    /** 校验必需字段并规范可选元数据。 */
    public McpResourceDescriptor {
        uri = required(uri, "uri");
        name = required(name, "name");
        description = description == null ? "" : description;
        mimeType = mimeType == null ? "application/octet-stream" : mimeType;
    }
    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > 2_048) {
            throw new IllegalArgumentException(field + " 无效");
        }
        return value;
    }
}
