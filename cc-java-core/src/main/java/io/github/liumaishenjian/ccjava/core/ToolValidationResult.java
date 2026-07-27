package io.github.liumaishenjian.ccjava.core;

import java.util.List;
import java.util.Objects;

/**
 * Tool 对模型参数进行确定性校验后的结果。
 *
 * @param valid      参数是否可执行
 * @param violations 无效时可反馈给模型的全部问题
 * @since 0.1.0
 */
public record ToolValidationResult(boolean valid, List<String> violations) {

    /**
     * 校验并创建参数校验结果。
     *
     * @param valid      参数是否有效
     * @param violations 无效时可反馈给模型的问题
     * @throws NullPointerException     {@code violations} 为空时抛出
     * @throws IllegalArgumentException 状态与问题列表不一致，或问题为空白时抛出
     */
    public ToolValidationResult {
        violations = List.copyOf(Objects.requireNonNull(violations, "violations 不能为空"));
        if (valid && !violations.isEmpty()) {
            throw new IllegalArgumentException("有效结果不能包含 violations");
        }
        if (!valid && violations.isEmpty()) {
            throw new IllegalArgumentException("无效结果必须说明至少一个问题");
        }
        if (violations.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("violation 不能为空");
        }
    }

    /**
     * 创建校验通过结果。
     *
     * @return 无问题的有效结果
     */
    public static ToolValidationResult validResult() {
        return new ToolValidationResult(true, List.of());
    }

    /**
     * 创建包含一个问题的校验失败结果。
     *
     * @param violation 可供模型纠正的说明
     * @return 无效结果
     */
    public static ToolValidationResult invalid(String violation) {
        return new ToolValidationResult(false, List.of(violation));
    }
}
