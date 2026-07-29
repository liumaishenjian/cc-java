package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * 把 Runtime 的 Assistant 文本增量转换为 Print 模式的纯文本 stdout。
 *
 * <p>该 Sink 忽略生命周期和未来 Tool 事件，不输出 ANSI 或诊断。若 Gateway
 * 只返回聚合结果而没有 Delta，{@link #finish(AgentRunResult)} 会回退输出最终文本，
 * 但绝不会把流式文本重复打印。</p>
 *
 * @since 0.1.0
 */
final class PrintEventSink implements AgentEventSink {

    private final PrintWriter output;
    private int characterCount;
    private char lastCharacter;

    PrintEventSink(PrintWriter output) {
        this.output = Objects.requireNonNull(output, "output 不能为空");
    }

    @Override
    public void publish(AgentEventEnvelope envelope) {
        if (envelope.event() instanceof ModelTextDelta delta) {
            write(delta.text());
        }
    }

    void finish(AgentRunResult result) {
        if (characterCount == 0) {
            result.finalText().ifPresent(this::write);
        }
        if (characterCount > 0 && lastCharacter != '\n') {
            output.println();
        }
        output.flush();
    }

    private void write(String text) {
        output.print(text);
        output.flush();
        if (!text.isEmpty()) {
            characterCount += text.length();
            lastCharacter = text.charAt(text.length() - 1);
        }
    }
}
