package io.github.liumaishenjian.ccjava.protocol;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * 每次服务启动生成的高熵本机 capability token。
 *
 * <p>字符串表示永不返回原 token；验证使用常量时间摘要比较。</p>
 *
 * @since 0.1.0
 */
public final class CapabilityToken {
    private final byte[] value;

    private CapabilityToken(byte[] value) {
        this.value = value.clone();
    }

    /**
     * 生成一次进程生命周期使用的高熵 token。
     *
     * @return 使用 SecureRandom 生成的 256-bit token
     */
    public static CapabilityToken generate() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new CapabilityToken(bytes);
    }

    /**
     * 从本机安全通道提供的 URL-safe Base64 恢复 token。
     *
     * @param encoded 不带 padding 的 token 文本
     * @return 解析后的 token
     */
    public static CapabilityToken parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded 不能为空");
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        if (bytes.length != 32) {
            throw new IllegalArgumentException("token 长度非法");
        }
        return new CapabilityToken(bytes);
    }

    /**
     * 仅供安全交付给已授权本机 Client。
     *
     * @return URL-safe Base64 token；调用方不得记录或持久化
     */
    public String reveal() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /**
     * 常量时间验证候选 token。
     *
     * @param candidate Client 提交的 URL-safe Base64 token
     * @return 长度与内容均匹配时为 true
     */
    public boolean matches(String candidate) {
        try {
            return MessageDigest.isEqual(value, Base64.getUrlDecoder().decode(candidate));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "CapabilityToken[<redacted>]";
    }
}
