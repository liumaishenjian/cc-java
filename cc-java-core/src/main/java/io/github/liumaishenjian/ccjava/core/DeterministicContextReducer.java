package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ContextProjection;
import io.github.liumaishenjian.ccjava.domain.ContextReduction;
import io.github.liumaishenjian.ccjava.domain.ContextReductionOutcome;
import io.github.liumaishenjian.ccjava.domain.ContextReductionReason;
import io.github.liumaishenjian.ccjava.domain.ContextReductionStatus;
import io.github.liumaishenjian.ccjava.domain.ContextReductionStrategy;
import io.github.liumaishenjian.ccjava.domain.ContextUsage;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.ToolResultMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 实现 G3-A C1 大载荷缩减与 C2 旧 Tool Result 清理的确定性 Reducer。
 *
 * <p>该实现每提交一步都重新估算，并在预算满足时立即停止。C1 只处理完整、非活动批次中
 * 的单个高体积 Tool Result；C2 再按最旧批次顺序清理正文。两者都保留 Assistant 批次、
 * Call ID、Tool 名称、结果状态与原有顺序，且从不修改输入列表或 Canonical Transcript。</p>
 *
 * <p>C3/C4 不在本类伪实现：若 C1/C2 无法安全满足预算，返回
 * {@link ContextReductionStatus#CONTEXT_LIMIT_REACHED}。</p>
 *
 * @since 0.7.0
 */
public final class DeterministicContextReducer
        implements ContextReducer, ContextProjectionPlanner {

    private static final String C1_PLACEHOLDER =
            "[C1 已缩减正文；状态与截断信息保留]";
    private static final String C2_PLACEHOLDER =
            "[C2 已清理旧正文；状态与截断信息保留]";

    private final ContextTokenEstimator estimator;
    private final long largePayloadTokenThreshold;

    /**
     * 创建 Reducer。
     *
     * @param estimator 每步之后重新计算 Usage 的确定性 Estimator
     * @param largePayloadTokenThreshold C1 单条 Tool Result 正文触发阈值
     */
    public DeterministicContextReducer(
            ContextTokenEstimator estimator,
            long largePayloadTokenThreshold) {
        this.estimator = Objects.requireNonNull(estimator, "estimator 不能为空");
        if (largePayloadTokenThreshold <= 0) {
            throw new IllegalArgumentException("largePayloadTokenThreshold 必须为正数");
        }
        this.largePayloadTokenThreshold = largePayloadTokenThreshold;
    }

    @Override
    public ContextReductionOutcome plan(
            ProjectionRequest request,
            CancellationToken cancellationToken) {
        return reduce(request, cancellationToken);
    }

    @Override
    public ContextReductionOutcome reduce(
            ProjectionRequest request,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");

        List<AgentMessage> original = request.canonicalMessages();
        ContextUsage initialUsage = estimator.estimate(original, request.capacity());
        if (cancellationToken.isCancellationRequested()) {
            return terminal(
                    request,
                    original,
                    initialUsage,
                    ContextReductionStatus.CANCELLED,
                    ContextReductionReason.CANCELLED);
        }

        ProtocolScan scan = scanProtocol(original, request.protectedMessageCount());
        if (scan.invalidProtocol()) {
            return terminal(
                    request,
                    original,
                    initialUsage,
                    ContextReductionStatus.CONTEXT_LIMIT_REACHED,
                    ContextReductionReason.INVALID_TOOL_PROTOCOL);
        }
        if (initialUsage.fits()) {
            return unchanged(request, initialUsage);
        }
        List<AgentMessage> candidate = new ArrayList<>(original);
        List<ContextReduction> reductions = new ArrayList<>();
        ContextUsage currentUsage = initialUsage;

        for (int resultIndex : scan.completedResultIndexes()) {
            ToolResultMessage resultMessage = (ToolResultMessage) candidate.get(resultIndex);
            if (contentTokens(resultMessage) < largePayloadTokenThreshold
                    || isReductionPlaceholder(resultMessage.result().content())) {
                continue;
            }
            Step step = replaceResult(
                    candidate,
                    resultIndex,
                    C1_PLACEHOLDER,
                    ContextReductionStrategy.LARGE_PAYLOAD_REDUCTION,
                    currentUsage,
                    request);
            if (step == null) {
                continue;
            }
            candidate = step.messages();
            currentUsage = step.usage();
            reductions.add(step.reduction());
            if (cancellationToken.isCancellationRequested()) {
                return terminal(
                        request,
                        original,
                        initialUsage,
                        ContextReductionStatus.CANCELLED,
                        ContextReductionReason.CANCELLED);
            }
            if (currentUsage.fits()) {
                return reduced(request, candidate, initialUsage, currentUsage, reductions);
            }
        }

        for (CompletedBatch batch : scan.completedBatches()) {
            List<Integer> remaining = new ArrayList<>();
            for (int resultIndex : batch.resultIndexes()) {
                ToolResultMessage result = (ToolResultMessage) candidate.get(resultIndex);
                if (!isReductionPlaceholder(result.result().content())) {
                    remaining.add(resultIndex);
                }
            }
            if (remaining.isEmpty()) {
                continue;
            }
            long before = currentUsage.totalTokens();
            List<AgentMessage> next = new ArrayList<>(candidate);
            for (int resultIndex : remaining) {
                ToolResultMessage result = (ToolResultMessage) next.get(resultIndex);
                next.set(resultIndex, placeholder(result, C2_PLACEHOLDER));
            }
            ContextUsage nextUsage = estimator.estimate(next, request.capacity());
            if (nextUsage.totalTokens() >= before) {
                continue;
            }
            candidate = next;
            currentUsage = nextUsage;
            reductions.add(new ContextReduction(
                    ContextReductionStrategy.OLD_TOOL_RESULT_CLEANUP,
                    before,
                    currentUsage.totalTokens(),
                    remaining.size()));
            if (cancellationToken.isCancellationRequested()) {
                return terminal(
                        request,
                        original,
                        initialUsage,
                        ContextReductionStatus.CANCELLED,
                        ContextReductionReason.CANCELLED);
            }
            if (currentUsage.fits()) {
                return reduced(request, candidate, initialUsage, currentUsage, reductions);
            }
        }

        ContextReductionReason reason = scan.hasProtectedBatch()
                ? ContextReductionReason.ACTIVE_OR_PROTECTED_TOOL_BATCH
                : ContextReductionReason.NO_SAFE_REDUCTION_AVAILABLE;
        return terminal(
                request,
                original,
                initialUsage,
                ContextReductionStatus.CONTEXT_LIMIT_REACHED,
                reason);
    }

    private ContextReductionOutcome unchanged(
            ProjectionRequest request,
            ContextUsage usage) {
        ContextProjection projection = new ContextProjection(
                request.canonicalMessages(), usage, List.of(), request.sourceRevision());
        return new ContextReductionOutcome(
                ContextReductionStatus.UNCHANGED,
                projection,
                usage,
                usage,
                ContextReductionReason.WITHIN_CAPACITY);
    }

    private ContextReductionOutcome reduced(
            ProjectionRequest request,
            List<AgentMessage> messages,
            ContextUsage initialUsage,
            ContextUsage finalUsage,
            List<ContextReduction> reductions) {
        ContextProjection projection = new ContextProjection(
                messages, finalUsage, reductions, request.sourceRevision());
        ContextReductionReason reason;
        if (reductions.size() > 1) {
            reason = ContextReductionReason.MULTIPLE_REDUCTIONS_APPLIED;
        } else if (reductions.getFirst().strategy()
                == ContextReductionStrategy.LARGE_PAYLOAD_REDUCTION) {
            reason = ContextReductionReason.LARGE_PAYLOAD_REDUCED;
        } else {
            reason = ContextReductionReason.OLD_TOOL_RESULT_CLEANED;
        }
        return new ContextReductionOutcome(
                ContextReductionStatus.REDUCED,
                projection,
                initialUsage,
                finalUsage,
                reason);
    }

    private ContextReductionOutcome terminal(
            ProjectionRequest request,
            List<AgentMessage> original,
            ContextUsage usage,
            ContextReductionStatus status,
            ContextReductionReason reason) {
        ContextProjection projection = new ContextProjection(
                original, usage, List.of(), request.sourceRevision());
        return new ContextReductionOutcome(status, projection, usage, usage, reason);
    }

    private Step replaceResult(
            List<AgentMessage> messages,
            int resultIndex,
            String replacement,
            ContextReductionStrategy strategy,
            ContextUsage beforeUsage,
            ProjectionRequest request) {
        List<AgentMessage> next = new ArrayList<>(messages);
        next.set(resultIndex, placeholder((ToolResultMessage) next.get(resultIndex), replacement));
        ContextUsage afterUsage = estimator.estimate(next, request.capacity());
        if (afterUsage.totalTokens() >= beforeUsage.totalTokens()) {
            return null;
        }
        return new Step(
                next,
                afterUsage,
                new ContextReduction(
                        strategy,
                        beforeUsage.totalTokens(),
                        afterUsage.totalTokens(),
                        1));
    }

    private ToolResultMessage placeholder(ToolResultMessage message, String content) {
        ToolResult original = message.result();
        ToolResultMetadata metadata = original.metadata();
        int characters = content.codePointCount(0, content.length());
        long knownOriginal = metadata.knownOriginalCharacters().isPresent()
                ? Math.max(metadata.knownOriginalCharacters().getAsLong(), original.content().codePointCount(0, original.content().length()))
                : original.content().codePointCount(0, original.content().length());
        ToolResultMetadata projectedMetadata = new ToolResultMetadata(
                metadata.truncated(),
                metadata.truncationReason(),
                characters,
                OptionalLong.of(Math.max(knownOriginal, characters)),
                metadata.returnedItems(),
                metadata.filteredItems(),
                metadata.continuation());
        ToolResult projected = new ToolResult(
                original.callId(),
                original.toolName(),
                original.status(),
                content,
                original.error(),
                projectedMetadata);
        return new ToolResultMessage(projected);
    }

    private long contentTokens(ToolResultMessage message) {
        String content = message.result().content();
        return content.codePointCount(0, content.length());
    }

    private boolean isReductionPlaceholder(String content) {
        return C1_PLACEHOLDER.equals(content) || C2_PLACEHOLDER.equals(content);
    }

    private ProtocolScan scanProtocol(
            List<AgentMessage> messages,
            int protectedMessageCount) {
        int protectedStart = messages.size() - protectedMessageCount;
        Map<String, Integer> callBatchById = new HashMap<>();
        List<MutableBatch> batches = new ArrayList<>();
        boolean invalid = false;
        int batchNumber = 0;
        MutableBatch awaitingResults = null;
        for (int index = 0; index < messages.size(); index++) {
            AgentMessage message = messages.get(index);
            if (message instanceof AssistantMessage assistant && !assistant.toolCalls().isEmpty()) {
                if (awaitingResults != null
                        && awaitingResults.resultIds().size() != awaitingResults.callIds().size()) {
                    invalid = true;
                }
                MutableBatch batch = new MutableBatch(batchNumber++, index, assistant.toolCalls());
                batches.add(batch);
                awaitingResults = batch;
                for (ToolCall call : assistant.toolCalls()) {
                    if (!batch.callIds().add(call.id())
                            || callBatchById.putIfAbsent(call.id(), batch.number()) != null) {
                        invalid = true;
                    }
                }
            } else if (message instanceof ToolResultMessage result) {
                Integer target = callBatchById.get(result.result().callId());
                if (target == null || awaitingResults == null || target != awaitingResults.number()) {
                    invalid = true;
                    continue;
                }
                List<ToolCall> calls = awaitingResults.calls();
                int resultPosition = awaitingResults.resultIds().size();
                if (resultPosition >= calls.size()
                        || !calls.get(resultPosition).id().equals(result.result().callId())
                        || !calls.get(resultPosition).name().equals(result.result().toolName())
                        || !awaitingResults.resultIds().add(result.result().callId())) {
                    invalid = true;
                }
                awaitingResults.resultIndexes().add(index);
                if (awaitingResults.resultIds().size() == awaitingResults.callIds().size()) {
                    awaitingResults = null;
                }
            } else if (awaitingResults != null
                    && awaitingResults.resultIds().size() != awaitingResults.callIds().size()) {
                invalid = true;
            }
        }

        List<CompletedBatch> completed = new ArrayList<>();
        boolean protectedBatchFound = false;
        for (MutableBatch batch : batches) {
            boolean complete = batch.resultIds().equals(batch.callIds())
                    && batch.resultIndexes().size() == batch.calls().size();
            if (!complete) {
                invalid = true;
                continue;
            }
            boolean protectedBatch = batch.assistantIndex() >= protectedStart
                    || batch.resultIndexes().stream().anyMatch(index -> index >= protectedStart);
            if (protectedBatch) {
                protectedBatchFound = true;
            } else {
                completed.add(new CompletedBatch(
                        batch.assistantIndex(), List.copyOf(batch.resultIndexes())));
            }
        }
        List<Integer> completedResults = completed.stream()
                .flatMap(batch -> batch.resultIndexes().stream())
                .toList();
        return new ProtocolScan(completed, completedResults, invalid, protectedBatchFound);
    }

    private record Step(
            List<AgentMessage> messages,
            ContextUsage usage,
            ContextReduction reduction) {
    }

    private record CompletedBatch(int assistantIndex, List<Integer> resultIndexes) {
    }

    private record ProtocolScan(
            List<CompletedBatch> completedBatches,
            List<Integer> completedResultIndexes,
            boolean invalidProtocol,
            boolean hasProtectedBatch) {
    }

    private static final class MutableBatch {
        private final int number;
        private final int assistantIndex;
        private final List<ToolCall> calls;
        private final Set<String> callIds = new HashSet<>();
        private final Set<String> resultIds = new HashSet<>();
        private final List<Integer> resultIndexes = new ArrayList<>();

        private MutableBatch(int number, int assistantIndex, List<ToolCall> calls) {
            this.number = number;
            this.assistantIndex = assistantIndex;
            this.calls = List.copyOf(calls);
        }

        private int number() {
            return number;
        }

        private int assistantIndex() {
            return assistantIndex;
        }

        private List<ToolCall> calls() {
            return calls;
        }

        private Set<String> callIds() {
            return callIds;
        }

        private Set<String> resultIds() {
            return resultIds;
        }

        private List<Integer> resultIndexes() {
            return resultIndexes;
        }
    }
}
