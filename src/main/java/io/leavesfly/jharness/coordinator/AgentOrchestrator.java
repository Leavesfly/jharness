package io.leavesfly.jharness.coordinator;

import io.leavesfly.jharness.engine.QueryEngine;
import io.leavesfly.jharness.tools.ToolRegistry;
import io.leavesfly.jharness.engine.model.ConversationMessage;
import io.leavesfly.jharness.engine.stream.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Agent 编排器
 * 
 * 负责多 Agent 任务的分配、执行和协调。
 * 支持并行执行和顺序执行两种模式。
 */
public class AgentOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final QueryEngine queryEngine;
    private final ToolRegistry toolRegistry;
    private final ExecutorService executorService;
    private final Map<String, AgentInstance> activeAgents = new ConcurrentHashMap<>();
    private QueryEngineFactory queryEngineFactory;

    /**
     * QueryEngine 工厂接口，用于为每个 Agent 创建独立的引擎实例
     */
    @FunctionalInterface
    public interface QueryEngineFactory {
        QueryEngine create(String model, String systemPrompt);
    }

    public AgentOrchestrator(QueryEngine queryEngine, ToolRegistry toolRegistry) {
        this.queryEngine = queryEngine;
        this.toolRegistry = toolRegistry;
        this.executorService = Executors.newCachedThreadPool();
    }

    public void setQueryEngineFactory(QueryEngineFactory factory) {
        this.queryEngineFactory = factory;
    }
    
    /**
     * 并行执行多个任务
     * 
     * @param tasks 任务列表，每个任务包含提示词和配置
     * @param eventConsumer 事件消费者
     * @return 所有任务的结果
     */
    public CompletableFuture<List<AgentResult>> executeParallel(List<AgentTask> tasks, Consumer<StreamEvent> eventConsumer) {
        List<CompletableFuture<AgentResult>> futures = new ArrayList<>();
        
        for (AgentTask task : tasks) {
            futures.add(executeSingle(task, eventConsumer));
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .orTimeout(30, java.util.concurrent.TimeUnit.MINUTES)
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .toList())
            .exceptionally(e -> {
                logger.error("并行 Agent 执行超时或异常", e);
                return futures.stream()
                    .map(f -> f.getNow(new AgentResult("unknown", "unknown", false, null, "执行超时")))
                    .toList();
            });
    }
    
    /**
     * 顺序执行多个任务
     * 
     * @param tasks 任务列表
     * @param eventConsumer 事件消费者
     * @return 所有任务的结果
     */
    public CompletableFuture<List<AgentResult>> executeSequential(List<AgentTask> tasks, Consumer<StreamEvent> eventConsumer) {
        return CompletableFuture.supplyAsync(() -> {
            List<AgentResult> results = new ArrayList<>();
            List<String> previousResults = new ArrayList<>();
            
            for (AgentTask task : tasks) {
                // 将前一个任务的结果作为上下文传递给下一个任务
                String enhancedPrompt = task.getPrompt();
                if (!previousResults.isEmpty()) {
                    enhancedPrompt = "前序任务结果:\n" + String.join("\n\n", previousResults) + 
                                   "\n\n当前任务:\n" + task.getPrompt();
                }
                
                AgentTask enhancedTask = new AgentTask(
                    task.getId(),
                    task.getName(),
                    enhancedPrompt,
                    task.getModel(),
                    task.getSystemPrompt()
                );
                
                AgentResult result = executeSingle(enhancedTask, eventConsumer).join();
                results.add(result);
                
                if (result.isSuccess()) {
                    previousResults.add(result.getOutput());
                }
            }
            
            return results;
        }, executorService);
    }
    
    /**
     * 清理已完成的 Agent 实例，防止内存泄漏
     */
    public void cleanupCompletedAgents() {
        activeAgents.entrySet().removeIf(entry -> {
            String status = entry.getValue().getStatus();
            return "completed".equals(status) || "failed".equals(status);
        });
    }

    /**
     * 执行单个 Agent 任务
     */
    private CompletableFuture<AgentResult> executeSingle(AgentTask task, Consumer<StreamEvent> eventConsumer) {
        return CompletableFuture.supplyAsync(() -> {
            String agentId = UUID.randomUUID().toString().substring(0, 8);
            activeAgents.put(agentId, new AgentInstance(agentId, task.getName(), "running"));
            
            try {
                // 创建临时 QueryEngine 实例（或使用共享实例）
                QueryEngine agentEngine = createAgentEngine(task, agentId);
                
                // 提交任务
                List<ConversationMessage> messages = new ArrayList<>();
                messages.add(ConversationMessage.userText(task.getPrompt()));
                
                // 执行查询
                StringBuilder output = new StringBuilder();
                agentEngine.submitMessage(task.getPrompt(), event -> {
                    output.append(event.toString()).append("\n");
                    if (eventConsumer != null) {
                        eventConsumer.accept(event);
                    }
                });
                
                AgentResult result = new AgentResult(
                    agentId,
                    task.getName(),
                    true,
                    output.toString(),
                    null
                );
                
                activeAgents.put(agentId, new AgentInstance(agentId, task.getName(), "completed"));
                return result;
                
            } catch (Exception e) {
                logger.error("Agent {} 执行失败: {}", agentId, task.getName(), e);
                AgentResult result = new AgentResult(
                    agentId,
                    task.getName(),
                    false,
                    null,
                    e.getMessage()
                );
                
                activeAgents.put(agentId, new AgentInstance(agentId, task.getName(), "failed"));
                return result;
            }
        }, executorService);
    }
    
    /**
     * 为 Agent 创建专用的 QueryEngine
     *
     * 每个 Agent 使用独立的 QueryEngine 实例，避免共享消息历史导致互相干扰。
     * 可通过 AgentTask 指定不同的模型和系统提示。
     */
    private QueryEngine createAgentEngine(AgentTask task, String agentId) {
        if (queryEngineFactory != null) {
            String agentModel = task.getModel();
            String agentSystemPrompt = task.getSystemPrompt();
            return queryEngineFactory.create(agentModel, agentSystemPrompt);
        }
        // 回退：使用共享引擎（不推荐，仅用于未配置工厂的场景）
        logger.warn("Agent {} 使用共享 QueryEngine（未配置 QueryEngineFactory）", agentId);
        return queryEngine;
    }
    
    /**
     * 获取活跃 Agent 列表
     */
    public List<AgentInstance> getActiveAgents() {
        return new ArrayList<>(activeAgents.values());
    }
    
    /**
     * 获取 Agent 状态
     */
    public AgentInstance getAgentStatus(String agentId) {
        return activeAgents.get(agentId);
    }
    
    /**
     * 关闭所有 Agent
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                logger.warn("Agent 线程池强制关闭，部分任务可能未完成");
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        activeAgents.clear();
    }
    
    /**
     * Agent 任务定义
     */
    public static class AgentTask {
        private final String id;
        private final String name;
        private final String prompt;
        private final String model;
        private final String systemPrompt;
        
        public AgentTask(String id, String name, String prompt, String model, String systemPrompt) {
            this.id = id;
            this.name = name;
            this.prompt = prompt;
            this.model = model;
            this.systemPrompt = systemPrompt;
        }
        
        public AgentTask(String name, String prompt) {
            this(UUID.randomUUID().toString().substring(0, 8), name, prompt, null, null);
        }
        
        public String getId() { return id; }
        public String getName() { return name; }
        public String getPrompt() { return prompt; }
        public String getModel() { return model; }
        public String getSystemPrompt() { return systemPrompt; }
    }
    
    /**
     * Agent 执行结果
     */
    public static class AgentResult {
        private final String agentId;
        private final String taskName;
        private final boolean success;
        private final String output;
        private final String error;
        
        public AgentResult(String agentId, String taskName, boolean success, String output, String error) {
            this.agentId = agentId;
            this.taskName = taskName;
            this.success = success;
            this.output = output;
            this.error = error;
        }
        
        public String getAgentId() { return agentId; }
        public String getTaskName() { return taskName; }
        public boolean isSuccess() { return success; }
        public String getOutput() { return output; }
        public String getError() { return error; }
    }
    
    /**
     * Agent 实例状态
     */
    public static class AgentInstance {
        private final String id;
        private final String name;
        private final String status;
        private final long startTime;
        
        public AgentInstance(String id, String name, String status) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.startTime = System.currentTimeMillis();
        }
        
        public String getId() { return id; }
        public String getName() { return name; }
        public String getStatus() { return status; }
        public long getStartTime() { return startTime; }
    }
}
