package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.integration.CronRegistry;
import io.leavesfly.jharness.tools.input.RemoteTriggerToolInput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/**
 * 按名称触发执行已注册的定时作业
 * 
 * 此工具查找作业定义并通过 ProcessBuilder 执行其命令。
 */
public class RemoteTriggerTool extends BaseTool<RemoteTriggerToolInput> {
    private final CronRegistry cronRegistry;

    public RemoteTriggerTool(CronRegistry cronRegistry) {
        this.cronRegistry = cronRegistry;
    }

    @Override
    public String getName() {
        return "remote_trigger";
    }

    @Override
    public String getDescription() {
        return "Trigger execution of a registered cron job by name. " +
               "Finds the job definition and runs its command immediately.";
    }

    @Override
    public Class<RemoteTriggerToolInput> getInputClass() {
        return RemoteTriggerToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(RemoteTriggerToolInput input, ToolExecutionContext context) {
        if (input.getName() == null || input.getName().isEmpty()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Parameter 'name' is required")
            );
        }

        try {
            Map<String, Object> job = cronRegistry.getJob(input.getName());
            if (job == null) {
                return CompletableFuture.completedFuture(
                    ToolResult.error("Cron job not found: " + input.getName())
                );
            }

            Boolean enabled = (Boolean) job.getOrDefault("enabled", true);
            if (!enabled) {
                return CompletableFuture.completedFuture(
                    ToolResult.error("Cron job is disabled: " + input.getName())
                );
            }

            String command = (String) job.get("command");
            String cwd = (String) job.get("cwd");
            int timeoutSeconds = input.getTimeoutSeconds();

            // 执行命令
            return executeCommand(command, cwd, timeoutSeconds, input.getName());
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Failed to trigger job: " + e.getMessage())
            );
        }
    }

    private CompletableFuture<ToolResult> executeCommand(String command, String cwd, 
                                                          int timeoutSeconds, String jobName) {
        return CompletableFuture.supplyAsync(() -> {
            Process process = null;
            try {
                ProcessBuilder pb;
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    pb = new ProcessBuilder("cmd", "/c", command);
                } else {
                    pb = new ProcessBuilder("bash", "-c", command);
                }

                if (cwd != null && !cwd.isEmpty()) {
                    pb.directory(new java.io.File(cwd));
                }

                pb.redirectErrorStream(true);
                process = pb.start();

                // 读取输出
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                // 等待完成，带超时；超时后先 destroy，若仍存活则 destroyForcibly
                boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

                int exitCode;
                if (completed) {
                    exitCode = process.exitValue();
                } else {
                    process.destroy();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(2, TimeUnit.SECONDS);
                    }
                    return ToolResult.error(
                        String.format("Job '%s' timed out after %d seconds.\nOutput so far:\n%s",
                            jobName, timeoutSeconds, output.toString())
                    );
                }

                // 记录执行时间
                cronRegistry.markExecuted(jobName, Instant.now());

                String result = String.format(
                    "Job '%s' completed with exit code %d.\nOutput:\n%s",
                    jobName, exitCode, output.toString()
                );

                if (exitCode == 0) {
                    return ToolResult.success(result);
                } else {
                    return ToolResult.error(result);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                return ToolResult.error("Job '" + jobName + "' interrupted");
            } catch (IOException e) {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                return ToolResult.error("Failed to execute job '" + jobName + "': " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(RemoteTriggerToolInput input) {
        return false;
    }
}
