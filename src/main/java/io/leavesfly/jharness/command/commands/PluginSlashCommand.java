package io.leavesfly.jharness.command.commands;

import io.leavesfly.jharness.core.engine.stream.StreamEvent;
import io.leavesfly.jharness.extension.skills.SkillDefinition;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 【P0-1】由 plugin 的 {@code commands/*.md} 注册而成的 slash command。
 *
 * <p>对齐 Claude Code 的 plugin slash command 语义：把 Markdown 正文作为 prompt 模板，
 * 用户输入的 args 会按占位符注入后作为一次新的用户消息提交给 {@link io.leavesfly.jharness.core.engine.QueryEngine}。
 *
 * <h3>占位符规则（尽量与 Claude Code 规范保持一致）</h3>
 * <ul>
 *   <li>{@code $ARGUMENTS} 或 {@code {{args}}} → 整串 args（空格拼接）</li>
 *   <li>{@code $1} / {@code $2} / ... → 按位置取 args 中的第 i 个</li>
 * </ul>
 * 未提供对应 arg 的占位符会被替换为空串。
 */
public class PluginSlashCommand implements SlashCommand {

    private final String name;
    private final String description;
    /** 插件来源名，用于展示 / 调试。null 表示未知。 */
    private final String pluginName;
    /** Markdown 正文作为 prompt 模板。 */
    private final String promptTemplate;

    public PluginSlashCommand(String name, String description, String pluginName, String promptTemplate) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("plugin command name 不能为空");
        }
        this.name = name;
        this.description = description == null ? "" : description;
        this.pluginName = pluginName;
        this.promptTemplate = promptTemplate == null ? "" : promptTemplate;
    }

    /** 便捷构造：直接基于 SkillDefinition 产出。 */
    public static PluginSlashCommand fromSkill(SkillDefinition def, String pluginName) {
        return new PluginSlashCommand(def.getName(), def.getDescription(), pluginName, def.getContent());
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        String prefix = (pluginName == null || pluginName.isBlank()) ? "[plugin] " : ("[plugin:" + pluginName + "] ");
        return prefix + description;
    }

    public String getPluginName() {
        return pluginName;
    }

    public String getPromptTemplate() {
        return promptTemplate;
    }

    @Override
    public CompletableFuture<CommandResult> execute(List<String> args,
                                                    CommandContext context,
                                                    Consumer<StreamEvent> eventConsumer) {
        if (context == null || context.getEngine() == null) {
            return CompletableFuture.completedFuture(
                    CommandResult.error("QueryEngine 未初始化，无法执行 plugin 命令: " + name));
        }

        String rendered = renderTemplate(promptTemplate, args);
        if (rendered.isBlank()) {
            return CompletableFuture.completedFuture(
                    CommandResult.error("plugin 命令模板渲染后为空: " + name));
        }

        // 把渲染后的 prompt 作为一次新的 user 消息提交给 QueryEngine，事件直通给上层。
        Consumer<StreamEvent> ec = eventConsumer != null ? eventConsumer : (e -> {});
        return context.getEngine()
                .submitMessage(rendered, ec)
                .handle((v, err) -> {
                    if (err != null) {
                        return CommandResult.error("plugin 命令执行失败: " + err.getMessage());
                    }
                    return CommandResult.success("plugin 命令 /" + name + " 已执行完成");
                });
    }

    /**
     * 渲染 prompt 模板，支持 $ARGUMENTS / {{args}} / $1..$9 占位。
     *
     * <p>暴露为 public 以便单元测试直接校验渲染行为（也方便上层工具链做 dry-run）。
     */
    public static String renderTemplate(String template, List<String> args) {
        if (template == null) return "";
        String joined = (args == null || args.isEmpty()) ? "" : String.join(" ", args);

        String out = template
                .replace("$ARGUMENTS", joined)
                .replace("{{args}}", joined);

        // $1..$9 按位置替换；越界替换为空串
        for (int i = 1; i <= 9; i++) {
            String placeholder = "$" + i;
            if (!out.contains(placeholder)) continue;
            String val = (args != null && args.size() >= i) ? args.get(i - 1) : "";
            out = out.replace(placeholder, val);
        }
        return out;
    }
}
