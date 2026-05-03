# 14 - 后台任务与 Cron

> 位于 `io.leavesfly.jharness.agent.tasks` 与 `io.leavesfly.jharness.integration.CronRegistry`。本章讲解 JHarness 的后台长任务管理和定时作业调度两大子系统。

## 1. 子系统概览

| 子系统 | 用途 | 核心类 |
|--------|------|--------|
| **后台任务** | 异步 / 长耗时任务的登记与查询 | `TaskRecord`、`TaskStatus`、`BackgroundTaskManager` |
| **Cron 作业** | 按间隔定时执行的周期任务 | `CronRegistry` |

两者独立：`BackgroundTaskManager` 表示「正在运行什么」，`CronRegistry` 表示「接下来要运行什么」。Cron 被触发后通常会在 `BackgroundTaskManager` 里新增一条 `TaskRecord`。

`BackgroundTaskManager` 实现 `AutoCloseable`，允许 try-with-resources 关闭；线程池大小上限 `MAX_THREAD_POOL_SIZE=20`；使用命名 `ThreadFactory` 便于日志定位；`shutdown` 幂等。

## 2. 后台任务（Task）

### 2.1 `TaskStatus` 状态机

```java
public enum TaskStatus {
    PENDING,     // 已创建，尚未启动
    RUNNING,     // 正在执行
    COMPLETED,   // 成功结束
    FAILED,      // 执行失败
    STOPPED,     // 用户主动停止
    KILLED       // 强制终止（例如超时被 killForcibly）
}
```

合法跃迁：
```
PENDING → RUNNING → (COMPLETED | FAILED | STOPPED | KILLED)
PENDING → STOPPED                              （未启动就被取消）
```

### 2.2 `TaskRecord.TaskType`

```java
public enum TaskType {
    LOCAL_BASH,     // 本地 shell 命令（BashTool 异步模式）
    LOCAL_AGENT,    // fork 的 JHarness 子会话
    REMOTE_AGENT,   // 通过 BridgeSessionManager 远端会话
    IN_PROCESS      // 进程内线程（多用于 Orchestrator 的 SubAgent）
}
```

### 2.3 `TaskRecord` 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `taskId` | `String` | UUID |
| `type` | `TaskType` | 如上 |
| `description` | `String` | 可读描述 |
| `status` | `TaskStatus` | 状态 |
| `createdAt` / `startedAt` / `endedAt` | `Instant` | 时间线 |
| `output` | `String` | 摘要输出（完整输出另存文件） |
| `metadata` | `Map<String,Object>` | 扩展字段（pid、cwd、command 等） |

### 2.4 `BackgroundTaskManager` 典型 API

```java
BackgroundTaskManager tm = new BackgroundTaskManager(...);
tm.setPermissionChecker(checker);                // 与权限系统共享

String id = tm.createTask(type, description, metadata);
tm.startTask(id, runnable);                       // PENDING → RUNNING
tm.completeTask(id, output);                      // → COMPLETED
tm.failTask(id, error);                           // → FAILED
tm.stopTask(id);                                  // → STOPPED
tm.killTask(id);                                  // → KILLED（强杀进程）

TaskRecord rec = tm.getTask(id);
List<TaskRecord> all = tm.listTasks();
List<TaskRecord> active = tm.listActiveTasks();   // PENDING + RUNNING
```

### 2.5 并发模型

- 内部用 `ConcurrentHashMap<String, TaskRecord> tasks` 登记
- `LOCAL_BASH` 由 `ProcessBuilder.start()` fork 子进程，stdout/stderr 由两个 IO 线程消费
- `IN_PROCESS` 交给 `ExecutorService` 跑 `Runnable` / `Callable`
- 超时由 `ScheduledExecutorService` 延迟触发 `stopTask` / `killTask`

### 2.6 `/tasks` 命令

由 `TaskCommandHandler.createTasksCommand` 注册：

- `/tasks` — 列出活跃任务
- `/tasks all` — 全部任务
- `/tasks show <id>` — 详情
- `/tasks stop <id>` — 优雅停止
- `/tasks kill <id>` — 强制终止
- `/tasks clear` — 清理已结束记录

### 2.7 与 BashTool 的集成

`BashTool` 支持两种模式：

- **同步**：阻塞等待命令结束，直接返回输出
- **异步**（`run_in_background=true`）：
  1. 创建 `TaskRecord(type=LOCAL_BASH, status=PENDING)`
  2. `ProcessBuilder` 启动，`startTask(id, ...)` 标 RUNNING
  3. 立即返回 `taskId` 给 LLM
  4. 进程结束回调 → `completeTask` / `failTask`

LLM 后续可通过 `BashOutputTool` 按 `taskId` 查询输出。

## 3. Cron 作业

### 3.1 `CronRegistry` 结构

```java
public class CronRegistry {
    private final Path registryPath;                     // cron_jobs.json
    private final List<Map<String,Object>> jobs;          // synchronizedList
    private final ScheduledExecutorService scheduler;     // 双线程守护池
    private final Map<String, ScheduledFuture<?>> scheduledTasks;
    private CronJobExecutor jobExecutor;                  // 回调接口
}
```

### 3.2 Job JSON 结构

```json
{
  "name": "daily-summary",
  "schedule": "@every 24h",
  "command": "/summary",
  "enabled": true,
  "lastExecuted": "2025-01-15T08:00:00Z"
}
```

### 3.3 `schedule` 支持的格式

当前**仅支持 `@every` 间隔**，不支持标准 cron 五段式表达式：

| 格式 | 含义 |
|------|------|
| `@every 30s` | 每 30 秒 |
| `@every 5m` | 每 5 分钟 |
| `@every 1h` | 每 1 小时 |
| 其他 | 记作「仅手动触发」，`parseScheduleInterval` 返回 -1 |

### 3.4 `CronJobExecutor` 回调

```java
@FunctionalInterface
public interface CronJobExecutor {
    void execute(Map<String,Object> job);
}
```

应用层（通常在 `JHarnessApplication` 启动时）注入：

```java
cronRegistry.setJobExecutor(job -> {
    String cmd = (String) job.get("command");
    taskManager.createAndRunBashTask(cmd, "cron:" + job.get("name"));
});
```

即：Cron 触发 → 调 `jobExecutor` → 应用层把它转为一条 `TaskRecord`。

### 3.5 调度线程工厂

```java
private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
    2,
    new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "jharness-cron-" + counter.incrementAndGet());
            t.setDaemon(true);                              // 守护线程，随 JVM 退出
            t.setUncaughtExceptionHandler((thread, ex) ->
                LoggerFactory.getLogger(CronRegistry.class)
                    .error("cron 调度线程未捕获异常: {}", thread.getName(), ex));
            return t;
        }
    });
```

要点：
- **守护线程**：不阻止 JVM 退出
- **命名**：`jharness-cron-N`，方便日志定位
- **未捕获异常兜底**：避免线程悄悄死掉

### 3.6 `scheduleJob`：单作业调度

```java
long intervalSeconds = parseScheduleInterval(schedule);
if (intervalSeconds <= 0) return;    // 仅手动触发

ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
    try {
        jobExecutor.execute(job);
        markExecuted(name, Instant.now());
    } catch (Exception e) {
        logger.error("cron 作业 {} 执行失败", name, e);
    }
}, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

scheduledTasks.put(name, future);
```

- 首次延迟 = 间隔（避免启动瞬间风暴）
- 失败被 catch 防止 `ScheduledFuture` 链停止

### 3.7 API 速览

```java
CronRegistry reg = new CronRegistry(dataDir.resolve("cron_jobs.json"));
reg.setJobExecutor(executor);

reg.upsertJob(Map.of(
    "name",     "daily-summary",
    "schedule", "@every 24h",
    "command",  "/summary",
    "enabled",  true));

reg.deleteJob("daily-summary");
reg.getJob("daily-summary");
reg.listJobs();
reg.markExecuted("daily-summary", Instant.now());

reg.shutdown();                        // 取消所有 ScheduledFuture + shutdown 线程池
```

### 3.8 原子持久化

`saveJobs()` 使用 **临时文件 + ATOMIC_MOVE** 策略：

```java
Files.createDirectories(registryPath.getParent());
Path tempPath = Files.createTempFile(parent, "cron_jobs", ".json.tmp");
Files.writeString(tempPath, content);
try {
    Files.move(tempPath, registryPath, ATOMIC_MOVE, REPLACE_EXISTING);
} catch (AtomicMoveNotSupportedException amnse) {
    // 跨挂载点降级
    Files.move(tempPath, registryPath, REPLACE_EXISTING);
}
```

并在 catch 里**清理残留临时文件**，防止目录污染。

### 3.9 `/cron` 命令

由 `ConfigPlusCommandHandler.createCronCommand` 注册：

- `/cron list` — 所有作业
- `/cron add <name> <schedule> <command>` — 新建
- `/cron enable <name>` / `/cron disable <name>`
- `/cron run <name>` — 立即触发一次
- `/cron remove <name>`

## 4. 两个子系统的组合流程

```
启动：
  CronRegistry.loadJobs()
  CronRegistry.setJobExecutor(job -> taskManager.createAndRunBashTask(...))
  CronRegistry.scheduleAllEnabledJobs()

运行：
  每到间隔 → scheduler 触发 →
    jobExecutor.execute(job) →
      bgTaskManager.createTask(LOCAL_BASH, "cron:" + name, meta)
      bgTaskManager.startTask(id, processRunnable)
      （异步完成后 completeTask / failTask）
    cronRegistry.markExecuted(name, now)

用户查询：
  /tasks        → BackgroundTaskManager.listTasks
  /cron list    → CronRegistry.listJobs

关闭：
  CronRegistry.shutdown()
  BackgroundTaskManager.close()    // AutoCloseable，幂等
```

## 5. 最佳实践

- Cron 作业命令尽量走 **斜杠命令**（如 `/summary`）而非裸 shell，可复用权限系统
- 长耗时任务优先用 **异步 BashTool**，让用户在其运行时继续与 Agent 交互
- `/tasks clear` 定期清理，避免内存/磁盘里累积过多 `TaskRecord`
- 只需手动触发的任务，把 `schedule` 留空或填非 `@every` 表达式，`/cron run` 启动

---

## 🔗 相关文档

- [06-工具系统](06-工具系统.md) — BashTool / BashOutputTool
- [13-多智能体协调](13-多智能体协调.md) — `IN_PROCESS` 类型任务的使用场景
- [07-斜杠命令系统](07-斜杠命令系统.md) — `/tasks` / `/cron` 命令
