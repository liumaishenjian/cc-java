package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import java.util.ArrayList;
import java.util.List;

/**
 * 按发布顺序保存事件信封的测试观察者。
 *
 * <p>返回值始终是不可变快照，测试不能通过修改观察结果反向污染 Runtime 的事件事实。</p>
 */
final class RecordingAgentEventSink implements AgentEventSink {

    private final List<AgentEventEnvelope> envelopes = new ArrayList<>();

    @Override
    public synchronized void publish(AgentEventEnvelope envelope) {
        envelopes.add(envelope);
    }

    /**
     * 返回当前已观察事件的不可变快照。
     *
     * @return 按真实发布顺序排列的事件信封
     */
    synchronized List<AgentEventEnvelope> envelopes() {
        return List.copyOf(envelopes);
    }
}
