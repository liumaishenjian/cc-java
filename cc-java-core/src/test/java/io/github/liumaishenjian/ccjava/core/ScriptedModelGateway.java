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
 * 按预设 FIFO 脚本返回聚合 Model Turn，并记录每次请求快照的离线 Fake。
 *
 * <p>脚本耗尽后使用 {@link AssertionError} 立即暴露 Runtime 的意外模型请求，
 * 避免测试把多调用一次误判为普通 Provider 故障。</p>
 */
final class ScriptedModelGateway implements ModelGateway {

    private final Deque<ModelTurn> scriptedTurns;
    private final List<ModelRequest> requests = new ArrayList<>();

    private ScriptedModelGateway(List<ModelTurn> scriptedTurns) {
        this.scriptedTurns = new ArrayDeque<>(
                List.copyOf(Objects.requireNonNull(scriptedTurns, "scriptedTurns 不能为空")));
    }

    /**
     * 创建按参数顺序消费的模型脚本。
     *
     * @param turns 已经聚合完成的模型回合
     * @return 确定性 Fake Gateway
     */
    static ScriptedModelGateway of(ModelTurn... turns) {
        Objects.requireNonNull(turns, "turns 不能为空");
        return new ScriptedModelGateway(Arrays.asList(turns));
    }

    @Override
    public ModelTurn complete(ModelRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        requests.add(copyRequest(request));
        if (scriptedTurns.isEmpty()) {
            throw new AssertionError("ScriptedModelGateway 没有剩余 Model Turn");
        }
        return scriptedTurns.removeFirst();
    }

    /**
     * 返回调用时刻捕获的不可变请求快照。
     *
     * @return 按模型调用顺序排列的请求
     */
    List<ModelRequest> requests() {
        return List.copyOf(requests);
    }

    /**
     * 返回尚未被 Runtime 消费的脚本回合数。
     *
     * @return 剩余回合数
     */
    int remainingTurns() {
        return scriptedTurns.size();
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
