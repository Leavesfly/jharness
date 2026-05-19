package io.leavesfly.jharness.kernel.spi;

import io.leavesfly.jharness.integration.api.ApiMessageCompleteEvent;
import io.leavesfly.jharness.kernel.engine.model.ConversationMessage;
import io.leavesfly.jharness.kernel.engine.stream.StreamEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * LLM 网关 SPI（B 路线 P0-3 引入）。
 *
 * <p>把内核（{@code kernel.engine.QueryEngine}）和编排（{@code tools.builtin.agent.AgentTool}）
 * 对 LLM 通信层的依赖统一收敛到这个**最小接口**，消除「kernel → integration.api」的反向依赖。
 *
 * <p>这是从原 {@code integration.api.LlmProvider} 抽出的 SPI 视图：方法签名与之 1:1 对齐，
 * 既保留多 Provider（OpenAI / Anthropic / Gemini …）平行扩展能力，又让 kernel 层只看接口、
 * 不看具体实现包。{@code integration.api.LlmProvider} 现在 {@code extends LlmGateway}，
 * 既有实现类无需任何改动。
 *
 * <p>注意：{@link ApiMessageCompleteEvent} 当前位于 {@code integration.api} 包，作为
 * 契约值对象保留——后续若想彻底切干净 {@code kernel.spi → integration} 的引用，
 * 可以再把它移到 {@code kernel.engine.model}（属于风格清理，不影响循环治理）。
 */
public interface LlmGateway extends AutoCloseable {

    CompletableFuture<ApiMessageCompleteEvent> streamMessage(
            List<ConversationMessage> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            Consumer<StreamEvent> eventConsumer);

    void cancelAllActiveRequests();

    String getProviderName();

    String getModelName();

    @Override
    default void close() {
        // 默认空实现，简单 Provider 可不重写
    }
}
