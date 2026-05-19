package io.leavesfly.jharness.integration.api.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jharness.kernel.engine.model.ContentBlock;
import io.leavesfly.jharness.kernel.engine.model.ConversationMessage;
import io.leavesfly.jharness.kernel.engine.model.ImageBlock;
import io.leavesfly.jharness.kernel.engine.model.TextBlock;
import io.leavesfly.jharness.kernel.engine.model.ToolResultBlock;
import io.leavesfly.jharness.kernel.engine.model.ToolUseBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 标准请求体构造器。
 *
 * 负责把内部的 {@link ConversationMessage} 列表、system prompt、工具定义，
 * 序列化为 OpenAI Chat Completions 格式的 JSON 请求体。
 *
 * 关键约束：
 *   - assistant 的 tool_calls 与后续的 tool 结果消息必须保持顺序，模型才能正确关联；
 *   - 仅含纯文本的 user 消息用字符串 content（兼容性最好），含图片才用数组 content。
 */
public final class OpenAiRequestBuilder {

    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxTokens;

    public OpenAiRequestBuilder(ObjectMapper objectMapper, String model, int maxTokens) {
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    public ObjectNode buildRequestBody(List<ConversationMessage> messages,
                                        String systemPrompt,
                                        List<Map<String, Object>> tools) {
        ObjectNode body = objectMapper.createObjectNode();

        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        ObjectNode streamOptions = body.putObject("stream_options");
        streamOptions.put("include_usage", true);

        ArrayNode messagesArray = body.putArray("messages");

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode systemMsg = messagesArray.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
        }

        for (ConversationMessage msg : messages) {
            convertMessage(msg, messagesArray);
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (Map<String, Object> tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", (String) tool.get("name"));
                functionNode.put("description", (String) tool.get("description"));
                Object inputSchema = tool.get("input_schema");
                if (inputSchema != null) {
                    functionNode.set("parameters", objectMapper.valueToTree(inputSchema));
                }
            }
        }
        return body;
    }

    private void convertMessage(ConversationMessage msg, ArrayNode messagesArray) {
        String role = msg.getRole().name().toLowerCase();
        List<ContentBlock> blocks = msg.getContent();

        if ("user".equals(role)) {
            convertUserMessage(blocks, messagesArray);
        } else if ("assistant".equals(role)) {
            convertAssistantMessage(blocks, messagesArray);
        }
    }

    private void convertUserMessage(List<ContentBlock> blocks, ArrayNode messagesArray) {
        List<ContentBlock> contentParts = new ArrayList<>();
        List<ToolResultBlock> toolResults = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof ToolResultBlock toolResult) {
                toolResults.add(toolResult);
            } else if (block instanceof TextBlock || block instanceof ImageBlock) {
                contentParts.add(block);
            }
        }

        // 先发送 tool 结果消息（必须紧跟在 assistant 的 tool_calls 之后）
        for (ToolResultBlock toolResult : toolResults) {
            ObjectNode toolMsg = messagesArray.addObject();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolResult.getToolUseId());
            toolMsg.put("content", toolResult.getContent());
        }

        if (!contentParts.isEmpty()) {
            ObjectNode userMsg = messagesArray.addObject();
            userMsg.put("role", "user");

            boolean hasImage = contentParts.stream().anyMatch(b -> b instanceof ImageBlock);
            if (hasImage) {
                ArrayNode contentArray = userMsg.putArray("content");
                for (ContentBlock part : contentParts) {
                    if (part instanceof TextBlock textBlock) {
                        ObjectNode textNode = contentArray.addObject();
                        textNode.put("type", "text");
                        textNode.put("text", textBlock.getText());
                    } else if (part instanceof ImageBlock imageBlock) {
                        ObjectNode imgNode = contentArray.addObject();
                        imgNode.put("type", "image_url");
                        ObjectNode imgUrlNode = imgNode.putObject("image_url");
                        imgUrlNode.put("url", imageBlock.toImageUrlValue());
                        imgUrlNode.put("detail", imageBlock.getDetail());
                    }
                }
            } else {
                StringBuilder text = new StringBuilder();
                for (ContentBlock part : contentParts) {
                    if (part instanceof TextBlock t) {
                        text.append(t.getText());
                    }
                }
                userMsg.put("content", text.toString());
            }
        }
    }

    private void convertAssistantMessage(List<ContentBlock> blocks, ArrayNode messagesArray) {
        ObjectNode assistantMsg = messagesArray.addObject();
        assistantMsg.put("role", "assistant");

        StringBuilder textContent = new StringBuilder();
        List<ToolUseBlock> toolUses = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                textContent.append(textBlock.getText());
            } else if (block instanceof ToolUseBlock toolUse) {
                toolUses.add(toolUse);
            }
        }

        if (!textContent.isEmpty()) {
            assistantMsg.put("content", textContent.toString());
        } else {
            assistantMsg.putNull("content");
        }

        if (!toolUses.isEmpty()) {
            ArrayNode toolCallsArray = assistantMsg.putArray("tool_calls");
            for (ToolUseBlock toolUse : toolUses) {
                ObjectNode toolCallNode = toolCallsArray.addObject();
                toolCallNode.put("id", toolUse.getId());
                toolCallNode.put("type", "function");
                ObjectNode functionNode = toolCallNode.putObject("function");
                functionNode.put("name", toolUse.getName());
                functionNode.put("arguments", toolUse.getInput().toString());
            }
        }
    }
}
