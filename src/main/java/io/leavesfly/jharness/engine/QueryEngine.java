package io.leavesfly.jharness.engine;

import io.leavesfly.jharness.api.OpenAiApiClient;
import io.leavesfly.jharness.api.ApiMessageCompleteEvent;
import io.leavesfly.jharness.engine.model.*;
import io.leavesfly.jharness.engine.stream.*;
import io.leavesfly.jharness.permissions.PermissionChecker;
import io.leavesfly.jharness.permissions.PermissionDecision;
import io.leavesfly.jharness.tools.ToolRegistry;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 查询引擎
 *
 * Agent 的核心引擎，负责管理消息历史、执行查询循环、
 * 调用 LLM 和工具执行。
 */
public class QueryEngine {
    private static final Logger logger = LoggerFactory.getLogger(QueryEngine.class);

    private final List<ConversationMessage> messages = java.util.Collections.synchronizedList(new ArrayList<>());
    private final CostTracker costTracker = new CostTracker();
    private final MessageCompactionService compactionService = new MessageCompactionService();
    private final OpenAiApiClient apiClient;
    private final ToolRegistry toolRegistry;
    private final PermissionChecker permissionChecker;
    private final Path cwd;
    private final String systemPrompt;
    private final int maxTurns;

    public QueryEngine(OpenAiApiClient apiClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker, Path cwd,
                       String systemPrompt, int maxTurns) {
        this.apiClient = apiClient;
        this.toolRegistry = toolRegistry;
        this.permissionChecker = permissionChecker;
        this.cwd = cwd;
        this.systemPrompt = systemPrompt;
        this.maxTurns = maxTurns;
    }

    /**
     * 提交消息并执行查询
     *
     * @param prompt        用户提示
     * @param eventConsumer 事件消费者
     * @return 执行完成的 Future
     */
    public CompletableFuture<Void> submitMessage(String prompt, Consumer<StreamEvent> eventConsumer) {
        // 添加用户消息
        messages.add(ConversationMessage.userText(prompt));

        // 执行查询循环
        return runQuery(eventConsumer);
    }

    /**
     * 加载消息历史
     */
    public void loadMessages(List<ConversationMessage> history) {
        messages.clear();
        messages.addAll(history);
    }

    /**
     * 获取消息历史
     */
    public List<ConversationMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    /**
     * 获取成本追踪器
     */
    public CostTracker getCostTracker() {
        return costTracker;
    }

    /**
     * 清空消息
     */
    public void clear() {
        messages.clear();
        costTracker.reset();
    }

    /**
     * 执行查询循环
     */
    private CompletableFuture<Void> runQuery(Consumer<StreamEvent> eventConsumer) {
        return CompletableFuture.runAsync(() -> {
            try {
                for (int turn = 0; turn < maxTurns; turn++) {
                    logger.debug("执行第 {} 轮查询", turn + 1);

                    // 检查是否需要压缩消息历史
                    if (compactionService.needsCompaction(messages)) {
                        logger.info("消息历史过长 ({}条)，执行压缩", messages.size());
                        List<ConversationMessage> compacted = compactionService.compact(messages);
                        messages.clear();
                        messages.addAll(compacted);
                    }

                    // 调用 API（传递工具定义）
                    ApiMessageCompleteEvent response = apiClient.streamMessage(
                            messages, systemPrompt, toolRegistry.toApiSchema(), eventConsumer).join();

                    // 记录使用量
                    costTracker.addUsage(response.getUsage());

                    // 检查是否有工具调用
                    if (!response.hasToolUses()) {
                        // 没有工具调用，循环结束
                        logger.debug("LLM 回复不包含工具调用，查询结束");
                        return;
                    }

                    // 执行工具调用
                    List<ToolUseBlock> toolUses = response.getToolUses();
                    logger.debug("检测到 {} 个工具调用", toolUses.size());

                    // 添加助手消息（TextBlock 必须在 ToolUseBlock 之前，符合 Anthropic API 规范）
                    List<ContentBlock> assistantContent = new ArrayList<>();
                    if (response.getTextContent() != null && !response.getTextContent().isEmpty()) {
                        assistantContent.add(new TextBlock(response.getTextContent()));
                    }
                    assistantContent.addAll(toolUses);
                    ConversationMessage assistantMsg = new ConversationMessage(
                            MessageRole.ASSISTANT, assistantContent);
                    messages.add(assistantMsg);

                    // 执行工具
                    List<ToolResultBlock> results = executeTools(toolUses, eventConsumer);

                    // 添加工具结果消息
                    ConversationMessage resultMsg = new ConversationMessage(
                            MessageRole.USER, new ArrayList<>(results));
                    messages.add(resultMsg);
                }

                logger.warn("达到最大轮次限制 ({})", maxTurns);
            } catch (Exception e) {
                logger.error("查询执行失败", e);
                throw new RuntimeException("查询执行失败", e);
            }
        });
    }

    /**
     * 执行多个工具调用
     */
    private List<ToolResultBlock> executeTools(List<ToolUseBlock> toolUses, Consumer<StreamEvent> eventConsumer) {
        if (toolUses.size() == 1) {
            // 单个工具：顺序执行
            ToolUseBlock toolUse = toolUses.get(0);
            eventConsumer.accept(new ToolExecutionStarted(toolUse.getName(), toolUse.getId()));
            ToolResult result = executeToolCall(toolUse).join();
            eventConsumer.accept(new ToolExecutionCompleted(
                    toolUse.getName(), toolUse.getId(), result.getOutput(), result.isError()));
            return List.of(result.toBlock(toolUse.getId()));
        } else {
            // 多个工具：并行执行
            List<CompletableFuture<ToolResult>> futures = toolUses.stream()
                    .map(toolUse -> {
                        eventConsumer.accept(new ToolExecutionStarted(toolUse.getName(), toolUse.getId()));
                        return executeToolCall(toolUse).thenApply(result -> {
                            eventConsumer.accept(new ToolExecutionCompleted(
                                    toolUse.getName(), toolUse.getId(), result.getOutput(), result.isError()));
                            return result;
                        });
                    })
                    .collect(Collectors.toList());

            // 等待所有工具完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 收集结果
            List<ToolResultBlock> results = new ArrayList<>();
            for (int i = 0; i < toolUses.size(); i++) {
                ToolResult result = futures.get(i).join();
                results.add(result.toBlock(toolUses.get(i).getId()));
            }
            return results;
        }
    }

    /**
     * 执行单个工具调用
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<ToolResult> executeToolCall(ToolUseBlock toolUse) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 查找工具
                BaseTool<Object> tool = (BaseTool<Object>) toolRegistry.get(toolUse.getName());
                if (tool == null) {
                    return ToolResult.error("未知工具: " + toolUse.getName());
                }

                // 权限检查
                Object input = OBJECT_MAPPER.treeToValue(toolUse.getInput(), tool.getInputClass());
                PermissionDecision decision = permissionChecker.evaluate(
                        tool.getName(),
                        tool.isReadOnly(input),
                        extractFilePath(toolUse.getInput()),
                        extractCommand(toolUse.getInput())
                );

                if (!decision.isAllowed()) {
                    if (decision.isRequiresConfirmation()) {
                        // 需要用户确认（简化处理：默认允许）
                        logger.debug("工具 {} 需要用户确认，默认允许", tool.getName());
                    } else {
                        return ToolResult.error("权限拒绝: " + decision.getReason());
                    }
                }

                // 执行工具
                ToolExecutionContext context = new ToolExecutionContext(cwd, null);
                return tool.execute(input, context).join();

            } catch (Exception e) {
                logger.error("工具执行失败: {}", toolUse.getName(), e);
                return ToolResult.error("工具执行失败: " + e.getMessage());
            }
        });
    }

    /**
     * 从工具输入中提取文件路径
     */
    private String extractFilePath(com.fasterxml.jackson.databind.JsonNode input) {
        if (input.has("file_path")) return input.get("file_path").asText();
        if (input.has("path")) return input.get("path").asText();
        return null;
    }

    /**
     * 从工具输入中提取命令
     */
    private String extractCommand(com.fasterxml.jackson.databind.JsonNode input) {
        if (input.has("command")) return input.get("command").asText();
        return null;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
