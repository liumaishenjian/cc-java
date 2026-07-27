package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 表示模型提出的一次结构化 Tool 调用意图。
 *
 * <p>Call ID 在单个 Session 的规范历史中必须唯一。模型只能提出该意图，
 * 是否执行以及如何执行由确定性的 Runtime 和 Pipeline 决定。</p>
 *
 * @param id        Provider 生成的稳定 Call ID
 * @param name      目标 Tool 名称
 * @param arguments 递归不可变的参数对象
 * @since 0.1.0
 */
public record ToolCall(String id, String name, JsonObject arguments) {

    /**
     * 校验结构化调用内容后创建 Tool Call。
     *
     * @param id Provider 生成的稳定 Call ID
     * @param name 目标 Tool 名称
     * @param arguments 递归不可变的参数对象
     * @throws NullPointerException ID、名称或参数为空时
     * @throws IllegalArgumentException ID 或名称为空白时
     */
    public ToolCall {
        id = requireText(id, "id");
        name = requireText(name, "name");
        arguments = Objects.requireNonNull(arguments, "arguments 不能为空");
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白");
        }
        return value;
    }
}
