package io.github.liumaishenjian.ccjava.core.hook;

import io.github.liumaishenjian.ccjava.domain.hook.HookSourceKind;
import java.util.Objects;
import java.util.Optional;

/**
 * 提供已经由用户确认的 Hook 指纹。
 *
 * <p>该端口只返回不含配置正文的指纹，不负责读取文件、提示用户或修改持久化 Settings。
 * S09 第一切片用内存 Fake 验证 Gate；实际来源和 Trust UI 由 CLI Composition Root 后续装配。</p>
 *
 * @since 0.9.0
 */
@FunctionalInterface
public interface HookTrustStore {

    /**
     * 查询某来源中某个绑定 ID 的已批准指纹。
     *
     * @param bindingId 稳定 Hook 绑定 ID
     * @param source 声明来源
     * @return 已批准指纹；没有显式批准时为空
     */
    Optional<String> approvedFingerprint(String bindingId, HookSourceKind source);

    /** 返回没有任何显式批准记录的安全实现。 */
    static HookTrustStore none() {
        return (bindingId, source) -> Optional.empty();
    }

    /**
     * 创建一个只读的单绑定批准 Fake，供离线测试和 Demo 使用。
     *
     * @param expectedBindingId 稳定绑定 ID
     * @param expectedSource 来源
     * @param fingerprint 已批准的 64 位十六进制指纹
     * @return 固定查询结果
     */
    static HookTrustStore single(String expectedBindingId, HookSourceKind expectedSource, String fingerprint) {
        String id = Objects.requireNonNull(expectedBindingId, "expectedBindingId 不能为空");
        HookSourceKind source = Objects.requireNonNull(expectedSource, "expectedSource 不能为空");
        String expected = Objects.requireNonNull(fingerprint, "fingerprint 不能为空");
        return (bindingId, actualSource) -> id.equals(bindingId) && source == actualSource
                ? Optional.of(expected)
                : Optional.empty();
    }
}
