package io.leavesfly.jharness.integration.api;

import io.leavesfly.jharness.core.engine.model.ConversationMessage;
import io.leavesfly.jharness.core.engine.stream.StreamEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * LLM Provider 抽象接口（F-P0-1）。
 *
 * 目标：将"与 LLM 的交互协议"从上层 QueryEngine 解耦，使得后续可以平行扩展
 * Anthropic / Gemini / Bedrock / Ollama 等不同供应商而无需改动 QueryEngine。
 *
 * 设计要点：
 * - 只暴露流式请求一个核心方法 {@link #streamMessage}，所有 Provider 内部差异
 *   （工具协议、推理字段、缓存策略）在实现类中自行消化；
 * - {@link #cancelAllActiveRequests()} 用于支持用户主动取消（Ctrl+C 中断 / UI 停止按钮）；
 * - 实现 {@link AutoCloseable} 以便统一释放底层连接池/线程池资源；
 * - {@link #getProviderName()} / {@link #getModelName()} 用于日志和成本统计。
 */
public interface LlmProvider extends AutoCloseable {

    /**
     * 向 LLM 流式发送对话并接收响应。
     *
     * @param messages      完整的会话历史（调用方保证不可变快照）
     * @param systemPrompt  system prompt，可为 null
     * @param tools         工具 schema 列表，可为 null 或空
     * @param eventConsumer 流式事件消费者（文本增量、工具启动等）
     * @return 一个 Future，完成时附带完整响应（文本 + 工具调用 + usage）
     */
    CompletableFuture<ApiMessageCompleteEvent> streamMessage(
            List<ConversationMessage> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            Consumer<StreamEvent> eventConsumer);

    /**
     * 取消当前所有活跃的流式请求（F-P0-3 流式中断依赖此方法）。
     *
     * 实现应保证：
     * - 幂等：多次调用不会抛异常；
     * - 对已完成的请求无副作用；
     * - 调用后 {@link #streamMessage} 仍可发起新请求。
     */
    void cancelAllActiveRequests();

    /** 供应商名称，如 "openai" / "anthropic" / "gemini"，用于日志/统计。 */
    String getProviderName();

    /** 当前使用的模型名称，用于成本/日志。 */
    String getModelName();

    /**
     * 关闭底层资源（HTTP 连接池、线程池等）。
     * 默认实现为空，便于简单 Provider 直接使用。
     */
    @Override
    default void close() {
        // 默认无资源可释放
    }
}
