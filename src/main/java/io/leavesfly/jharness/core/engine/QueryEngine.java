package io.leavesfly.jharness.core.engine;

import io.leavesfly.jharness.core.plan.ExecutionPlan;
import io.leavesfly.jharness.core.plan.PlanStep;
import io.leavesfly.jharness.integration.api.ApiMessageCompleteEvent;
import io.leavesfly.jharness.integration.api.LlmProvider;
import io.leavesfly.jharness.integration.api.OpenAiApiClient;
import io.leavesfly.jharness.core.engine.model.*;
import io.leavesfly.jharness.core.engine.stream.StreamEvent;
import io.leavesfly.jharness.core.engine.stream.ToolExecutionCompleted;
import io.leavesfly.jharness.core.engine.stream.ToolExecutionStarted;
import io.leavesfly.jharness.tools.EnterPlanModeTool;

import io.leavesfly.jharness.session.permissions.PermissionChecker;
import io.leavesfly.jharness.session.permissions.PermissionDecision;
import io.leavesfly.jharness.tools.ToolRegistry;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 查询引擎
 *
 * Agent 的核心引擎，负责管理消息历史、执行查询循环、
 * 调用 LLM 和工具执行。
 */
public class QueryEngine implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(QueryEngine.class);

    // 统一使用 messageLock 保护所有消息访问，避免 synchronizedList + 独立锁的双重同步死锁风险
    private final List<ConversationMessage> messages = new ArrayList<>();
    private final Object messageLock = new Object();
    private final CostTracker costTracker = new CostTracker();
    private final MessageCompactionService compactionService = new MessageCompactionService();
    private final LlmProvider apiClient;
    private final ToolRegistry toolRegistry;
    private final PermissionChecker permissionChecker;
    private volatile Path cwd;
    private final String systemPrompt;
    private final int maxTurns;
    /** F-P0-3 流式中断标志：submitMessage 开始时置 false，cancel() 置 true，循环每轮检查。 */
    private final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 构造函数（兼容旧签名）。
     */
    public QueryEngine(OpenAiApiClient apiClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker, Path cwd,
                       String systemPrompt, int maxTurns) {
        this((LlmProvider) apiClient, toolRegistry, permissionChecker, cwd, systemPrompt, maxTurns);
    }

    /**
     * 构造函数（F-P0-1：支持任意 LlmProvider 实现）。
     */
    public QueryEngine(LlmProvider apiClient, ToolRegistry toolRegistry,
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
     * 取消当前正在进行的查询（F-P0-3）。
     *
     * 调用后：
     * - 中断当前活跃的 SSE 连接；
     * - 置位 cancelled 标志，使下一轮循环立即退出；
     * - 调用者可在下一次 submitMessage 开始时自动复位。
     */
    public void cancel() {
        cancelled.set(true);
        try {
            apiClient.cancelAllActiveRequests();
        } catch (Exception e) {
            logger.warn("取消当前请求失败", e);
        }
    }

    /** 查询是否处于被取消状态（供 UI/测试使用）。 */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 获取当前工作目录
     */
    public Path getCwd() {
        return cwd;
    }

    /**
     * 设置当前工作目录
     *
     * @param newCwd 新的工作目录路径，必须是已存在的目录
     * @throws IllegalArgumentException 如果路径不存在或不是目录
     */
    public void setCwd(Path newCwd) {
        if (newCwd == null) {
            throw new IllegalArgumentException("工作目录不能为 null");
        }
        Path resolved = newCwd.toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("路径不存在或不是目录: " + resolved);
        }
        this.cwd = resolved;
        logger.info("工作目录已切换到: {}", resolved);
    }

    /**
     * 提交消息并执行查询
     *
     * 使用 messageLock 保护对 messages 的写入，避免与 loadMessages/clear/getMessages 以及
     * runQuery 中的压缩逻辑产生数据竞争。
     *
     * @param prompt        用户提示
     * @param eventConsumer 事件消费者
     * @return 执行完成的 Future
     */
    public CompletableFuture<Void> submitMessage(String prompt, Consumer<StreamEvent> eventConsumer) {
        // F-P0-3：新的提交开始时复位取消标志，避免上次的 cancel 影响新查询
        cancelled.set(false);
        synchronized (messageLock) {
            messages.add(ConversationMessage.userText(prompt));
        }
        return runQuery(eventConsumer);
    }

    /**
     * 加载消息历史
     */
    public void loadMessages(List<ConversationMessage> history) {
        synchronized (messageLock) {
            messages.clear();
            messages.addAll(history);
        }
    }

    /**
     * 获取消息历史（返回副本，保证线程安全）
     */
    public List<ConversationMessage> getMessages() {
        synchronized (messageLock) {
            return new ArrayList<>(messages);
        }
    }

    /**
     * 获取成本追踪器
     */
    public CostTracker getCostTracker() {
        return costTracker;
    }

    /**
     * FP-2：暴露运行时 PermissionChecker 给 TUI / CLI 的命令处理器，
     * 让 /plan、/permissions 等命令能把模式切换同步到真正在工作的实例上。
     */
    public PermissionChecker getPermissionChecker() {
        return permissionChecker;
    }

    /**
     * 获取当前运行时系统提示词
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * 清空消息
     */
    public void clear() {
        synchronized (messageLock) {
            messages.clear();
        }
        costTracker.reset();
    }

    /**
     * 关闭 QueryEngine，释放底层 API 客户端资源（HTTP 连接池、线程池等）
     */
    @Override
    public void close() {
        try {
            apiClient.close();
        } catch (Exception e) {
            logger.warn("关闭 ApiClient 失败", e);
        }
    }

    /**
     * 执行查询循环
     */
    private CompletableFuture<Void> runQuery(Consumer<StreamEvent> eventConsumer) {
        return CompletableFuture.runAsync(() -> {
            try {
                for (int turn = 0; turn < maxTurns; turn++) {
                    logger.debug("执行第 {} 轮查询", turn + 1);

                    // F-P0-3：每轮循环开始检查取消标志
                    if (cancelled.get()) {
                        logger.info("查询被用户取消（第 {} 轮前）", turn + 1);
                        eventConsumer.accept(new io.leavesfly.jharness.core.engine.stream.AssistantTurnComplete(
                                "查询已被用户取消"));
                        return;
                    }

                    // 检查是否需要压缩消息历史
                    synchronized (messageLock) {
                        if (compactionService.needsCompaction(messages)) {
                            logger.info("消息历史过长 ({}条)，执行压缩", messages.size());
                            List<ConversationMessage> compacted = compactionService.compact(messages);
                            messages.clear();
                            messages.addAll(compacted);
                        }
                    }

                    // 调用 API（传递工具定义）- 传入快照避免在 API 调用过程中被并发修改
                    List<ConversationMessage> snapshot;
                    synchronized (messageLock) {
                        snapshot = new ArrayList<>(messages);
                    }
                    ApiMessageCompleteEvent response;
                    try {
                        response = apiClient.streamMessage(
                                snapshot, systemPrompt, toolRegistry.toApiSchema(), eventConsumer).join();
                    } catch (java.util.concurrent.CancellationException ce) {
                        // F-P0-3：SSE 被 cancelAllActiveRequests 关闭时，future 以 CancellationException 完成
                        logger.info("LLM 请求被取消");
                        eventConsumer.accept(new io.leavesfly.jharness.core.engine.stream.AssistantTurnComplete(
                                "查询已被用户取消"));
                        return;
                    } catch (java.util.concurrent.CompletionException ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        if (cause instanceof java.util.concurrent.CancellationException
                                || cancelled.get()) {
                            logger.info("LLM 请求被取消: {}", cause.getMessage());
                            eventConsumer.accept(new io.leavesfly.jharness.core.engine.stream.AssistantTurnComplete(
                                    "查询已被用户取消"));
                            return;
                        }
                        throw ex;
                    }

                    // F-P0-3：拿到响应后再次检查取消标志（避免把"已取消但已返回"的结果继续推进到工具执行）
                    if (cancelled.get()) {
                        logger.info("查询已在收到响应后被取消");
                        eventConsumer.accept(new io.leavesfly.jharness.core.engine.stream.AssistantTurnComplete(
                                "查询已被用户取消"));
                        return;
                    }

                    // 记录使用量（F-P0-5：可能抛 BudgetExceededException）
                    long turnInputTokens = 0L;
                    long turnOutputTokens = 0L;
                    if (response.getUsage() != null) {
                        turnInputTokens = response.getUsage().getInputTokens();
                        turnOutputTokens = response.getUsage().getOutputTokens();
                    }
                    try {
                        costTracker.addUsage(response.getUsage());
                    } catch (BudgetExceededException bex) {
                        logger.warn("预算超限: {}", bex.getMessage());
                        eventConsumer.accept(new io.leavesfly.jharness.core.engine.stream.AssistantTurnComplete(
                                "⚠ " + bex.getMessage()));
                        return;
                    }

                    // 可观测性：每轮推送一次 UsageReport，让 UI 实时展示 token / 成本
                    // 注意：必须在 addUsage 之后采样，才能反映最新累计值
                    try {
                        eventConsumer.accept(new io.leavesfly.jharness.core.engine.stream.UsageReport(
                                turnInputTokens,
                                turnOutputTokens,
                                costTracker.getTotalTokens(),
                                costTracker.getSessionCostUsd().doubleValue(),
                                costTracker.getDailyCostUsd().doubleValue(),
                                costTracker.getDailyBudgetUsd().doubleValue()));
                    } catch (Exception reportErr) {
                        // UsageReport 仅用于可观测性，消费端异常不能影响主循环
                        logger.debug("推送 UsageReport 失败（忽略）: {}", reportErr.getMessage());
                    }

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
                    synchronized (messageLock) {
                        messages.add(assistantMsg);
                    }

                    // F-P1-2：Plan Mode 拦截——如果处于 Plan Mode，将工具调用记录为 PlanStep 而非执行
                    ExecutionPlan activePlan = EnterPlanModeTool.getActivePlan();
                    if (activePlan != null) {
                        List<ToolResultBlock> planResults = interceptToolCallsForPlan(
                                toolUses, activePlan, eventConsumer);
                        ConversationMessage planResultMsg = new ConversationMessage(
                                MessageRole.USER, new ArrayList<>(planResults));
                        synchronized (messageLock) {
                            messages.add(planResultMsg);
                        }
                        continue;
                    }

                    // 执行工具
                    List<ToolResultBlock> results = executeTools(toolUses, eventConsumer);

                    // 添加工具结果消息
                    ConversationMessage resultMsg = new ConversationMessage(
                            MessageRole.USER, new ArrayList<>(results));
                    synchronized (messageLock) {
                        messages.add(resultMsg);
                    }
                }

                // P2-M17：达到最大轮次时通过事件通知上层，避免用户误以为是模型正常结束
                logger.warn("达到最大轮次限制 ({})", maxTurns);
                eventConsumer.accept(new io.leavesfly.jharness.core.engine.stream.AssistantTurnComplete(
                        "达到最大轮次限制 (" + maxTurns + ")，本轮对话已结束"));
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
            ToolResult result;
            try {
                result = executeToolCall(toolUse).join();
            } catch (java.util.concurrent.CancellationException ce) {
                result = ToolResult.error("工具执行被取消");
            } catch (java.util.concurrent.CompletionException ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                logger.error("工具执行异常: {}", toolUse.getName(), cause);
                result = ToolResult.error("工具执行异常: " + cause.getMessage());
            }
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

            // 等待所有工具完成（带超时控制）
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(5, TimeUnit.MINUTES);
            } catch (java.util.concurrent.TimeoutException e) {
                logger.error("工具并行执行超时 (5分钟)，取消所有未完成任务");
                // 取消所有未完成的 Future，防止后续 join() 永久阻塞
                futures.forEach(f -> f.cancel(true));
                // 为超时的工具生成错误结果
                List<ToolResultBlock> timeoutResults = new ArrayList<>();
                for (int i = 0; i < toolUses.size(); i++) {
                    CompletableFuture<ToolResult> future = futures.get(i);
                    ToolResult result = future.isDone()
                            ? future.getNow(ToolResult.error("工具执行超时"))
                            : ToolResult.error("工具执行超时 (5分钟)");
                    timeoutResults.add(result.toBlock(toolUses.get(i).getId()));
                }
                return timeoutResults;
            } catch (java.util.concurrent.ExecutionException e) {
                logger.error("工具并行执行异常", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("工具并行执行被中断");
                futures.forEach(f -> f.cancel(true));
            } catch (java.util.concurrent.CancellationException e) {
                // 当 future 已被外层取消时，allOf 可能直接抛出 CancellationException；
                // 此时继续往下走，让每个 future 各自上报错误结果，不影响模型侧的 tool_result 配对。
                logger.warn("工具并行执行被取消: {}", e.getMessage());
            }

            // 收集结果（此时所有 Future 已完成，join() 不会阻塞）
            List<ToolResultBlock> results = new ArrayList<>();
            for (int i = 0; i < toolUses.size(); i++) {
                ToolUseBlock toolUse = toolUses.get(i);
                CompletableFuture<ToolResult> future = futures.get(i);
                ToolResult result;
                try {
                    result = future.getNow(ToolResult.error("工具执行失败"));
                } catch (java.util.concurrent.CancellationException ce) {
                    result = ToolResult.error("工具执行被取消");
                } catch (Exception ex) {
                    result = ToolResult.error("工具执行异常: " + ex.getMessage());
                }
                results.add(result.toBlock(toolUse.getId()));
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

                // P2-M16：空校验——防御性编程，避免 PermissionChecker 实现变更后返回 null 导致 NPE
                if (decision == null) {
                    logger.error("权限检查器返回 null 决策，默认拒绝: {}", tool.getName());
                    return ToolResult.error("权限检查异常: 决策为空");
                }
                if (!decision.isAllowed()) {
                    if (decision.isRequiresConfirmation()) {
                        // 需要用户确认（当前实现默认允许，留待后续接入交互式确认）
                        logger.debug("工具 {} 需要用户确认，默认允许", tool.getName());
                    } else {
                        return ToolResult.error("权限拒绝: " + decision.getReason());
                    }
                }

                // 执行工具
                // FP-2：注入 PermissionChecker，使 EnterPlanModeTool/ExitPlanModeTool 等能把
                // 模式切换同步到真正在工作的 PermissionChecker 实例，而不仅仅落到 Settings。
                ToolExecutionContext context = new ToolExecutionContext(cwd, null, permissionChecker);
                return tool.execute(input, context).join();

            } catch (Exception e) {
                logger.error("工具执行失败: {}", toolUse.getName(), e);
                String errorDetail = e.getMessage();
                if (e.getCause() != null) {
                    errorDetail += " (原因: " + e.getCause().getMessage() + ")";
                }
                return ToolResult.error("工具执行失败: " + errorDetail);
            }
        });
    }

    /**
     * F-P1-2：Plan Mode 下拦截工具调用，将其记录为 PlanStep 而不是真正执行。
     * 返回的 ToolResultBlock 告知 LLM "已记录到执行计划"，使其可以继续输出后续步骤。
     */
    private List<ToolResultBlock> interceptToolCallsForPlan(
            List<ToolUseBlock> toolUses, ExecutionPlan plan, Consumer<StreamEvent> eventConsumer) {
        List<ToolResultBlock> results = new ArrayList<>();
        for (ToolUseBlock toolUse : toolUses) {
            String description = buildPlanStepDescription(toolUse);
            String details = toolUse.getInput() != null ? toolUse.getInput().toString() : "";
            PlanStep step = new PlanStep(plan.size(), toolUse.getName(), description, details);
            plan.addStep(step);

            String msg = String.format("📋 已记录到执行计划 (步骤 %d): [%s] %s",
                    step.getIndex() + 1, toolUse.getName(), description);
            eventConsumer.accept(new ToolExecutionCompleted(toolUse.getName(), toolUse.getId(), msg, false));

            ToolResultBlock resultBlock = new ToolResultBlock(
                    toolUse.getId(), msg, false);
            results.add(resultBlock);
        }
        logger.info("Plan Mode: 已拦截 {} 个工具调用，计划共 {} 步", toolUses.size(), plan.size());
        return results;
    }

    /**
     * 从工具调用中提取人类可读的描述。
     */
    private String buildPlanStepDescription(ToolUseBlock toolUse) {
        com.fasterxml.jackson.databind.JsonNode input = toolUse.getInput();
        if (input == null) return toolUse.getName();
        StringBuilder desc = new StringBuilder();
        if (input.has("file_path")) {
            desc.append(input.get("file_path").asText());
        } else if (input.has("command")) {
            String cmd = input.get("command").asText();
            desc.append(cmd.length() > 60 ? cmd.substring(0, 60) + "..." : cmd);
        } else if (input.has("path")) {
            desc.append(input.get("path").asText());
        }
        return desc.length() > 0 ? desc.toString() : toolUse.getName();
    }

    /**
     * F-P1-2：执行已批准的 PlanStep 列表。
     * 由 /approve 或 /approve_all 命令触发，按照步骤顺序逐个执行工具。
     *
     * @param plan          执行计划
     * @param eventConsumer 事件消费者，用于向 UI 推送执行进度
     * @return 执行结果摘要
     */
    public String executeApprovedPlanSteps(ExecutionPlan plan, Consumer<StreamEvent> eventConsumer) {
        List<PlanStep> approvedSteps = plan.getApprovedSteps();
        if (approvedSteps.isEmpty()) {
            return "没有待执行的已批准步骤。";
        }

        int successCount = 0;
        int failCount = 0;

        for (PlanStep step : approvedSteps) {
            logger.info("执行计划步骤 {}: [{}] {}", step.getIndex() + 1, step.getToolName(), step.getDescription());
            eventConsumer.accept(new ToolExecutionStarted(step.getToolName(),
                    "plan_step_" + step.getIndex()));

            try {
                // 从 details（JSON）重建 ToolUseBlock
                com.fasterxml.jackson.databind.JsonNode inputNode = OBJECT_MAPPER.readTree(step.getDetails());
                ToolUseBlock syntheticToolUse = new ToolUseBlock(
                        "plan_step_" + step.getIndex(), step.getToolName(), inputNode);
                ToolResult result = executeToolCall(syntheticToolUse).join();

                step.setResult(result.getOutput());
                if (result.isError()) {
                    step.setStatus(PlanStep.StepStatus.FAILED);
                    failCount++;
                } else {
                    step.setStatus(PlanStep.StepStatus.EXECUTED);
                    successCount++;
                }

                eventConsumer.accept(new ToolExecutionCompleted(
                        step.getToolName(), "plan_step_" + step.getIndex(),
                        result.getOutput(), result.isError()));
            } catch (Exception e) {
                logger.error("执行计划步骤 {} 失败", step.getIndex() + 1, e);
                step.setStatus(PlanStep.StepStatus.FAILED);
                step.setResult("执行失败: " + e.getMessage());
                failCount++;
                eventConsumer.accept(new ToolExecutionCompleted(
                        step.getToolName(), "plan_step_" + step.getIndex(),
                        "执行失败: " + e.getMessage(), true));
            }
        }

        return String.format("计划执行完成：%d 成功，%d 失败（共 %d 步）",
                successCount, failCount, approvedSteps.size());
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
