package io.github.liumaishenjian.ccjava.model.springai;

import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 把 Spring AI 的流式 {@link ChatResponse} 聚合为项目 {@link ModelTurn}。
 *
 * <p>Accumulator 保持文本 Delta 与 Tool Call 首次出现顺序。相同 Call ID 的
 * 参数既可按增量到达，也可由 Provider 发送累计快照；最终 JSON 只在流明确
 * 完成后解析。缺少结束原因、Call ID、名称或完整 JSON 时拒绝整个回合，避免
 * 把不完整 Tool Call 送入 Pipeline。Provider 明确报告长度上限或内容安全终止时，
 * 该安全边界优先于已聚合的 Tool Call，Runtime 因而不会执行被截断或被过滤回合中的
 * 操作意图。</p>
 *
 * @since 0.1.0
 */
final class SpringAiStreamAccumulator {

    /** 单个模型回合允许保留的文本、终止原因与 Tool Call 字段 UTF-8 字节总量。 */
    static final long DEFAULT_MAX_AGGREGATED_UTF8_BYTES = 8L * 1024L * 1024L;

    /** 单个模型回合允许聚合的不同 Tool Call 数量。 */
    static final int DEFAULT_MAX_TOOL_CALLS = 128;

    private final StringBuilder text = new StringBuilder();
    private final LinkedHashMap<String, MutableToolCall> toolCalls = new LinkedHashMap<>();
    private final long maxAggregatedUtf8Bytes;
    private final int maxToolCalls;
    private long retainedUtf8Bytes;
    private String rawFinishReason;
    private ModelUsage usage;
    private boolean sawResponse;
    private boolean sawObservableContent;

    /**
     * 使用生产安全上限创建聚合器。
     */
    SpringAiStreamAccumulator() {
        this(DEFAULT_MAX_AGGREGATED_UTF8_BYTES, DEFAULT_MAX_TOOL_CALLS);
    }

    /**
     * 使用显式上限创建聚合器，供 Adapter 测试和受控边缘装配使用。
     *
     * @param maxAggregatedUtf8Bytes 文本、终止原因、Tool ID、名称和参数保留值的
     *                               UTF-8 字节总上限
     * @param maxToolCalls 不同 Tool Call ID 的数量上限
     */
    SpringAiStreamAccumulator(long maxAggregatedUtf8Bytes, int maxToolCalls) {
        if (maxAggregatedUtf8Bytes <= 0) {
            throw new IllegalArgumentException("maxAggregatedUtf8Bytes 必须大于 0");
        }
        if (maxToolCalls <= 0) {
            throw new IllegalArgumentException("maxToolCalls 必须大于 0");
        }
        this.maxAggregatedUtf8Bytes = maxAggregatedUtf8Bytes;
        this.maxToolCalls = maxToolCalls;
    }

    /**
     * 接收一个 Provider 顺序中的响应 Chunk。
     *
     * @param response Spring AI 响应 Chunk
     * @return 本 Chunk 的文本 Delta；没有文本时为空
     */
    String accept(ChatResponse response) {
        Objects.requireNonNull(response, "response 不能为空");
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            throw invalidResponse("Provider Chunk 缺少 Generation");
        }
        sawResponse = true;
        String delta = Objects.requireNonNullElse(generation.getOutput().getText(), "");
        if (!delta.isEmpty()) {
            reserveUtf8Bytes(utf8Length(delta));
            text.append(delta);
            sawObservableContent = true;
        }
        mergeToolCalls(generation.getOutput().getToolCalls());

        ChatGenerationMetadata generationMetadata = generation.getMetadata();
        if (generationMetadata != null
                && generationMetadata.getFinishReason() != null
                && !generationMetadata.getFinishReason().isBlank()) {
            String incomingFinishReason = generationMetadata.getFinishReason();
            if (rawFinishReason == null) {
                replaceRetainedValue(null, incomingFinishReason);
                rawFinishReason = incomingFinishReason;
            } else if (!rawFinishReason.equals(incomingFinishReason)) {
                throw invalidResponse("Provider 流包含相互冲突的 finish reason");
            }
            captureUsage(response.getMetadata() == null
                    ? null
                    : response.getMetadata().getUsage());
        }
        return delta;
    }

    /**
     * 在收到流完成信号后构造项目回合。
     *
     * @return 完整聚合的模型回合
     * @throws ModelAggregationException 流或 Tool Call 不完整时
     */
    ModelTurn finish() {
        if (!sawResponse) {
            throw incompleteResponse("Provider 流未产生任何响应");
        }
        if (rawFinishReason == null) {
            throw incompleteResponse("Provider 流在结束前未报告 finish reason");
        }
        List<io.github.liumaishenjian.ccjava.domain.ToolCall> calls = new ArrayList<>();
        for (MutableToolCall mutable : toolCalls.values()) {
            calls.add(mutable.toDomain());
        }
        ModelFinishReason finishReason = normalizeFinishReason(rawFinishReason, calls);
        return new ModelTurn(
                new AssistantMessage(text.toString(), calls),
                finishReason,
                Optional.ofNullable(usage));
    }

    /**
     * 判断失败前是否已经接收可观察文本或 Tool Call 内容。
     *
     * @return 存在部分响应时为 {@code true}
     */
    boolean hasPartialResponse() {
        return sawObservableContent;
    }

    private void mergeToolCalls(
            List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return;
        }
        sawObservableContent = true;
        Set<String> chunkIds = new HashSet<>();
        int newCallCount = 0;
        for (org.springframework.ai.chat.messages.AssistantMessage.ToolCall call : calls) {
            if (call == null || call.id() == null || call.id().isBlank()) {
                throw invalidResponse("Tool Call 缺少稳定 ID");
            }
            if (!chunkIds.add(call.id())) {
                throw invalidResponse("同一 Provider Chunk 包含重复 Tool Call ID");
            }
            if (!toolCalls.containsKey(call.id())) {
                newCallCount++;
                if (newCallCount > maxToolCalls - toolCalls.size()) {
                    throw responseLimitExceeded("Tool Call 数量超过本地安全上限");
                }
            }
        }
        for (org.springframework.ai.chat.messages.AssistantMessage.ToolCall call : calls) {
            ensureIndividualFieldWithinLimit(call.id());
            ensureIndividualFieldWithinLimit(
                    Objects.requireNonNullElse(call.name(), ""));
            ensureIndividualFieldWithinLimit(
                    Objects.requireNonNullElse(call.arguments(), ""));
            MutableToolCall mutable = toolCalls.get(call.id());
            boolean newCall = mutable == null;
            if (newCall) {
                mutable = new MutableToolCall(call.id());
            }
            MutableToolCall.MergedFields merged =
                    mutable.previewMerge(call.name(), call.arguments());
            long additionalBytes = newCall
                    ? utf8Length(call.id())
                    : 0;
            additionalBytes += utf8Length(merged.name()) - utf8Length(mutable.name());
            additionalBytes += utf8Length(merged.arguments())
                    - utf8Length(mutable.arguments());
            reserveUtf8Bytes(additionalBytes);
            if (newCall) {
                toolCalls.put(call.id(), mutable);
            }
            mutable.apply(merged);
        }
    }

    private void captureUsage(Usage providerUsage) {
        if (providerUsage == null
                || providerUsage instanceof EmptyUsage
                || providerUsage.getPromptTokens() == null
                || providerUsage.getCompletionTokens() == null) {
            return;
        }
        long input = providerUsage.getPromptTokens().longValue();
        long output = providerUsage.getCompletionTokens().longValue();
        Long reportedTotal = providerUsage.getTotalTokens() == null
                ? null
                : providerUsage.getTotalTokens().longValue();
        try {
            long total = reportedTotal == null
                    ? Math.addExact(input, output)
                    : reportedTotal;
            usage = new ModelUsage(input, output, total);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            throw invalidResponse("Provider 返回了无效的 Token Usage");
        }
    }

    private static ModelFinishReason normalizeFinishReason(
            String rawReason,
            List<io.github.liumaishenjian.ccjava.domain.ToolCall> calls) {
        return switch (rawReason.toLowerCase(Locale.ROOT)) {
            case "length", "max_tokens", "max_output_tokens" -> ModelFinishReason.LENGTH;
            case "content_filter", "content-filter", "safety" ->
                    ModelFinishReason.CONTENT_FILTER;
            case "tool_calls", "tool_use" -> {
                if (calls.isEmpty()) {
                    throw incompleteResponse(
                            "Provider finish reason 宣称 Tool Call 终止但未返回任何 Tool Call");
                }
                yield ModelFinishReason.TOOL_CALLS;
            }
            case "stop", "end_turn" -> calls.isEmpty()
                    ? ModelFinishReason.STOP
                    : ModelFinishReason.TOOL_CALLS;
            default -> calls.isEmpty()
                    ? ModelFinishReason.UNKNOWN
                    : ModelFinishReason.TOOL_CALLS;
        };
    }

    private void reserveUtf8Bytes(long additionalBytes) {
        if (additionalBytes < 0) {
            throw new IllegalStateException("保留字节增量不能为负数");
        }
        if (additionalBytes > maxAggregatedUtf8Bytes - retainedUtf8Bytes) {
            throw responseLimitExceeded("模型响应累计 UTF-8 字节超过本地安全上限");
        }
        retainedUtf8Bytes += additionalBytes;
    }

    private void ensureIndividualFieldWithinLimit(String value) {
        if (utf8Length(value) > maxAggregatedUtf8Bytes) {
            throw responseLimitExceeded("单个 Tool Call 字段超过本地安全上限");
        }
    }

    private void replaceRetainedValue(String current, String replacement) {
        long currentBytes = current == null ? 0 : utf8Length(current);
        long replacementBytes = utf8Length(replacement);
        if (replacementBytes >= currentBytes) {
            reserveUtf8Bytes(replacementBytes - currentBytes);
        } else {
            retainedUtf8Bytes -= currentBytes - replacementBytes;
        }
    }

    private static long utf8Length(String value) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7f) {
                bytes++;
            } else if (character <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                // 未配对代理项按三个字节保守计数，不能借畸形 Unicode 绕过上限。
                bytes += 3;
            }
        }
        return bytes;
    }

    private static ModelAggregationException incompleteResponse(String message) {
        return new ModelAggregationException(
                AggregationFailureKind.INCOMPLETE_RESPONSE,
                message);
    }

    private static ModelAggregationException invalidResponse(String message) {
        return new ModelAggregationException(
                AggregationFailureKind.INVALID_RESPONSE,
                message);
    }

    private static ModelAggregationException responseLimitExceeded(String message) {
        return new ModelAggregationException(
                AggregationFailureKind.RESPONSE_LIMIT_EXCEEDED,
                message);
    }

    /**
     * Adapter 内部聚合失败的结构化分类。
     *
     * <p>Gateway 只能根据该值映射公开失败，不得解析诊断文案。</p>
     */
    enum AggregationFailureKind {

        /** Provider 流或声明的 Tool Call 回合未完整结束。 */
        INCOMPLETE_RESPONSE,

        /** Provider 数据违反项目模型协议。 */
        INVALID_RESPONSE,

        /** 响应保留字节或 Tool Call 数超过本地安全上限。 */
        RESPONSE_LIMIT_EXCEEDED
    }

    /**
     * Adapter 内部安全的聚合失败，不携带原始响应正文。
     */
    static final class ModelAggregationException extends RuntimeException {

        private final AggregationFailureKind kind;

        /**
         * 创建带稳定分类的聚合失败。
         *
         * @param kind 失败分类
         * @param message 不包含原始模型内容的安全诊断
         */
        ModelAggregationException(
                AggregationFailureKind kind,
                String message) {
            super(message);
            this.kind = Objects.requireNonNull(kind, "kind 不能为空");
        }

        /**
         * 返回 Gateway 可直接映射的稳定失败分类。
         *
         * @return 聚合失败分类
         */
        AggregationFailureKind kind() {
            return kind;
        }
    }

    private static final class MutableToolCall {

        private final String id;
        private String name = "";
        private String arguments = "";

        private MutableToolCall(String id) {
            this.id = id;
        }

        private MergedFields previewMerge(
                String nameFragment,
                String argumentFragment) {
            return new MergedFields(
                    mergeStableName(name, Objects.requireNonNullElse(nameFragment, "")),
                    mergeFragment(
                            arguments,
                            Objects.requireNonNullElse(argumentFragment, "")));
        }

        private void apply(MergedFields merged) {
            name = merged.name();
            arguments = merged.arguments();
        }

        private String name() {
            return name;
        }

        private String arguments() {
            return arguments;
        }

        private io.github.liumaishenjian.ccjava.domain.ToolCall toDomain() {
            if (name.isBlank()) {
                throw invalidResponse("Tool Call 缺少名称");
            }
            if (arguments.isBlank()) {
                throw invalidResponse("Tool Call 缺少参数 JSON");
            }
            Object decoded;
            try {
                decoded = JsonCodec.read(arguments);
            } catch (RuntimeException exception) {
                throw invalidResponse("Tool Call 参数不是完整 JSON");
            }
            if (!(decoded instanceof Map<?, ?> rawMap)) {
                throw invalidResponse("Tool Call 参数必须是 JSON Object");
            }
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw invalidResponse("Tool Call 参数键必须是字符串");
                }
                values.put(key, entry.getValue());
            }
            try {
                return new io.github.liumaishenjian.ccjava.domain.ToolCall(
                        id,
                        name,
                        new JsonObject(values));
            } catch (IllegalArgumentException exception) {
                throw invalidResponse("Tool Call 参数包含不支持的 JSON 值");
            }
        }

        private static String mergeStableName(String current, String incoming) {
            if (incoming.isEmpty() || incoming.equals(current)) {
                return current;
            }
            if (current.isEmpty()) {
                return incoming;
            }
            if (incoming.startsWith(current)) {
                return incoming;
            }
            if (current.startsWith(incoming)) {
                return current;
            }
            throw invalidResponse("相同 Tool Call ID 的名称发生变化");
        }

        private static String mergeFragment(String current, String incoming) {
            if (incoming.isEmpty() || incoming.equals(current)) {
                return current;
            }
            if (current.isEmpty()) {
                return incoming;
            }
            if (incoming.startsWith(current)) {
                return incoming;
            }
            if (current.startsWith(incoming)) {
                return current;
            }
            return current + incoming;
        }

        /**
         * 单次合并预览；仅在总量校验通过后才写回可变状态。
         *
         * @param name 合并后的稳定 Tool 名称
         * @param arguments 合并后的参数 JSON 文本
         */
        private record MergedFields(String name, String arguments) {
        }
    }
}
