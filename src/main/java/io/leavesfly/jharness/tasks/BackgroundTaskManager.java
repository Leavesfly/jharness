package io.leavesfly.jharness.tasks;

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

/**
 * 后台任务管理器
 *
 * 管理后台进程任务的生命周期。
 */
public class BackgroundTaskManager {
    private static final Logger logger = LoggerFactory.getLogger(BackgroundTaskManager.class);

    private static final int MAX_THREAD_POOL_SIZE = 20;

    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_THREAD_POOL_SIZE);
    private final Path outputDir;

    public BackgroundTaskManager(Path outputDir) {
        this.outputDir = outputDir;
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            logger.error("创建任务输出目录失败", e);
        }
    }

    /**
     * 创建 Shell 任务
     */
    public TaskRecord createShellTask(String command, String description, Path cwd) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        
        TaskRecord task = new TaskRecord(taskId, command, description, cwd, TaskStatus.RUNNING);
        tasks.put(taskId, task);

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
        if (task != null && task.getStatus() == TaskStatus.RUNNING) {
            task.setStatus(TaskStatus.STOPPED);
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
     * 关闭任务管理器，终止所有运行中的进程并关闭线程池
     */
    public void shutdown() {
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
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
                processes.remove(taskId);
            }
        });

        return task;
    }

    /**
     * 写入任务 stdin
     */
    public boolean writeToTask(String taskId, String message) {
        Process process = processes.get(taskId);
        if (process == null || !process.isAlive()) {
            return false;
        }

        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write((message + "\n").getBytes());
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

        Process process = processes.get(taskId);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }

        task.setStatus(TaskStatus.KILLED);
        processes.remove(taskId);
        return true;
    }
}
