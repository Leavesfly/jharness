package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Todo 写入工具输入
 */
public class TodoWriteToolInput {
    @NotEmpty(message = "todos 不能为空")
    private List<TodoItem> todos;

    public List<TodoItem> getTodos() {
        return todos;
    }

    public void setTodos(List<TodoItem> todos) {
        this.todos = todos;
    }

    public static class TodoItem {
        private String content;
        private String status = "pending";

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
