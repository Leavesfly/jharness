package io.leavesfly.jharness.prompts;

import io.leavesfly.jharness.skills.SkillDefinition;
import io.leavesfly.jharness.skills.SkillRegistry;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 系统提示构建器
 *
 * 组装完整的系统提示，包含基础指令、环境信息、可用技能等。
 */
public class SystemPromptBuilder {
    private final String basePrompt;
    private SkillRegistry skillRegistry;

    public SystemPromptBuilder(String basePrompt) {
        this.basePrompt = basePrompt;
    }

    public SystemPromptBuilder withSkills(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        return this;
    }

    /**
     * 构建完整的系统提示
     */
    public String build() {
        StringBuilder prompt = new StringBuilder();

        // 基础提示
        prompt.append(basePrompt).append("\n\n");

        // 环境信息
        prompt.append("## 环境信息\n");
        prompt.append("- 操作系统: ").append(System.getProperty("os.name")).append("\n");
        prompt.append("- 工作目录: ").append(System.getProperty("user.dir")).append("\n");
        prompt.append("- 日期: ").append(LocalDate.now().format(DateTimeFormatter.ISO_DATE)).append("\n\n");

        // 可用技能
        if (skillRegistry != null && !skillRegistry.getAllSkills().isEmpty()) {
            prompt.append("## 可用技能\n");
            for (SkillDefinition skill : skillRegistry.getAllSkills()) {
                prompt.append("- ").append(skill.getName()).append(": ").append(skill.getDescription()).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("## 工具使用指南\n");
        prompt.append("- 你可以使用提供的工具来完成任务\n");
        prompt.append("- 每次只能使用一个工具，等待结果后再继续\n");
        prompt.append("- 如果不确定，请先询问用户\n");

        return prompt.toString();
    }
}
