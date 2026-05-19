package io.leavesfly.jharness.integration.api.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.integration.api.ApiMessageCompleteEvent;
import io.leavesfly.jharness.kernel.engine.model.ToolUseBlock;
import io.leavesfly.jharness.kernel.engine.model.UsageSnapshot;
import io.leavesfly.jharness.kernel.engine.stream.AssistantTextDelta;
import io.leavesfly.jharness.kernel.engine.stream.AssistantTurnComplete;
import io.leavesfly.jharness.kernel.engine.stream.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * OpenAI 标准 SSE 流式响应收集器。
 *
 * 解析事件格式：
 *   data: {"choices":[{"delta":{"content":"..."},"finish_reason":null}]}
 *   data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_xxx","function":{"name":"...","arguments":"..."}}]}}]}
 *   data: [DONE]
 *
 * 边收边把 AssistantTextDelta / AssistantTurnComplete 推给上游 consumer，
 * 完成后通过 {@link #buildCompleteEvent()} 产出包含完整文本 + 工具调用 + usage 的事件。
 */
public final class OpenAiSseStreamReader {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiSseStreamReader.class);

    private final ObjectMapper objectMapper;
    private final Consumer<StreamEvent> eventConsumer;

    private final StringBuilder fullText = new StringBuilder();
    private final List<ToolCallAccumulator> toolCallAccumulators = new ArrayList<>();

    private int promptTokens;
    private int completionTokens;

    public OpenAiSseStreamReader(ObjectMapper objectMapper, Consumer<StreamEvent> eventConsumer) {
        this.objectMapper = objectMapper;
        this.eventConsumer = eventConsumer;
    }

    public void handleEvent(String data) throws Exception {
        if ("[DONE]".equals(data)) {
            return;
        }

        JsonNode json = objectMapper.readTree(data);

        JsonNode usageNode = json.path("usage");
        if (!usageNode.isMissingNode()) {
            promptTokens = usageNode.path("prompt_tokens").asInt(0);
            completionTokens = usageNode.path("completion_tokens").asInt(0);
        }

        JsonNode errorNode = json.path("error");
        if (!errorNode.isMissingNode() && errorNode.isObject()) {
            String errorMessage = errorNode.path("message").asText("未知服务端错误");
            String errorType = errorNode.path("type").asText("unknown");
            logger.error("收到 SSE 错误事件: type={}, message={}", errorType, errorMessage);
            throw new RuntimeException("API 错误 (" + errorType + "): " + errorMessage);
        }

        JsonNode choices = json.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return;
        }

        JsonNode firstChoice = choices.get(0);
        JsonNode delta = firstChoice.path("delta");
        String finishReason = firstChoice.path("finish_reason").asText(null);

        String contentDelta = delta.path("content").asText(null);
        if (contentDelta != null) {
            fullText.append(contentDelta);
            eventConsumer.accept(new AssistantTextDelta(contentDelta));
        }

        JsonNode toolCallsNode = delta.path("tool_calls");
        if (toolCallsNode.isArray()) {
            for (JsonNode toolCallDelta : toolCallsNode) {
                int index = toolCallDelta.path("index").asInt(0);
                handleToolCallDelta(index, toolCallDelta);
            }
        }

        if ("stop".equals(finishReason) || "tool_calls".equals(finishReason)) {
            eventConsumer.accept(new AssistantTurnComplete("消息完成"));
        }
    }

    private void handleToolCallDelta(int index, JsonNode toolCallDelta) {
        while (toolCallAccumulators.size() <= index) {
            toolCallAccumulators.add(new ToolCallAccumulator());
        }
        ToolCallAccumulator accumulator = toolCallAccumulators.get(index);

        String callId = toolCallDelta.path("id").asText(null);
        if (callId != null) {
            accumulator.id = callId;
        }

        JsonNode functionNode = toolCallDelta.path("function");
        if (!functionNode.isMissingNode()) {
            String name = functionNode.path("name").asText(null);
            if (name != null) {
                accumulator.name = name;
                logger.debug("开始收集工具调用: name={}, id={}", name, accumulator.id);
            }
            String argumentsDelta = functionNode.path("arguments").asText(null);
            if (argumentsDelta != null) {
                accumulator.argumentsBuilder.append(argumentsDelta);
            }
        }
    }

    /** 流结束时调用，用于做收尾（当前实现不需要额外动作）。 */
    public void finalizeCollection() {
        // no-op
    }

    public ApiMessageCompleteEvent buildCompleteEvent() {
        List<ToolUseBlock> toolUses = buildToolUses();
        UsageSnapshot usage = new UsageSnapshot(promptTokens, completionTokens, 0, 0);
        return new ApiMessageCompleteEvent(fullText.toString(), toolUses, usage);
    }

    private List<ToolUseBlock> buildToolUses() {
        List<ToolUseBlock> toolUses = new ArrayList<>();
        for (ToolCallAccumulator accumulator : toolCallAccumulators) {
            if (accumulator.id != null && accumulator.name != null) {
                try {
                    String argsStr = accumulator.argumentsBuilder.toString();
                    JsonNode inputNode = argsStr.isEmpty()
                            ? objectMapper.createObjectNode()
                            : objectMapper.readTree(argsStr);
                    toolUses.add(new ToolUseBlock(accumulator.id, accumulator.name, inputNode));
                    logger.debug("收集到工具调用: name={}, id={}", accumulator.name, accumulator.id);
                } catch (Exception e) {
                    logger.error("解析工具参数 JSON 失败: {}", accumulator.argumentsBuilder, e);
                }
            }
        }
        return toolUses;
    }

    /** 工具调用增量累加器：在流式响应中逐步拼接 tool_call 的各个部分。 */
    private static final class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder argumentsBuilder = new StringBuilder();
    }
}
