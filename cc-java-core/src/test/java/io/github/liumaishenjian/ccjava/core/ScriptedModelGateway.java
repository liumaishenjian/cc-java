package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 按预设 FIFO 脚本发布 Delta、返回聚合 Turn 或抛出结构化错误的离线 Fake。
 *
 * <p>脚本耗尽后使用 {@link AssertionError} 立即暴露 Runtime 的意外模型请求，
 * 避免测试把多调用一次误判为普通 Provider 故障。S01 的
 * {@link #of(ModelTurn...)} 工厂保持不变，S02 测试可以使用
 * {@link #scripted(Script...)} 描述流、重试和取消边界。</p>
 */
final class ScriptedModelGateway implements ModelGateway {

    @FunctionalInterface
    interface Script {

        ModelTurn execute(ModelCallContext context) throws ModelGatewayException;
    }

    private final Deque<Script> scripts;
    private final List<ModelRequest> requests = new ArrayList<>();

    private ScriptedModelGateway(List<Script> scripts) {
        this.scripts = new ArrayDeque<>(
                List.copyOf(Objects.requireNonNull(scripts, "scripts 不能为空")));
    }

    /**
     * 创建按参数顺序消费的模型脚本。
     *
     * @param turns 已经聚合完成的模型回合
     * @return 确定性 Fake Gateway
     */
    static ScriptedModelGateway of(ModelTurn... turns) {
        Objects.requireNonNull(turns, "turns 不能为空");
        List<Script> scripts = Arrays.stream(turns)
                .map(turn -> returning(turn))
                .toList();
        return new ScriptedModelGateway(scripts);
    }

    /**
     * 创建可以发布 Delta、失败或等待取消的模型脚本。
     *
     * @param scripts 按 Provider 尝试顺序消费的脚本
     * @return 确定性流式 Fake Gateway
     */
    static ScriptedModelGateway scripted(Script... scripts) {
        Objects.requireNonNull(scripts, "scripts 不能为空");
        return new ScriptedModelGateway(Arrays.asList(scripts));
    }

    /**
     * 创建按顺序发布 Delta 后返回完整 Turn 的脚本。
     *
     * @param turn 最终聚合回合
     * @param deltas 发布给 Observer 的文本增量
     * @return 单次模型脚本
     */
    static Script returning(ModelTurn turn, String... deltas) {
        Objects.requireNonNull(turn, "turn 不能为空");
        List<String> copiedDeltas = List.copyOf(Arrays.asList(deltas));
        return context -> {
            copiedDeltas.forEach(context.observer()::onTextDelta);
            return turn;
        };
    }

    /**
     * 创建按顺序发布 Delta 后抛出结构化失败的脚本。
     *
     * @param failure 预期模型失败
     * @param deltas 失败前发布的文本增量
     * @return 单次模型脚本
     */
    static Script failing(ModelGatewayException failure, String... deltas) {
        Objects.requireNonNull(failure, "failure 不能为空");
        List<String> copiedDeltas = List.copyOf(Arrays.asList(deltas));
        return context -> {
            copiedDeltas.forEach(context.observer()::onTextDelta);
            throw failure;
        };
    }

    @Override
    public ModelTurn complete(ModelRequest request) {
        try {
            return complete(request, ModelCallContext.unbounded());
        } catch (ModelGatewayException exception) {
            throw new IllegalStateException("直接 Fake 调用遇到模型失败", exception);
        }
    }

    @Override
    public ModelTurn complete(
            ModelRequest request,
            ModelCallContext context) throws ModelGatewayException {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        Script script;
        synchronized (this) {
            requests.add(copyRequest(request));
            if (scripts.isEmpty()) {
                throw new AssertionError("ScriptedModelGateway 没有剩余 Model Script");
            }
            script = scripts.removeFirst();
        }
        return script.execute(context);
    }

    /**
     * 返回调用时刻捕获的不可变请求快照。
     *
     * @return 按模型调用顺序排列的请求
     */
    synchronized List<ModelRequest> requests() {
        return List.copyOf(requests);
    }

    /**
     * 返回尚未被 Runtime 消费的脚本回合数。
     *
     * @return 剩余回合数
     */
    synchronized int remainingTurns() {
        return scripts.size();
    }

    private ModelRequest copyRequest(ModelRequest request) {
        return new ModelRequest(
                request.sessionId(),
                request.runId(),
                request.turnNumber(),
                request.messages(),
                request.toolDefinitions());
    }
}
