package io.github.liumaishenjian.ccjava.model.springai.provider;

/** Spring AI 工厂支持的封闭 Provider 协议种类。 */
public enum ProviderGatewayKind {
    /** 自定义 OpenAI-compatible Chat Completions。 */ OPENAI_COMPATIBLE,
    /** Anthropic 官方 Messages API。 */ ANTHROPIC,
    /** OpenRouter 官方 OpenAI-compatible API。 */ OPENROUTER
}
