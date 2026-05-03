package io.leavesfly.jharness.agent.tasks;

import io.leavesfly.jharness.session.permissions.PermissionChecker;
import io.leavesfly.jharness.session.permissions.PermissionDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台任务管理器
 *
 * 管理后台进程任务的生命周期。
 *
 * 改进点（P2-M3）：
 * - 实现 AutoCloseable，允许调用方用 try-with-resources 自动关闭；
 * - 使用命名 ThreadFactory，daemon=false，便于在日志和线程 dump 中定位；
 * - shutdown 幂等，重复调用不会抛异常。
 */
public class BackgroundTaskManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(BackgroundTaskManager.class);

    private static final int MAX_THREAD_POOL_SIZE = 20;

    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final Path outputDir;
    private volatile boolean closed = false;

    /**
     * FP-3：可选的 PermissionChecker。注入后，后台 shell / agent 任务在 fork 子进程前会先
     * 走一遍权限评估；未注入时保持旧行为。通过 setter 注入避免破坏既有构造签名。
     */
    private volatile PermissionChecker permissionChecker;

    public BackgroundTaskManager(Path outputDir) {
        this.outputDir = outputDir;
        this.executor = Executors.newFixedThreadPool(MAX_THREAD_POOL_SIZE, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "jharness-bg-task-" + counter.incrementAndGet());
                t.setDaemon(false);
                t.setUncaughtExceptionHandler((thread, ex) ->
                        logger.error("后台任务线程未捕获异常: {}", thread.getName(), ex));
                return t;
            }
        });
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            logger.error("创建任务输出目录失败", e);
        }
    }

    /**
     * FP-3：注入 PermissionChecker，后续 shell / agent 任务在 fork 子进程前走权限评估。
     */
    public void setPermissionChecker(PermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    /**
     * 创建 Shell 任务
     *
     * FP-3：若注入了 PermissionChecker，先对命令做一次权限评估；被 deny 的任务立即标记为
     * FAILED，不会 fork 子进程。这样后台 shell 通道和前台 bash 工具使用同一套安全栅栏。
     */
    public TaskRecord createShellTask(String command, String description, Path cwd) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);

        TaskRecord task = new TaskRecord(taskId, command, description, cwd, TaskStatus.RUNNING);
        tasks.put(taskId, task);

        // FP-3：前置权限评估。注意任务名用 "bash"，与前台 BashTool 的 name 对齐，这样
        // PermissionChecker 的工具黑/白名单、命令黑名单对前后台路径均一致生效。
        if (permissionChecker != null) {
            PermissionDecision decision = permissionChecker.evaluate("bash", false, null, command);
            if (decision != null && !decision.isAllowed() && !decision.isRequiresConfirmation()) {
                task.setStatus(TaskStatus.FAILED);
                logger.warn("后台 shell 任务 {} 被权限拒绝: {}", taskId, decision.getReason());
                return task;
            }
        }

        executor.submit(() -> {
            Process process = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                pb.directory(cwd.toFile());
                pb.redirectOutput(outputDir.resolve(taskId + ".out").toFile());
                pb.redirectError(outputDir.resolve(taskId + ".err").toFile());

                process = pb.start();
                processes.put(taskId, process);

                int exitCode = process.waitFor();

                task.setStatus(exitCode == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED);
                logger.info("任务 {} 完成，退出码: {}", taskId, exitCode);
            } catch (Exception e) {
                task.setStatus(TaskStatus.FAILED);
                logger.error("任务 {} 失败", taskId, e);
            } finally {
                processes.remove(taskId);
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        });

        return task;
    }

    /**
     * 获取任务
     */
    public TaskRecord getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 列出所有任务
     */
    public List<TaskRecord> listTasks(TaskStatus status) {
        return tasks.values().stream()
                .filter(t -> status == null || t.getStatus() == status)
                .toList();
    }

    /**
     * 停止任务
     */
    public boolean stopTask(String taskId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null) return false;

        if (task.getStatus() == TaskStatus.RUNNING || task.getStatus() == TaskStatus.PENDING) {
            // 先终止进程
            Process process = processes.get(taskId);
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            task.setStatus(TaskStatus.STOPPED);
            processes.remove(taskId);
            return true;
        }
        return false;
    }

    /**
     * 读取任务输出
     */
    public String readTaskOutput(String taskId) {
        Path outputFile = outputDir.resolve(taskId + ".out");
        try {
            if (Files.exists(outputFile)) {
                return Files.readString(outputFile);
            }
            return "";
        } catch (IOException e) {
            logger.error("读取任务输出失败", e);
            return "";
        }
    }

    /**
     * 关闭任务管理器，终止所有运行中的进程并关闭线程池（幂等，P2-M3）。
     */
    public synchronized void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        for (Map.Entry<String, Process> entry : processes.entrySet()) {
            Process process = entry.getValue();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                logger.info("强制终止进程: {}", entry.getKey());
            }
        }
        processes.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    logger.warn("任务线程池强制关闭后仍未结束");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** AutoCloseable 实现（P2-M3）：委托给 shutdown，使得 try-with-resources 能优雅关闭。 */
    @Override
    public void close() {
        shutdown();
    }

    /**
     * 创建 Agent 任务
     *
     * 通过 JHarnessApplication 主类以子进程方式启动独立的 Agent，
     * 使用 -p 参数传入 prompt 实现单次查询模式。
     */
    public TaskRecord createAgentTask(String prompt, String description, Path cwd,
                                      String model, String apiKey) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);

        TaskRecord task = new TaskRecord(taskId, null, description, cwd,
                TaskStatus.PENDING, TaskRecord.TaskType.LOCAL_AGENT);
        task.setPrompt(prompt);
        task.setModel(model);
        tasks.put(taskId, task);

        // FP-3：agent_spawn 工具在黑名单中时，不允许通过后台入口绕过。
        // 命令参数填 null（agent 启动不是一条 shell 命令），仅走工具名校验。
        if (permissionChecker != null) {
            PermissionDecision decision = permissionChecker.evaluate("agent_spawn", false, null, null);
            if (decision != null && !decision.isAllowed() && !decision.isRequiresConfirmation()) {
                task.setStatus(TaskStatus.FAILED);
                logger.warn("后台 agent 任务 {} 被权限拒绝: {}", taskId, decision.getReason());
                return task;
            }
        }

        executor.submit(() -> {
            Process process = null;
            try {
                String javaHome = System.getProperty("java.home");
                String classpath = System.getProperty("java.class.path");

                List<String> command = new ArrayList<>();
                command.add(javaHome + "/bin/java");
                command.add("-cp");
                command.add(classpath);
                command.add("io.leavesfly.jharness.JHarnessApplication");
                command.add("-p");
                command.add(prompt);
                if (model != null && !model.isEmpty()) {
                    command.add("-m");
                    command.add(model);
                }
                command.add("--permission-mode");
                command.add("full_auto");

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(cwd.toFile());
                pb.redirectOutput(outputDir.resolve(taskId + ".out").toFile());
                pb.redirectError(outputDir.resolve(taskId + ".err").toFile());

                if (apiKey != null && !apiKey.isEmpty()) {
                    pb.environment().put("ANTHROPIC_API_KEY", apiKey);
                }

                process = pb.start();
                processes.put(taskId, process);

                task.setStatus(TaskStatus.RUNNING);
                logger.info("Agent 任务 {} 已启动", taskId);

                int exitCode = process.waitFor();
                task.setExitCode(exitCode);
                task.setStatus(exitCode == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED);
                logger.info("Agent 任务 {} 完成，退出码: {}", taskId, exitCode);

            } catch (Exception e) {
                task.setStatus(TaskStatus.FAILED);
                logger.error("Agent 任务 {} 失败", taskId, e);
            } finally {
                Process remainingProcess = processes.remove(taskId);
                if (remainingProcess != null && remainingProcess.isAlive()) {
                    remainingProcess.destroyForcibly();
                    logger.warn("Agent 任务 {} 的进程在 finally 中被强制终止", taskId);
                }
            }
        });

        return task;
    }

    /**
     * 写入任务 stdin（P2 顺带修复：使用 UTF-8，避免平台默认字符集导致乱码）。
     */
    public boolean writeToTask(String taskId, String message) {
        Process process = processes.get(taskId);
        if (process == null || !process.isAlive()) {
            return false;
        }

        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write((message + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stdin.flush();
            return true;
        } catch (IOException e) {
            logger.error("写入任务 {} stdin 失败", taskId, e);
            return false;
        }
    }

    /**
     * 强制终止任务
     */
    public boolean killTask(String taskId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null) return false;

        Process process = processes.remove(taskId);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        task.setStatus(TaskStatus.KILLED);
        return true;
    }
}
