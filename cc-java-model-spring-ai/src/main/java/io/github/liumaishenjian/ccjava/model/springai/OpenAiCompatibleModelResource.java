package io.github.liumaishenjian.ccjava.model.springai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 持有 OpenAI-compatible Spring AI 模型及其底层同步、异步 HTTP Client。
 *
 * <p>{@link ChatModel} 本身不暴露关闭契约，但 Spring AI 为流式调用创建的异步 Client
 * 可能持有非 daemon I/O 线程。Composition Root 必须关闭本资源，不能只丢弃
 * {@code ChatModel} 引用；关闭是幂等的，并分别尝试释放两个 Client。</p>
 *
 * @since 0.1.0
 */
public final class OpenAiCompatibleModelResource implements AutoCloseable {
    private final ChatModel chatModel;
    private final OpenAIClient syncClient;
    private final OpenAIClientAsync asyncClient;
    private final AtomicBoolean closed = new AtomicBoolean();

    OpenAiCompatibleModelResource(
            ChatModel chatModel,
            OpenAIClient syncClient,
            OpenAIClientAsync asyncClient) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel 不能为空");
        this.syncClient = Objects.requireNonNull(syncClient, "syncClient 不能为空");
        this.asyncClient = Objects.requireNonNull(asyncClient, "asyncClient 不能为空");
    }

    /**
     * 提供由本资源托管的 Spring AI 模型视图。
     *
     * <p>调用者可以使用该视图发起模型请求，但不取得底层同步、异步 HTTP Client
     * 的所有权，也不应单独关闭或长期持有它。完成使用后，应由创建本资源的
     * Composition Root 关闭 {@code OpenAiCompatibleModelResource}。</p>
     *
     * @return 只执行模型协议转换、不拥有关闭权的 Spring AI 视图
     */
    public ChatModel chatModel() {
        return chatModel;
    }

    /**
     * 关闭本资源拥有的底层 HTTP Client；一个 Client 关闭失败不阻止另一个 Client 释放。
     *
     * <p>该操作幂等。首次关闭时若任一 Client 释放失败，将在尝试释放两者后抛出异常；
     * 后续调用不会重复关闭。</p>
     *
     * @throws RuntimeException 底层 Client 关闭失败；若两者均失败，后一个异常作为 suppressed exception 保留
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        try {
            asyncClient.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            syncClient.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
