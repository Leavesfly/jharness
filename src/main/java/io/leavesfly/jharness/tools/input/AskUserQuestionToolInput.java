package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 用户问题工具输入
 */
public class AskUserQuestionToolInput {
    @NotBlank(message = "question 不能为空")
    private String question;

    private List<String> options;
    private String defaultOption;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<String> getOptions() {
        return options;
    }

    public void setOptions(List<String> options) {
        this.options = options;
    }

    public String getDefaultOption() {
        return defaultOption;
    }

    public void setDefaultOption(String defaultOption) {
        this.defaultOption = defaultOption;
    }
}
