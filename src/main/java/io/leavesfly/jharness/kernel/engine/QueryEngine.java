package io.leavesfly.jharness.kernel.engine;

import io.leavesfly.jharness.capability.permission.PermissionChecker;
import io.leavesfly.jharness.integration.api.ApiMessageCompleteEvent;
import io.leavesfly.jharness.integration.api.LlmProvider;
import io.leavesfly.jharness.integration.api.OpenAiApiClient;
import io.leavesfly.jharness.kernel.engine.hooks.HookEmitterBridge;
import io.leavesfly.jharness.kernel.engine.model.*;
import io.leavesfly.jharness.kernel.engine.stream.StreamEvent;
import io.leavesfly.jharness.kernel.engine.tools.PlanModeInterceptor;
import io.leavesfly.jharness.kernel.engine.tools.PlanStepRunner;
import io.leavesfly.jharness.kernel.engine.tools.ToolCallDispatcher;
import io.leavesfly.jharness.kernel.plan.ExecutionPlan;
import io.leavesfly.jharness.tools.ToolRegistry;
import io.leavesfly.jharness.tools.builtin.mode.EnterPlanModeTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
    /**
     * 压缩服务可注入：允许外部根据 Settings 注入自定义 token/条数预算，并通过
     * {@link MessageCompactionService#withSystemPromptTokens(int)} 把 systemPrompt 的 token
     * 占用提前扣减，避免超长 CLAUDE.md 把压缩触发时机拖到超出 API 上下文。
     */
    private volatile MessageCompactionService compactionService = new MessageCompactionService();
    private final LlmProvider apiClient;
    /**
     * ToolRegistry 可替换（带 setter），便于 UI/上层在 MCP 动态工具刷新后无需重建 engine。
     * 多线程场景下通过 volatile 保证可见性。
     */
    private volatile ToolRegistry toolRegistry;
    private final PermissionChecker permissionChecker;
    private volatile Path cwd;
    private final String systemPrompt;
    private final int maxTurns;
    /** 流式中断标志：submitMessage 开始时置 false，cancel() 置 true，循环每轮检查。 */
    private final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * 会话持久化钩子。每当一轮交互结束（含压缩、超时、取消）后调用，
     * 由上层决定如何把当前 messages 快照写入 SessionStorage。null 表示关闭自动保存。
     */
    private volatile java.util.function.Consumer<java.util.List<ConversationMessage>>
            sessionPersister;

    /** Hook 发射桥：通过反射调用 capability.hook.HookExecutor，避免内核包硬依赖能力包。 */
    private final HookEmitterBridge hookBridge =
            new HookEmitterBridge(() -> cwd == null ? null : cwd.toString());

    /** 工具调用调度器：单/多个 ToolUseBlock 的统一执行通路（含权限、并行、超时）。 */
    private final ToolCallDispatcher toolDispatcher;

    /** 已批准计划步骤执行器：复用 toolDispatcher.executeToolCall。 */
    private final PlanStepRunner planStepRunner;

    /**
     * 构造函数（兼容旧签名）。
     */
    public QueryEngine(OpenAiApiClient apiClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker, Path cwd,
                       String systemPrompt, int maxTurns) {
        this((LlmProvider) apiClient, toolRegistry, permissionChecker, cwd, systemPrompt, maxTurns);
    }

    /**
     * 构造函数：支持任意 {@link LlmProvider} 实现。
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
        this.toolDispatcher = new ToolCallDispatcher(
                () -> this.toolRegistry, permissionChecker, () -> this.cwd);
        this.planStepRunner = new PlanStepRunner(toolDispatcher);
    }

    /**
     * 取消当前正在进行的查询。
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
     * 注入自定义压缩服务，上层可通过它把 systemPrompt 的 token 占用扣减到预算里，
     * 或换成基于 token 的更激进压缩策略。
     */
    public void setCompactionService(MessageCompactionService service) {
        if (service != null) {
            this.compactionService = service;
        }
    }

    /**
     * 替换 ToolRegistry。用于 MCP 异步连接完成后把动态工具刷新到 engine。
     * 通常直接在原 ToolRegistry 上 refreshMcpTools 就够了，此 setter 仅用于极端场景。
     */
    public void setToolRegistry(ToolRegistry registry) {
        if (registry != null) {
            this.toolRegistry = registry;
        }
    }

    /**
     * 获取 ToolRegistry，供交互 UI / 命令注册表使用。
     */
    public ToolRegistry getToolRegistry() {
        return this.toolRegistry;
    }

    /**
     * 已加载的插件列表（只用于在构造 CommandRegistry / teamRegistry 时
     * 传递 plugin 的 commands & agents，不影响 engine 本身行为）。
     * 用 Object 持有避免 QueryEngine 直接依赖 extension 包，降低耦合。
     */
    private volatile java.util.List<Object> loadedPlugins = java.util.Collections.emptyList();

    public void setLoadedPlugins(java.util.List<?> plugins) {
        this.loadedPlugins = plugins == null
                ? java.util.Collections.emptyList()
                : java.util.List.copyOf(plugins);
    }

    public java.util.List<Object> getLoadedPlugins() {
        return this.loadedPlugins;
    }

    /**
     * 设置会话持久化钩子，null 表示关闭自动保存。
     * QueryEngine 会在每轮消息变更后（包括压缩、工具结果、超时、取消）回调一次。
     */
    public void setSessionPersister(
            java.util.function.Consumer<java.util.List<ConversationMessage>> persister) {
        this.sessionPersister = persister;
    }

    /**
     * 注入可选的 Hook 发射器 + 会话 ID。
     *
     * @param hookExecutor {@code io.leavesfly.jharness.capability.hook.HookExecutor} 实例；null 表示关闭 Hook
     * @param sessionId    会话 ID，写入 payload "session_id"
     */
    public void setHookEmitter(Object hookExecutor, String sessionId) {
        this.hookBridge.configure(hookExecutor, sessionId);
    }

    /**
     * 向 sessionPersister 推送当前消息快照，异常被吞掉并记录日志，
     * 保证持久化失败不会影响主循环。
     */
    private void persistIfNeeded() {
        java.util.function.Consumer<java.util.List<ConversationMessage>> p = this.sessionPersister;
        if (p == null) return;
        List<ConversationMessage> snapshot;
        synchronized (messageLock) {
            snapshot = new ArrayList<>(messages);
        }
        try {
            p.accept(snapshot);
        } catch (Exception e) {
            logger.warn("会话持久化失败（忽略）: {}", e.getMessage());
        }
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
        // 新的提交开始时复位取消标志，避免上次的 cancel 影响新查询
        cancelled.set(false);
        // USER_PROMPT_SUBMIT：在消息入队前触发，payload 含 prompt 供审计/重写
        hookBridge.fire("USER_PROMPT_SUBMIT", java.util.Map.of("prompt", prompt == null ? "" : prompt));
        synchronized (messageLock) {
            messages.add(ConversationMessage.userText(prompt));
        }
        return runQuery(eventConsumer)
                .whenComplete((v, err) -> {
                    // STOP：无论正常 / 异常 / 取消，都发射一次 STOP
                    java.util.Map<String, Object> p = new java.util.HashMap<>();
                    p.put("cancelled", cancelled.get());
                    p.put("error", err == null ? null : String.valueOf(err.getMessage()));
                    hookBridge.fire("STOP", p);
                });
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
     * 暴露运行时 PermissionChecker 给 TUI / CLI 的命令处理器，
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
     * engine 关闭时要调用的额外清理钩子。用于把 MCP / Cron / BackgroundTask 等后台资源
     * 的生命周期绑定到 engine，避免单测或脚本调用时线程泄漏。
     *
     * <p>使用 CopyOnWriteArrayList 保证注册/遍历的线程安全；
     * 钩子逆序执行（后注册先释放），符合 LIFO 的资源关闭习惯。
     */
    private final java.util.List<Runnable> closeHooks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 注册一个在 {@link #close()} 时执行的清理钩子。
     * 钩子内部应自行处理异常；主流程会在异常时记录日志但不中断后续钩子。
     */
    public void registerCloseHook(Runnable hook) {
        if (hook != null) {
            closeHooks.add(hook);
        }
    }

    /**
     * 关闭 QueryEngine，释放底层 API 客户端资源（HTTP 连接池、线程池等），
     * 并按 LIFO 顺序执行所有已注册的 close hook。
     */
    @Override
    public void close() {
        try {
            apiClient.close();
        } catch (Exception e) {
            logger.warn("关闭 ApiClient 失败", e);
        }
        // 逆序执行注册的 close hook，每个钩子独立 try/catch
        for (int i = closeHooks.size() - 1; i >= 0; i--) {
            try {
                closeHooks.get(i).run();
            } catch (Exception e) {
                logger.warn("close hook 执行失败（忽略）: {}", e.getMessage());
            }
        }
        closeHooks.clear();
    }

    /**
     * 执行查询循环
     */
    private CompletableFuture<Void> runQuery(Consumer<StreamEvent> eventConsumer) {
        return CompletableFuture.runAsync(() -> {
            try {
                for (int turn = 0; turn < maxTurns; turn++) {
                    logger.debug("执行第 {} 轮查询", turn + 1);

                    // 每轮循环开始检查取消标志
                    if (cancelled.get()) {
                        logger.info("查询被用户取消（第 {} 轮前）", turn + 1);
                        eventConsumer.accept(new io.leavesfly.jharness.kernel.engine.stream.AssistantTurnComplete(
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
                        // SSE 被 cancelAllActiveRequests 关闭时，future 以 CancellationException 完成
                        logger.info("LLM 请求被取消");
                        eventConsumer.accept(new io.leavesfly.jharness.kernel.engine.stream.AssistantTurnComplete(
                                "查询已被用户取消"));
                        return;
                    } catch (java.util.concurrent.CompletionException ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        if (cause instanceof java.util.concurrent.CancellationException
                                || cancelled.get()) {
                            logger.info("LLM 请求被取消: {}", cause.getMessage());
                            eventConsumer.accept(new io.leavesfly.jharness.kernel.engine.stream.AssistantTurnComplete(
                                    "查询已被用户取消"));
                            return;
                        }
                        throw ex;
                    }

                    // 拿到响应后再次检查取消标志（避免把"已取消但已返回"的结果继续推进到工具执行）
                    if (cancelled.get()) {
                        logger.info("查询已在收到响应后被取消");
                        eventConsumer.accept(new io.leavesfly.jharness.kernel.engine.stream.AssistantTurnComplete(
                                "查询已被用户取消"));
                        return;
                    }

                    // 记录使用量（可能抛 BudgetExceededException）
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
                        eventConsumer.accept(new io.leavesfly.jharness.kernel.engine.stream.AssistantTurnComplete(
                                "⚠ " + bex.getMessage()));
                        return;
                    }

                    // 可观测性：每轮推送一次 UsageReport，让 UI 实时展示 token / 成本
                    // 注意：必须在 addUsage 之后采样，才能反映最新累计值
                    try {
                        eventConsumer.accept(new io.leavesfly.jharness.kernel.engine.stream.UsageReport(
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

                    // Plan Mode 拦截：如果处于 Plan Mode，将工具调用记录为 PlanStep 而非执行
                    ExecutionPlan activePlan = EnterPlanModeTool.getActivePlan();
                    if (activePlan != null) {
                        List<ToolResultBlock> planResults = PlanModeInterceptor.intercept(
                                toolUses, activePlan, eventConsumer);
                        ConversationMessage planResultMsg = new ConversationMessage(
                                MessageRole.USER, new ArrayList<>(planResults));
                        synchronized (messageLock) {
                            messages.add(planResultMsg);
                        }
                        continue;
                    }

                    // 执行工具（统一通过 ToolCallDispatcher，含并行执行、超时、取消、权限）
                    List<ToolResultBlock> results = toolDispatcher.execute(toolUses, eventConsumer);

                    // 添加工具结果消息
                    ConversationMessage resultMsg = new ConversationMessage(
                            MessageRole.USER, new ArrayList<>(results));
                    synchronized (messageLock) {
                        messages.add(resultMsg);
                    }
                }

                // P2-M17：达到最大轮次时通过事件通知上层，避免用户误以为是模型正常结束
                logger.warn("达到最大轮次限制 ({})", maxTurns);
                eventConsumer.accept(new io.leavesfly.jharness.kernel.engine.stream.AssistantTurnComplete(
                        "达到最大轮次限制 (" + maxTurns + ")，本轮对话已结束"));
            } catch (Exception e) {
                logger.error("查询执行失败", e);
                throw new RuntimeException("查询执行失败", e);
            }
        });
    }

    /**
     * 执行已批准的 PlanStep 列表（薄门面，委托给 {@link PlanStepRunner}）。
     * 由 /approve 或 /approve_all 命令触发。
     */
    public String executeApprovedPlanSteps(ExecutionPlan plan, Consumer<StreamEvent> eventConsumer) {
        return planStepRunner.run(plan, eventConsumer);
    }
}
