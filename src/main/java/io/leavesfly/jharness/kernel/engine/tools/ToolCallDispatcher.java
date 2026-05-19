package io.leavesfly.jharness.kernel.engine.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.capability.permission.PermissionDecision;
import io.leavesfly.jharness.kernel.engine.model.ToolResultBlock;
import io.leavesfly.jharness.kernel.engine.model.ToolUseBlock;
import io.leavesfly.jharness.kernel.engine.stream.StreamEvent;
import io.leavesfly.jharness.kernel.engine.stream.ToolExecutionCompleted;
import io.leavesfly.jharness.kernel.engine.stream.ToolExecutionStarted;
import io.leavesfly.jharness.kernel.spi.PermissionGate;
import io.leavesfly.jharness.kernel.spi.ToolCatalog;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import io.leavesfly.jharness.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 工具调用调度器：执行一组 {@link ToolUseBlock}，返回与之一一对应的 {@link ToolResultBlock}。
 *
 * <ul>
 *   <li>单个工具顺序执行；多个工具并行执行，整体 5 分钟超时；</li>
 *   <li>每次执行前后向 {@code eventConsumer} 推送 {@link ToolExecutionStarted} /
 *       {@link ToolExecutionCompleted}；</li>
 *   <li>调用前过 {@link PermissionChecker}，拒绝时直接返回错误结果；</li>
 *   <li>异常 / 取消 / 超时都转换为 {@code ToolResult.error}，保证 tool_result 与 tool_use 一一配对，
 *       否则模型会因协议不一致而失败。</li>
 * </ul>
 */
public final class ToolCallDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PARALLEL_TIMEOUT_MINUTES = 5;

    private final Supplier<? extends ToolCatalog> toolRegistrySupplier;
    private final PermissionGate permissionChecker;
    private final Supplier<Path> cwdSupplier;

    public ToolCallDispatcher(Supplier<? extends ToolCatalog> toolRegistrySupplier,
                              PermissionGate permissionChecker,
                              Supplier<Path> cwdSupplier) {
        this.toolRegistrySupplier = toolRegistrySupplier;
        this.permissionChecker = permissionChecker;
        this.cwdSupplier = cwdSupplier;
    }

    /** 执行一组工具调用，返回顺序对应的 {@link ToolResultBlock} 列表。 */
    public List<ToolResultBlock> execute(List<ToolUseBlock> toolUses,
                                          Consumer<StreamEvent> eventConsumer) {
        if (toolUses.size() == 1) {
            return executeSingle(toolUses.get(0), eventConsumer);
        }
        return executeParallel(toolUses, eventConsumer);
    }

    private List<ToolResultBlock> executeSingle(ToolUseBlock toolUse,
                                                 Consumer<StreamEvent> eventConsumer) {
        eventConsumer.accept(new ToolExecutionStarted(toolUse.getName(), toolUse.getId()));
        ToolResult result;
        try {
            result = executeToolCall(toolUse).join();
        } catch (CancellationException ce) {
            result = ToolResult.error("工具执行被取消");
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            logger.error("工具执行异常: {}", toolUse.getName(), cause);
            result = ToolResult.error("工具执行异常: " + cause.getMessage());
        }
        eventConsumer.accept(new ToolExecutionCompleted(
                toolUse.getName(), toolUse.getId(), result.getOutput(), result.isError()));
        return List.of(result.toBlock(toolUse.getId()));
    }

    private List<ToolResultBlock> executeParallel(List<ToolUseBlock> toolUses,
                                                   Consumer<StreamEvent> eventConsumer) {
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

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(PARALLEL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            logger.error("工具并行执行超时 ({}分钟)，取消所有未完成任务", PARALLEL_TIMEOUT_MINUTES);
            futures.forEach(f -> f.cancel(true));
            return collectResultsAfterTimeout(toolUses, futures);
        } catch (ExecutionException e) {
            logger.error("工具并行执行异常", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("工具并行执行被中断");
            futures.forEach(f -> f.cancel(true));
        } catch (CancellationException e) {
            // 外层取消时让每个 future 各自上报错误结果，不影响模型侧的 tool_result 配对
            logger.warn("工具并行执行被取消: {}", e.getMessage());
        }

        return collectResults(toolUses, futures);
    }

    private List<ToolResultBlock> collectResultsAfterTimeout(List<ToolUseBlock> toolUses,
                                                              List<CompletableFuture<ToolResult>> futures) {
        List<ToolResultBlock> timeoutResults = new ArrayList<>();
        for (int i = 0; i < toolUses.size(); i++) {
            CompletableFuture<ToolResult> future = futures.get(i);
            ToolResult result = future.isDone()
                    ? future.getNow(ToolResult.error("工具执行超时"))
                    : ToolResult.error("工具执行超时 (" + PARALLEL_TIMEOUT_MINUTES + "分钟)");
            timeoutResults.add(result.toBlock(toolUses.get(i).getId()));
        }
        return timeoutResults;
    }

    private List<ToolResultBlock> collectResults(List<ToolUseBlock> toolUses,
                                                  List<CompletableFuture<ToolResult>> futures) {
        List<ToolResultBlock> results = new ArrayList<>();
        for (int i = 0; i < toolUses.size(); i++) {
            ToolUseBlock toolUse = toolUses.get(i);
            CompletableFuture<ToolResult> future = futures.get(i);
            ToolResult result;
            try {
                result = future.getNow(ToolResult.error("工具执行失败"));
            } catch (CancellationException ce) {
                result = ToolResult.error("工具执行被取消");
            } catch (Exception ex) {
                result = ToolResult.error("工具执行异常: " + ex.getMessage());
            }
            results.add(result.toBlock(toolUse.getId()));
        }
        return results;
    }

    /**
     * 执行单个工具：解析输入 → 权限检查 → 真正执行。
     * 暴露为 public 是为了让 {@link PlanStepRunner} 复用同一执行通路。
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<ToolResult> executeToolCall(ToolUseBlock toolUse) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BaseTool<Object> tool = (BaseTool<Object>) toolRegistrySupplier.get().get(toolUse.getName());
                if (tool == null) {
                    return ToolResult.error("未知工具: " + toolUse.getName());
                }

                Object input = MAPPER.treeToValue(toolUse.getInput(), tool.getInputClass());
                PermissionDecision decision = permissionChecker.evaluate(
                        tool.getName(),
                        tool.isReadOnly(input),
                        extractFilePath(toolUse.getInput()),
                        extractCommand(toolUse.getInput())
                );

                // 防御性编程：PermissionChecker 实现变更后返回 null 也不能 NPE
                if (decision == null) {
                    logger.error("权限检查器返回 null 决策，默认拒绝: {}", tool.getName());
                    return ToolResult.error("权限检查异常: 决策为空");
                }
                if (!decision.isAllowed()) {
                    if (decision.isRequiresConfirmation()) {
                        logger.debug("工具 {} 需要用户确认，默认允许", tool.getName());
                    } else {
                        return ToolResult.error("权限拒绝: " + decision.getReason());
                    }
                }

                // 注入 PermissionChecker，使 EnterPlanModeTool/ExitPlanModeTool 能把模式切换
                // 同步到运行时实例（而不仅落到 Settings）
                ToolExecutionContext context = new ToolExecutionContext(cwdSupplier.get(), null, permissionChecker);
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

    private static String extractFilePath(JsonNode input) {
        if (input.has("file_path")) return input.get("file_path").asText();
        if (input.has("path")) return input.get("path").asText();
        return null;
    }

    private static String extractCommand(JsonNode input) {
        if (input.has("command")) return input.get("command").asText();
        return null;
    }
}
