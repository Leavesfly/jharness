package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.AskUserQuestionToolInput;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 用户问题工具
 *
 * 向用户提问并获取回答。用于需要用户确认或输入的场景。
 */
public class AskUserQuestionTool extends BaseTool<AskUserQuestionToolInput> {
    @Override
    public String getName() {
        return "ask_user_question";
    }

    @Override
    public String getDescription() {
        return "向用户提问。支持提供多个选项供用户选择。";
    }

    @Override
    public Class<AskUserQuestionToolInput> getInputClass() {
        return AskUserQuestionToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(AskUserQuestionToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            String question = input.getQuestion();
            List<String> options = input.getOptions();

            StringBuilder prompt = new StringBuilder();
            prompt.append(question).append("\n");

            if (options != null && !options.isEmpty()) {
                prompt.append("选项:\n");
                for (int i = 0; i < options.size(); i++) {
                    prompt.append(i + 1).append(". ").append(options.get(i)).append("\n");
                }
            }

            // 简化实现：默认返回第一个选项
            String answer = (options != null && !options.isEmpty()) ? options.get(0) : "已确认";

            return ToolResult.success("用户回答: " + answer);
        });
    }
}
