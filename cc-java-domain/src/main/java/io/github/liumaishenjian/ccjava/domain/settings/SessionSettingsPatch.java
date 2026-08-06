package io.github.liumaishenjian.ccjava.domain.settings;

import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import java.util.Objects;

/**
 * 当前 Session 内存 Settings overlay 的受限标量更新。
 *
 * <p>该补丁只能变更模型或权限默认模式；它不能携带规则、selector、ToolSource、grant、
 * 文件路径或任何持久化意图。Application 层必须以当前 overlay 为基底复制其余字段后再发布。</p>
 *
 * @since 0.8.0
 */
public sealed interface SessionSettingsPatch permits SessionSettingsPatch.ModelName, SessionSettingsPatch.PermissionModeChange {
    /**
     * 仅替换下一 Run 的模型名称。
     *
     * @param value 不含控制字符的有界模型标识
     */
    record ModelName(String value) implements SessionSettingsPatch {
        /**
         * 验证不含控制字符的有界模型标识。
         *
         * @param value Provider 在启动时已配置的候选模型名称
         */
        public ModelName {
            if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 256
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("模型名称非法");
            }
        }
        @Override public String toString() { return "ModelName[value=<redacted>]"; }
    }

    /**
     * 仅替换下一 Run 的 PermissionMode 默认值。
     *
     * @param value 已封闭的 S05 默认权限模式
     */
    record PermissionModeChange(PermissionMode value) implements SessionSettingsPatch {
        /**
         * 验证已封闭的 S05 默认权限模式。
         *
         * @param value 已封闭的 S05 默认权限模式
         */
        public PermissionModeChange { value = Objects.requireNonNull(value, "value 不能为空"); }
    }
}
