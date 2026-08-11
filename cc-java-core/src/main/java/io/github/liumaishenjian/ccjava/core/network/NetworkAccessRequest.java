package io.github.liumaishenjian.ccjava.core.network;

import java.time.Duration;
import java.util.Objects;

/**
 * 不含 Header、凭证或正文的出站访问意图。
 *
 * @param purpose 固定用途
 * @param scheme URI scheme
 * @param host 主机名
 * @param port 端口
 * @param deadline 剩余 deadline
 * @param redirectsAllowed 是否允许重定向
 * @since 0.1.0
 */
public record NetworkAccessRequest(
        NetworkPurpose purpose,
        String scheme,
        String host,
        int port,
        Duration deadline,
        boolean redirectsAllowed) {
    /** 校验用途、目标和正 deadline 均为有界非敏感值。 */
    public NetworkAccessRequest {
        purpose = Objects.requireNonNull(purpose, "purpose 不能为空");
        scheme = bounded(scheme, "scheme", 16);
        host = bounded(host, "host", 253);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port 非法");
        deadline = Objects.requireNonNull(deadline, "deadline 不能为空");
        if (deadline.isNegative() || deadline.isZero()) throw new IllegalArgumentException("deadline 必须为正数");
    }
    private static String bounded(String value, String field, int max) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank() || value.length() > max) throw new IllegalArgumentException(field + " 非法");
        return value;
    }
}
