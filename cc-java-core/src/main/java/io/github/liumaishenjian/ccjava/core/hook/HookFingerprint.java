package io.github.liumaishenjian.ccjava.core.hook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * 生成 Hook 配置的稳定、不可逆指纹。
 *
 * <p>组件采用长度前缀编码，避免简单字符串拼接造成边界歧义。指纹只用于 Trust 比较，
 * 不承担身份认证，也不代表外部进程已经安全；调用方仍须经过 Workspace、权限和进程边界。</p>
 *
 * @since 0.9.0
 */
public final class HookFingerprint {

    private HookFingerprint() {
    }

    /**
     * 对有序配置组件计算 SHA-256 指纹。
     *
     * @param components 已规范化的配置组件，不得为 null
     * @return 小写 64 位十六进制摘要
     */
    public static String sha256(List<String> components) {
        Objects.requireNonNull(components, "components 不能为空");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String component : components) {
                String value = Objects.requireNonNull(component, "fingerprint component 不能为空");
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(value & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }
}
