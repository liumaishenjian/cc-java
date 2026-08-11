package io.github.liumaishenjian.ccjava.domain.plugin;

import java.util.Objects;

/**
 * Plugin 签名验证 Port 的最小信封。
 *
 * <p>验证成功只说明签名字节与 payload digest/key reference 匹配；不证明 publisher identity、
 * revocation、root rotation、透明日志或安全性。</p>
 *
 * @param algorithm 封闭算法 ID
 * @param keyReference 宿主配置的 key reference
 * @param payloadDigest 被签名内容 SHA-256
 * @param signature Base64URL signature
 * @since 0.1.0
 */
public record PluginSignatureEnvelope(String algorithm, String keyReference, String payloadDigest, String signature) {
    /** 校验封闭算法、key reference、payload digest 与有界签名字节。 */
    public PluginSignatureEnvelope { algorithm=text(algorithm,"algorithm",32); keyReference=text(keyReference,"keyReference",128); if (payloadDigest==null || !payloadDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("payloadDigest 非法"); signature=text(signature,"signature",8192); }
    private static String text(String value,String field,int max){Objects.requireNonNull(value,field+" 不能为空");if(value.isBlank()||value.length()>max||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException(field+" 非法");return value;}
    @Override public String toString(){return "PluginSignatureEnvelope[algorithm="+algorithm+", keyReference="+keyReference+", payloadDigest="+payloadDigest+", signature=<redacted>]";}
}
