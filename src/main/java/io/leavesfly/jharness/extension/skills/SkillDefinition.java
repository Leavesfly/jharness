package io.leavesfly.jharness.extension.skills;

/**
 * 技能定义
 *
 * 表示一个可加载的技能，包含名称、描述和内容。
 */
public class SkillDefinition {
    private final String name;
    private final String description;
    private final String content;
    private final String source;

    public SkillDefinition(String name, String description, String content, String source) {
        this.name = name;
        this.description = description;
        this.content = content;
        this.source = source;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getContent() {
        return content;
    }

    public String getSource() {
        return source;
    }

    @Override
    public String toString() {
        return "SkillDefinition{name='" + name + "', description='" + description + "', source='" + source + "'}";
    }
}
