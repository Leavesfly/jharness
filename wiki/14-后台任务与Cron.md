# 14 · 后台任务与 Cron

> 包路径：
> - 后台任务：`io.leavesfly.jharness.capability.task`
> - 定时任务：`io.leavesfly.jharness.integration.cron`

两套独立机制：

- **后台任务（`BackgroundTaskManager`）** — 即时启动的长运行任务（Shell / 子进程 Agent），生命周期挂在 JHarness 进程上
- **定时任务（`CronRegistry`）** — 按 Cron 表达式周期性触发的作业，配置持久化到磁盘

---

## 1. 后台任务（BackgroundTaskManager）

### 1.1 类设计

```java
public class BackgroundTaskManager implements AutoCloseable {
    private static final int MAX_THREAD_POOL_SIZE = 20;

    private final Map<String, TaskRecord> tasks;        // taskId → 记录
    private final Map<String, Process> processes;       // taskId → 子进程
    private final ExecutorService executor;             // 有界线程池
    private final Path outputDir;                       // 输出文件目录
    private volatile boolean closed;
    private volatile PermissionChecker permissionChecker;
}
```

### 1.2 任务记录（TaskRecord）

```java
public class TaskRecord {
    public enum TaskType {
        LOCAL_BASH,       // 本地 shell 命令
        LOCAL_AGENT,      // 本地子进程 Agent
        REMOTE_AGENT,     // 远程 Agent（保留）
        IN_PROCESS        // 进程内（保留）
    }

    String id;                   // 8 位 UUID 前缀
    TaskType type;
    String command;
    String description;
    String prompt;               // Agent 任务的 prompt
    Path cwd;
    TaskStatus status;
    Map<String, String> metadata;
    Instant startedAt;
    Instant endedAt;
    Integer exitCode;
    String model;
}
```

`TaskStatus` 枚举：`PENDING / RUNNING / COMPLETED / FAILED / STOPPED / KILLED`。

### 1.3 主要 API

```java
TaskRecord createShellTask(String command, String description, Path cwd);
TaskRecord createAgentTask(String prompt, String description, Path cwd, String model, String apiKey);
TaskRecord getTask(String taskId);
List<TaskRecord> listTasks(TaskStatus status);
boolean stopTask(String taskId);        // 优雅停止（SIGTERM 后等 5s 再 SIGKILL）
boolean killTask(String taskId);        // 强杀（SIGKILL）
String readTaskOutput(String taskId);   // 读取 stdout/stderr
boolean writeToTask(String taskId, String message);  // 向 Agent 任务 stdin 写入
void shutdown();                        // 关闭线程池 + 终止所有进程
@Override void close();                 // 调 shutdown()
```

### 1.4 输出文件

每个任务的 stdout / stderr 重定向到：

```
<outputDir>/<taskId>.log
```

`outputDir` 默认 `~/.jharness/data/tasks/`，由 `BackgroundTaskManager` 构造时创建。

### 1.5 线程池配置

```java
this.executor = Executors.newFixedThreadPool(MAX_THREAD_POOL_SIZE, namedThreadFactory("jharness-bg-task-"));
```

- 固定大小 20 线程
- 命名 ThreadFactory，便于 `jstack` / 日志定位
- `daemon=false`：JVM 退出时等任务完成
- `UncaughtExceptionHandler` 记录未捕获异常

### 1.6 权限闸门

`setPermissionChecker(checker)` 注入后，每次创建 Shell / Agent 任务在 fork 子进程前先评估：

```java
PermissionDecision decision = permissionChecker.evaluate(
    "bash", false, null, command);
if (decision != null && !decision.isAllowed() && !decision.isRequiresConfirmation()) {
    task.setStatus(TaskStatus.FAILED);
    return task;   // 不 fork 子进程
}
```

工具名固定用 `"bash"`，与前台 `BashTool.getName()` 对齐，使权限规则前后台一致生效。

### 1.7 优雅关闭

```java
public synchronized void shutdown() {
    if (closed) return;     // 幂等
    closed = true;
    // 1. 停止接收新任务
    executor.shutdown();
    // 2. 给所有进程发送 SIGTERM
    processes.values().forEach(Process::destroy);
    // 3. 等待 5 秒
    try {
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            // 4. 强杀
            executor.shutdownNow();
            processes.values().forEach(Process::destroyForcibly);
        }
    } catch (InterruptedException ignored) { /* ... */ }
}
```

`close()` 委托给 `shutdown()`，支持 try-with-resources。

### 1.8 task 系列工具

LLM 通过以下工具操作后台任务：

| 工具 | 行为 |
|------|------|
| `task_create` | 创建任务（type=shell/agent） |
| `task_get` | 查询单个任务 |
| `task_list` | 列出全部 |
| `task_stop` | 优雅停止 |
| `task_output` | 读 stdout/stderr |
| `task_update` | 更新 metadata |

---

## 2. 定时任务（CronRegistry）

### 2.1 类设计

```java
public class CronRegistry implements AutoCloseable {
    private final Path registryPath;     // ~/.jharness/data/cron_jobs.json
    private final List<Map<String, Object>> jobs;   // 同步 List
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> scheduledTasks;  // 并发 Map
    private final Object jobsLock;
    private volatile boolean closed;
    private CronJobExecutor jobExecutor;
}
```

### 2.2 作业数据模型

每个作业是一个 `Map<String, Object>`，约定字段：

```json
{
  "name": "daily-summary",
  "description": "每日总结",
  "cron": "0 0 18 * * ?",
  "enabled": true,
  "type": "agent",
  "prompt": "请总结今日工作内容",
  "model": "deepseek-chat",
  "lastExecutedAt": "2026-05-19T18:00:00Z",
  "createdAt": "2026-05-01T10:00:00Z"
}
```

**Cron 表达式**：支持标准 6 段格式（秒 分 时 日 月 周）。

### 2.3 持久化

```
~/.jharness/data/cron_jobs.json
```

启动时自动加载，每次 `upsertJob` / `deleteJob` / `markExecuted` 立即写盘。

### 2.4 调度器配置

```java
new ScheduledThreadPoolExecutor(2, namedDaemonThreadFactory("jharness-cron-"))
```

- 池大小 2 线程
- `daemon=true`：JVM 关闭时不阻塞退出（与后台任务相反）
- 名称 `jharness-cron-N`

### 2.5 主要 API

```java
public CronRegistry(Path dataDir);
public void setJobExecutor(CronJobExecutor executor);   // 设置后启动调度
public void upsertJob(Map<String, Object> job);          // 增 / 改
public boolean deleteJob(String name);
public Map<String, Object> getJob(String name);
public List<Map<String, Object>> listJobs();
public void markExecuted(String name, Instant executedAt);
public String getSummary();
public synchronized void shutdown();                     // 幂等
@Override public void close();
```

`CronJobExecutor` 是函数式接口：

```java
@FunctionalInterface
public interface CronJobExecutor {
    void execute(Map<String, Object> job);
}
```

通常注入"按 prompt 调 QueryEngine"的 lambda：

```java
cronRegistry.setJobExecutor(job -> {
    String prompt = (String) job.get("prompt");
    queryEngine.submitMessage(prompt, ev -> {}).join();
    cronRegistry.markExecuted((String) job.get("name"), Instant.now());
});
```

### 2.6 并发安全

历史 bug：旧实现用 `HashMap` 却在 `synchronized(jobsLock)` 之外访问 `scheduledTasks`，存在数据竞争。

**修复**：

- `scheduledTasks` 改用 `ConcurrentHashMap`
- `jobs` 改用 `Collections.synchronizedList(new ArrayList<>())`
- 复合操作仍在 `synchronized(jobsLock)` 块内

### 2.7 cron 系列工具

| 工具 | 行为 |
|------|------|
| `cron_create` | 创建定时任务 |
| `cron_list` | 列出全部 |
| `cron_delete` | 删除 |
| `remote_trigger` | 按需立即触发一次（不影响调度） |

---

## 3. `/tasks` 与 `/cron` 命令

| 命令 | 行为 |
|------|------|
| `/tasks` | 列出全部后台任务（带状态） |
| `/tasks <id>` | 查看任务详情 |
| `/cron list` | 列出全部定时任务 |
| `/cron add` | 添加（交互式） |
| `/cron remove <name>` | 删除 |
| `/cron summary` | 摘要 |

---

## 4. 关闭顺序

`QueryEngineBuilder` 注册的 close hook：

```java
engine.registerCloseHook(() -> {
    mcp.close();           // 1. 关 MCP
    cron.close();          // 2. 关 cron
    task.shutdown();       // 3. 关后台任务
    apiClient.cancelAllActiveRequests();  // 4. 取消 LLM 请求
});
```

所有 `close` / `shutdown` 都是**幂等**的，重复调用不抛异常。

---

## 5. 安全注意事项

1. **PLAN 模式下** 创建 Shell 任务会被权限闸门拒绝
2. **Cron 命令** 同样走权限评估（通过 `jobExecutor` 内的 `queryEngine.submitMessage` → 工具调用 → 权限检查）
3. **后台 Agent 任务** 用独立 API Key 时，需要确保 Key 不被日志泄漏
4. **任务输出文件** 在用户目录下，注意敏感信息

---

## 6. 关键类清单

| 类 | 文件 | 行数 | 职责 |
|----|------|------|------|
| `BackgroundTaskManager` | `task/BackgroundTaskManager.java` | 348 | 后台任务管理 + 进程跟踪 |
| `TaskRecord` | `task/TaskRecord.java` | 80 | 任务数据模型 |
| `TaskStatus` | `task/TaskStatus.java` | 14 | 状态枚举 |
| `CronRegistry` | `cron/CronRegistry.java` | 411 | 定时任务调度 + 持久化 |
| `CronRegistry.CronJobExecutor` | 内部接口 | — | 作业执行器 |
| `TaskCreateTool` 等 | `tools/builtin/task/...` | — | task 工具 |
| `CronCreateTool` 等 | `tools/builtin/cron/...` | — | cron 工具 |

---

## 7. 下一步

- 多 Agent 协调 → [13-多智能体协调](13-多智能体协调.md)
- 工具调用权限 → [08-权限系统](08-权限系统.md)
- 任务输出文件读取 → [06-工具系统 § 7.5](06-工具系统.md#75-任务管理-builtintask)
