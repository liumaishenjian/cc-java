package io.github.liumaishenjian.ccjava.protocol;

/**
 * stable protocol 版本。
 *
 * @param major 不兼容变化
 * @param minor 向后兼容能力增加
 * @since 0.1.0
 */
public record ProtocolVersion(int major, int minor) implements Comparable<ProtocolVersion> {
    /** 首个稳定协议版本。 */
    public static final ProtocolVersion V1_0 = new ProtocolVersion(1, 0);
    /** 校验 major 为正且 minor 非负。 */
    public ProtocolVersion { if (major < 1 || minor < 0) throw new IllegalArgumentException("协议版本非法"); }
    @Override public int compareTo(ProtocolVersion other) {
        int majorResult = Integer.compare(major, other.major);
        return majorResult != 0 ? majorResult : Integer.compare(minor, other.minor);
    }
}
