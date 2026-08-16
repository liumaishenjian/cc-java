package io.github.liumaishenjian.ccjava.model.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelStreamObserver;
import io.github.liumaishenjian.ccjava.core.StreamingModelGateway;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewContextItem;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewRequest;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewResult;
import io.github.liumaishenjian.ccjava.domain.ModelFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ModelFailureSummary;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import java.util.Optional;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 验证自动审批模型 Adapter 的无 Tool、严格 verdict、取消与失败关闭边界。 */
class ModelGatewayApprovalReviewGatewayTest {

    @Test
    void sendsOnlyBoundedReviewEnvelopeWithoutToolsAndAcceptsExactAllowOnce() {
        AtomicReference<ModelRequest> observed = new AtomicReference<>();
        ModelGatewayApprovalReviewGateway gateway = new ModelGatewayApprovalReviewGateway(
                (StreamingModelGateway) (request, observer, cancellation) -> {
                    observed.set(request);
                    return ModelTurn.text("{\"verdict\":\"ALLOW_ONCE\"}");
                });

        ApprovalReviewResult result = gateway.review(request(), CancellationToken.none());

        assertThat(result.verdict()).contains(ApprovalReviewResult.Verdict.ALLOW_ONCE);
        ModelRequest request = observed.get();
        assertThat(request.toolDefinitions()).isEmpty();
        assertThat(request.messages()).hasSize(2);
        assertThat(((io.github.liumaishenjian.ccjava.domain.UserMessage) request.messages().get(1)).content())
                .contains("cc-java-approval-review-v1")
                .contains("write_file")
                .doesNotContain("/workspace/private.txt")
                .doesNotContain("secret-value");
    }

    @Test
    void rejectsToolCallsAndNonExactTextAsParseFailures() {
        ModelGatewayApprovalReviewGateway toolCallGateway = new ModelGatewayApprovalReviewGateway(
                ignored -> new ModelTurn(
                        new io.github.liumaishenjian.ccjava.domain.AssistantMessage("", List.of(
                                new io.github.liumaishenjian.ccjava.domain.ToolCall("call", "forbidden",
                                        io.github.liumaishenjian.ccjava.domain.JsonObject.empty()))),
                        new io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata(
                                io.github.liumaishenjian.ccjava.domain.ModelFinishReason.TOOL_CALLS,
                                Optional.empty(), Optional.empty())));
        ModelGatewayApprovalReviewGateway proseGateway = new ModelGatewayApprovalReviewGateway(
                ignored -> ModelTurn.text("{\"verdict\":\"ALLOW_ONCE\"}\n"));
        ModelGatewayApprovalReviewGateway surroundingWhitespaceGateway = new ModelGatewayApprovalReviewGateway(
                ignored -> ModelTurn.text(" {\"verdict\":\"ALLOW_ONCE\"} "));

        assertThat(toolCallGateway.review(request(), CancellationToken.none()).failure())
                .contains(ApprovalReviewResult.FailureKind.PARSE);
        assertThat(proseGateway.review(request(), CancellationToken.none()).failure())
                .contains(ApprovalReviewResult.FailureKind.PARSE);
        assertThat(surroundingWhitespaceGateway.review(request(), CancellationToken.none()).failure())
                .contains(ApprovalReviewResult.FailureKind.PARSE);
    }

    @Test
    void mapsProviderAndCancellationFailuresWithoutExposingCause() {
        String secret = "provider-secret-body";
        ModelGatewayApprovalReviewGateway providerGateway = new ModelGatewayApprovalReviewGateway(
                ignored -> { throw new ModelGatewayException("provider request failed", new IllegalStateException(secret)); });
        ModelGatewayApprovalReviewGateway typedCancellationGateway = new ModelGatewayApprovalReviewGateway(
                ignored -> { throw new ModelGatewayException(ModelGatewayException.FailureKind.CANCELLED, "provider aborted"); });
        CancellationSource cancellation = new CancellationSource();
        ModelGatewayApprovalReviewGateway cancelledGateway = new ModelGatewayApprovalReviewGateway(
                (StreamingModelGateway) (request, observer, token) -> {
                    cancellation.cancel();
                    throw new ModelGatewayException(ModelGatewayException.FailureKind.CANCELLED, "cancelled");
                });
        ModelGatewayApprovalReviewGateway timeoutGateway = new ModelGatewayApprovalReviewGateway(
                ignored -> { throw new ModelGatewayException(
                        ModelGatewayException.FailureKind.RETRYABLE,
                        "request timed out",
                        ModelFailureSummary.firstAttempt(ModelFailureCategory.REQUEST_TIMEOUT, Optional.empty(), false)); });
        ModelGatewayApprovalReviewGateway runtimeGateway = new ModelGatewayApprovalReviewGateway(
                ignored -> { throw new IllegalStateException("unexpected adapter state"); });

        ApprovalReviewResult provider = providerGateway.review(request(), CancellationToken.none());
        ApprovalReviewResult typedCancellation = typedCancellationGateway.review(request(), CancellationToken.none());
        ApprovalReviewResult cancelled = cancelledGateway.review(request(), cancellation.token());
        ApprovalReviewResult timeout = timeoutGateway.review(request(), CancellationToken.none());
        ApprovalReviewResult runtime = runtimeGateway.review(request(), CancellationToken.none());

        assertThat(provider.failure()).contains(ApprovalReviewResult.FailureKind.PROVIDER);
        assertThat(provider.toString()).doesNotContain(secret);
        assertThat(typedCancellation.failure()).contains(ApprovalReviewResult.FailureKind.TIMEOUT);
        assertThat(cancelled.failure()).contains(ApprovalReviewResult.FailureKind.CANCELLED);
        assertThat(timeout.failure()).contains(ApprovalReviewResult.FailureKind.TIMEOUT);
        assertThat(runtime.failure()).contains(ApprovalReviewResult.FailureKind.INTERNAL);
    }

    private static ApprovalReviewRequest request() {
        return new ApprovalReviewRequest(
                new SessionId("session-1"), new RunId("run-1"), "call-1", "write_file",
                ToolEffect.WRITE_WORKSPACE, ToolSource.BUILT_IN, true, "请求执行受控 Tool 调用",
                List.of(new ApprovalReviewContextItem(ApprovalReviewContextItem.Role.USER, "用户已提交请求")));
    }
}
