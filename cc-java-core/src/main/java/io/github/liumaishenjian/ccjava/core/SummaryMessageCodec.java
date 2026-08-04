package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage;
import io.github.liumaishenjian.ccjava.domain.MemoryContextMessage;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * 为 C3/C4 创建稳定消息 ID 和有界文本快照的确定性编码器。
 *
 * <p>编码格式是本项目内部的独立输入协议，不是持久化格式或 Provider Prompt。稳定 ID 由
 * source revision 与消息位置派生；Coordinator 只用它核对候选覆盖，不写回 Session。</p>
 *
 * @since 0.7.0
 */
final class SummaryMessageCodec {

    private SummaryMessageCodec() {
    }

    static List<String> sourceIds(long revision, int fromInclusive, int toExclusive) {
        ArrayList<String> ids = new ArrayList<>(toExclusive - fromInclusive);
        for (int index = fromInclusive; index < toExclusive; index++) {
            ids.add("r" + revision + ":m" + index);
        }
        return List.copyOf(ids);
    }

    static String snapshot(List<AgentMessage> messages, int fromInclusive, int toExclusive) {
        StringBuilder snapshot = new StringBuilder();
        for (int index = fromInclusive; index < toExclusive; index++) {
            if (!snapshot.isEmpty()) {
                snapshot.append('\n');
            }
            snapshot.append("[message-").append(index).append("] ")
                    .append(text(messages.get(index)));
        }
        return snapshot.toString();
    }

    private static String text(AgentMessage message) {
        if (message instanceof SystemMessage system) {
            return "system: " + system.content();
        }
        if (message instanceof UserMessage user) {
            return "user: " + user.content();
        }
        if (message instanceof ContextSummaryMessage summary) {
            return "summary: " + summary.content();
        }
        if (message instanceof MemoryContextMessage memory) {
            StringBuilder text = new StringBuilder("memory ")
                    .append(memory.source()).append(' ')
                    .append(memory.catalogRevision().value());
            memory.items().forEach(item -> text.append("\ntopic ")
                    .append(item.name()).append(' ')
                    .append(item.kind()).append(": ")
                    .append(item.body()));
            return text.toString();
        }
        if (message instanceof AssistantMessage assistant) {
            StringBuilder text = new StringBuilder("assistant: ").append(assistant.text());
            for (ToolCall call : assistant.toolCalls()) {
                text.append("\ncall ").append(call.id()).append(' ').append(call.name());
            }
            return text.toString();
        }
        ToolResultMessage result = (ToolResultMessage) message;
        return "result " + result.result().callId() + ' '
                + result.result().toolName() + ": " + result.result().content();
    }
}
