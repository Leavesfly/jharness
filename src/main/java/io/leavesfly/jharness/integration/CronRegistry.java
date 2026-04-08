package io.leavesfly.jharness.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Cron 作业注册表服务
 * 
 * 管理定时作业的定义和持久化。
 * 作业存储在 ~/.jharness/data/cron_jobs.json 文件中。
 * 
 * 支持基于 ScheduledExecutorService 的定时调度执行。
 * 作业也可通过 RemoteTriggerTool 按需触发执行。
 */
public class CronRegistry {
    private static final Logger logger = LoggerFactory.getLogger(CronRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final Path registryPath;
    private final List<Map<String, Object>> jobs = java.util.Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new HashMap<>();
    private final Object jobsLock = new Object();
    private CronJobExecutor jobExecutor;

    /**
     * Cron 作业执行器接口
     */
    @FunctionalInterface
    public interface CronJobExecutor {
        void execute(Map<String, Object> job);
    }

    /**
     * 创建 CronRegistry 实例
     * 
     * @param dataDir 数据目录 (~/.jharness/data/)
     */
    public CronRegistry(Path dataDir) {
        this.registryPath = dataDir.resolve("cron_jobs.json");
        loadJobs();
    }

    /**
     * 设置作业执行器并启动所有已启用作业的调度
     */
    public void setJobExecutor(CronJobExecutor executor) {
        this.jobExecutor = executor;
        scheduleAllEnabledJobs();
    }

    /**
     * 调度所有已启用的作业
     */
    private void scheduleAllEnabledJobs() {
        for (Map<String, Object> job : jobs) {
            Boolean enabled = (Boolean) job.getOrDefault("enabled", true);
            if (Boolean.TRUE.equals(enabled)) {
                scheduleJob(job);
            }
        }
    }

    /**
     * 调度单个作业
     *
     * 解析 cron schedule 中的间隔秒数（简化实现：支持 "@every Ns/Nm/Nh" 格式），
     * 使用 ScheduledExecutorService 定期执行。
     */
    private void scheduleJob(Map<String, Object> job) {
        String name = (String) job.get("name");
        String schedule = (String) job.getOrDefault("schedule", "");

        cancelScheduledJob(name);

        long intervalSeconds = parseScheduleInterval(schedule);
        if (intervalSeconds <= 0) {
            logger.debug("作业 {} 的 schedule '{}' 不支持自动调度，仅支持手动触发", name, schedule);
            return;
        }

        if (jobExecutor == null) {
            logger.debug("未设置 jobExecutor，跳过作业 {} 的调度", name);
            return;
        }

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                logger.info("定时执行 cron 作业: {}", name);
                jobExecutor.execute(job);
                markExecuted(name, Instant.now());
            } catch (Exception e) {
                logger.error("cron 作业 {} 执行失败", name, e);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

        scheduledTasks.put(name, future);
        logger.info("已调度 cron 作业: {}, 间隔: {}s", name, intervalSeconds);
    }

    /**
     * 取消已调度的作业
     */
    private void cancelScheduledJob(String name) {
        ScheduledFuture<?> existing = scheduledTasks.remove(name);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    /**
     * 解析 schedule 字符串为间隔秒数
     *
     * 支持格式：
     * - "@every 30s" → 30 秒
     * - "@every 5m" → 300 秒
     * - "@every 1h" → 3600 秒
     * - 标准 cron 表达式暂不支持自动调度，返回 -1
     */
    private long parseScheduleInterval(String schedule) {
        if (schedule == null || schedule.isBlank()) {
            return -1;
        }
        String trimmed = schedule.trim().toLowerCase();
        if (trimmed.startsWith("@every ")) {
            String interval = trimmed.substring(7).trim();
            try {
                if (interval.endsWith("s")) {
                    return Long.parseLong(interval.substring(0, interval.length() - 1));
                } else if (interval.endsWith("m")) {
                    return Long.parseLong(interval.substring(0, interval.length() - 1)) * 60;
                } else if (interval.endsWith("h")) {
                    return Long.parseLong(interval.substring(0, interval.length() - 1)) * 3600;
                }
            } catch (NumberFormatException e) {
                logger.warn("无法解析 schedule 间隔: {}", interval);
            }
        }
        return -1;
    }

    /**
     * 关闭调度器
     */
    public void shutdown() {
        for (ScheduledFuture<?> future : scheduledTasks.values()) {
            future.cancel(false);
        }
        scheduledTasks.clear();
        scheduler.shutdown();
    }

    /**
     * 加载作业列表
     */
    private void loadJobs() {
        synchronized (jobsLock) {
            try {
                if (Files.exists(registryPath)) {
                    String content = Files.readString(registryPath);
                    List<Map<String, Object>> loaded = MAPPER.readValue(content, List.class);
                    jobs.clear();
                    jobs.addAll(loaded);
                    logger.debug("已加载 {} 个 cron 作业", jobs.size());
                }
            } catch (IOException e) {
                logger.error("加载 cron 作业失败", e);
                jobs.clear();
            }
        }
    }

    /**
     * 保存作业列表
     */
    private void saveJobs() {
        try {
            Files.createDirectories(registryPath.getParent());
            String content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jobs);
            Files.writeString(registryPath, content);
            logger.debug("已保存 {} 个 cron 作业", jobs.size());
        } catch (IOException e) {
            logger.error("保存 cron 作业失败", e);
        }
    }

    /**
     * 添加或更新作业
     * 
     * @param job 作业定义（必须包含 name, schedule, command 字段）
     */
    public void upsertJob(Map<String, Object> job) {
        String name = (String) job.get("name");
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Job name is required");
        }

        // 检查调度器是否已关闭
        if (scheduler.isShutdown()) {
            throw new IllegalStateException("调度器已关闭，无法添加新作业");
        }

        synchronized (jobsLock) {
            // 移除同名作业
            jobs.removeIf(existing -> name.equals(existing.get("name")));

            // 添加新作业
            if (!job.containsKey("created_at")) {
                job.put("created_at", Instant.now().toString());
            }
            job.put("updated_at", Instant.now().toString());
            if (!job.containsKey("last_executed")) {
                job.put("last_executed", null);
            }
            if (!job.containsKey("enabled")) {
                job.put("enabled", true);
            }

            jobs.add(job);

            // 按名称排序
            jobs.sort(Comparator.comparing(j -> (String) j.getOrDefault("name", "")));

            saveJobs();
        }

        // 如果作业已启用，则调度它
        Boolean enabled = (Boolean) job.getOrDefault("enabled", true);
        if (Boolean.TRUE.equals(enabled)) {
            scheduleJob(job);
        }

        logger.info("Cron 作业已更新: {}", name);
    }

    /**
     * 删除作业
     * 
     * @param name 作业名称
     * @return 如果删除成功返回 true，作业不存在返回 false
     */
    public boolean deleteJob(String name) {
        boolean removed;
        synchronized (jobsLock) {
            int before = jobs.size();
            jobs.removeIf(job -> name.equals(job.get("name")));
            removed = jobs.size() < before;

            if (removed) {
                saveJobs();
            }
        }

        if (removed) {
            cancelScheduledJob(name);
            logger.info("Cron 作业已删除: {}", name);
        }

        return removed;
    }

    /**
     * 获取单个作业
     * 
     * @param name 作业名称
     * @return 作业定义，不存在返回 null
     */
    public Map<String, Object> getJob(String name) {
        return jobs.stream()
                .filter(job -> name.equals(job.get("name")))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取所有作业
     * 
     * @return 作业列表（按名称排序）
     */
    public List<Map<String, Object>> listJobs() {
        return Collections.unmodifiableList(jobs);
    }

    /**
     * 更新作业的执行时间
     * 
     * @param name 作业名称
     * @param executedAt 执行时间
     */
    public void markExecuted(String name, Instant executedAt) {
        synchronized (jobsLock) {
            Map<String, Object> job = getJob(name);
            if (job != null) {
                job.put("last_executed", executedAt.toString());
                job.put("execution_count", ((Number) job.getOrDefault("execution_count", 0)).intValue() + 1);
                saveJobs();
            }
        }
    }

    /**
     * 获取作业摘要
     * 
     * @return 格式化的作业列表摘要
     */
    public String getSummary() {
        if (jobs.isEmpty()) {
            return "No cron jobs registered.";
        }

        String formatted = jobs.stream()
                .map(job -> {
                    String name = (String) job.get("name");
                    String schedule = (String) job.getOrDefault("schedule", "");
                    String command = (String) job.get("command");
                    Boolean enabled = (Boolean) job.getOrDefault("enabled", true);
                    String status = enabled ? "enabled" : "disabled";
                    return String.format("  %s [%s] (%s) - %s", name, schedule, status, command);
                })
                .collect(Collectors.joining("\n"));

        return String.format("Registered cron jobs (%d):\n%s", jobs.size(), formatted);
    }
}