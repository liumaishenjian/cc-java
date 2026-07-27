package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 记录真实执行次数，并允许测试注入参数校验与执行行为的 Fake Tool。
 *
 * <p>只有进入 {@link #execute(ToolInvocation)} 的调用才会被记录；因此测试可以
 * 准确证明未知 Tool、无效参数和预算拒绝没有产生执行副作用。</p>
 */
final class RecordingAgentTool implements AgentTool {

    @FunctionalInterface
    interface ToolBehavior {

        ToolExecutionOutcome execute(ToolInvocation invocation) throws Exception;
    }

    private final ToolDefinition definition;
    private final Function<JsonObject, ToolValidationResult> validator;
    private final ToolBehavior behavior;
    private final List<ToolInvocation> invocations = new ArrayList<>();

    RecordingAgentTool(
            String name,
            Function<JsonObject, ToolValidationResult> validator,
            ToolBehavior behavior) {
        this.definition = ToolDefinition.readOnlyText(
                name,
                "用于 S01 离线测试的 " + name,
                """
                {
                  "type": "object"
                }
                """);
        this.validator = Objects.requireNonNull(validator, "validator 不能为空");
        this.behavior = Objects.requireNonNull(behavior, "behavior 不能为空");
    }

    /**
     * 创建始终校验通过、并为每次调用返回固定正文的 Tool。
     *
     * @param name    Tool 名称
     * @param content 固定成功输出
     * @return Recording Tool
     */
    static RecordingAgentTool succeeding(String name, String content) {
        return new RecordingAgentTool(
                name,
                ignored -> ToolValidationResult.validResult(),
                ignored -> ToolExecutionOutcome.success(content));
    }

    /**
     * 创建始终校验通过、但执行时抛出指定异常的 Tool。
     *
     * @param name      Tool 名称
     * @param exception 执行阶段异常
     * @return Recording Tool
     */
    static RecordingAgentTool throwing(String name, Exception exception) {
        Objects.requireNonNull(exception, "exception 不能为空");
        return new RecordingAgentTool(
                name,
                ignored -> ToolValidationResult.validResult(),
                ignored -> {
                    throw exception;
                });
    }

    /**
     * 创建始终返回参数错误、永远不应进入执行阶段的 Tool。
     *
     * @param name      Tool 名称
     * @param violation 参数问题说明
     * @return Recording Tool
     */
    static RecordingAgentTool invalid(String name, String violation) {
        return new RecordingAgentTool(
                name,
                ignored -> ToolValidationResult.invalid(violation),
                ignored -> {
                    throw new AssertionError("参数校验失败后不应执行 Tool");
                });
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        return validator.apply(arguments);
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) throws Exception {
        invocations.add(invocation);
        return behavior.execute(invocation);
    }

    /**
     * 返回已经进入执行阶段的调用快照。
     *
     * @return 按执行顺序排列的调用
     */
    List<ToolInvocation> invocations() {
        return List.copyOf(invocations);
    }
}
