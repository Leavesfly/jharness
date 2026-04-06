package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.TodoWriteToolInput;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Todo 列表管理工具
 *
 * 创建和更新任务列表，帮助跟踪复杂任务的进度。
 */
public class TodoWriteTool extends BaseTool<TodoWriteToolInput> {
    private static volatile List<TodoWriteToolInput.TodoItem> currentTodos;

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
            currentTodos = input.getTodos();

            // 格式化输出
            String output = currentTodos.stream()
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
     * 获取当前任务列表
     */
    public static List<TodoWriteToolInput.TodoItem> getCurrentTodos() {
        return currentTodos;
    }
}
