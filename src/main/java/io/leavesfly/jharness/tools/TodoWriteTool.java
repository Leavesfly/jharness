package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.TodoWriteToolInput;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Todo 列表管理工具
 *
 * 创建和更新任务列表，帮助跟踪复杂任务的进度。
 *
 * 并发策略（P1-L2）：
 * - 使用 AtomicReference 存储不可变快照，替代 volatile + 可变 List，
 *   避免外部读取到正在被 LLM 写入过程中的半更新列表；
 * - execute 时先拷贝为不可变 List，再原子替换引用；
 * - getCurrentTodos 返回的始终是当时读到的完整快照。
 */
public class TodoWriteTool extends BaseTool<TodoWriteToolInput> {
    private static final AtomicReference<List<TodoWriteToolInput.TodoItem>> CURRENT_TODOS =
            new AtomicReference<>(Collections.emptyList());

    @Override
    public String getName() {
        return "todo_write";
    }

    @Override
    public String getDescription() {
        return "创建和更新任务列表。用于跟踪复杂多步骤任务的进度。";
    }

    @Override
    public Class<TodoWriteToolInput> getInputClass() {
        return TodoWriteToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(TodoWriteToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            List<TodoWriteToolInput.TodoItem> incoming = input.getTodos();
            List<TodoWriteToolInput.TodoItem> snapshot = (incoming == null)
                    ? Collections.emptyList()
                    : List.copyOf(incoming);
            CURRENT_TODOS.set(snapshot);

            String output = snapshot.stream()
                    .map(todo -> {
                        String statusIcon = switch (todo.getStatus()) {
                            case "completed" -> "✅";
                            case "in_progress" -> "🔄";
                            default -> "⬜";
                        };
                        return statusIcon + " " + todo.getContent();
                    })
                    .collect(Collectors.joining("\n"));

            return ToolResult.success("任务列表已更新:\n" + output);
        });
    }

    /**
     * 获取当前任务列表（返回不可变快照）
     */
    public static List<TodoWriteToolInput.TodoItem> getCurrentTodos() {
        return CURRENT_TODOS.get();
    }
}
