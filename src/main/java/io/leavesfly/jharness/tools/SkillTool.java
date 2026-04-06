package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.skills.SkillDefinition;
import io.leavesfly.jharness.skills.SkillRegistry;
import io.leavesfly.jharness.tools.input.SkillToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 技能工具
 *
 * 查询和加载技能内容。
 */
public class SkillTool extends BaseTool<SkillToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(SkillTool.class);
    private final SkillRegistry skillRegistry;

    public SkillTool(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String getName() {
        return "skill";
    }

    @Override
    public String getDescription() {
        return "查询可用技能或加载指定技能的详细内容。";
    }

    @Override
    public Class<SkillToolInput> getInputClass() {
        return SkillToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(SkillToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            if (input.getSkill() == null || input.getSkill().isEmpty()) {
                // 列出所有技能
                String skillsList = skillRegistry.getAllSkills().stream()
                        .map(skill -> "- " + skill.getName() + ": " + skill.getDescription())
                        .collect(Collectors.joining("\n"));
                return ToolResult.success("可用技能:\n" + skillsList);
            }

            // 加载指定技能
            SkillDefinition skill = skillRegistry.get(input.getSkill());
            if (skill == null) {
                return ToolResult.error("技能不存在: " + input.getSkill());
            }

            return ToolResult.success(skill.getContent());
        });
    }

    @Override
    public boolean isReadOnly(SkillToolInput input) {
        return true;
    }
}
