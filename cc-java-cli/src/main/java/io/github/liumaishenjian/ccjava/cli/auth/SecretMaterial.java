package io.github.liumaishenjian.ccjava.cli.auth;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter edge 短生命周期持有的可清零 secret。
 *
 * <p>构造器复制调用方数组，调用方仍负责清零原数组；{@link #close()} 清零本对象拥有的数组。
 * 仅 {@link #copyChars()} 可显式借出副本，借用方必须在使用后清零。</p>
 *
 * @since 0.1.0
 */
public final class SecretMaterial implements AutoCloseable {
    private final char[] value;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 复制并校验 1..16 KiB、无 CR/LF/NUL 的 secret。
     *
     * @param source 由调用方持有并负责清零的原始 secret 字符数组
     */
    public SecretMaterial(char[] source) {
        Objects.requireNonNull(source, "source 不能为空");
        if (source.length == 0 || source.length > 16 * 1024
                || isBlank(source) || containsForbidden(source)) {
            throw new IllegalArgumentException("secret material 格式无效");
        }
        value = source.clone();
    }

    /**
     * 返回由调用方负责清零的短生命周期副本。
     *
     * @return 本对象所持 secret 的独立副本
     */
    public char[] copyChars() {
        if (closed.get()) throw new IllegalStateException("secret material 已关闭");
        return value.clone();
    }

    /** 清零本对象持有的数组；重复调用安全。 */
    @Override public void close() {
        if (closed.compareAndSet(false, true)) Arrays.fill(value, '\0');
    }

    @Override public String toString() { return "<redacted>"; }

    private static boolean containsForbidden(char[] value) {
        for (char c : value) if (c == 0 || c == '\r' || c == '\n') return true;
        return false;
    }
    private static boolean isBlank(char[] value) {
        for (char c : value) if (!Character.isWhitespace(c)) return false;
        return true;
    }
}
