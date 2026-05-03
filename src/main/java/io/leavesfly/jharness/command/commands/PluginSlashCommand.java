package io.leavesfly.jharness.command.commands;

import io.leavesfly.jharness.core.engine.stream.StreamEvent;
import io.leavesfly.jharness.extension.skills.SkillDefinition;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【P0-1】由 plugin 的 {@code commands/*.md} 注册而成的 slash command。
 *
 * <p>对齐 Claude Code 的 plugin slash command 语义：把 Markdown 正文作为 prompt 模板，
 * 用户输入的 args 会按占位符注入后作为一次新的用户消息提交给 {@link io.leavesfly.jharness.core.engine.QueryEngine}。
 *
 * <h3>占位符规则（尽量与 Claude Code 规范保持一致）</h3>
 * <ul>
 *   <li>{@code $ARGUMENTS} 或 {@code {{args}}} → 整串 args（空格拼接）</li>
 *   <li>{@code $1} / {@code $2} / ... → 按位置取 args 中的第 i 个（支持 $1..$99，越界替换为空串）</li>
 * </ul>
 * 未提供对应 arg 的占位符会被替换为空串。
 */
public class PluginSlashCommand implements SlashCommand {

    /**
     * 数字占位符匹配：{@code $<n>}，n 可为任意长度。
     * 使用一次性正则替换，避免"{@code $1} 先于 {@code $10} 被替换"导致的字符串腐蚀。
     * 例如模板 {@code "$10"} + args={@code ["a"]}：
     * <ul>
     *   <li>错误实现：{@code $1} 先被替换成 {@code "a"}，结果变 {@code "a0"}；</li>
     *   <li>本实现：正则一次性按整数 10 处理，args 未提供第 10 个时替换为空串。</li>
     * </ul>
     */
    private static final Pattern NUMERIC_PLACEHOLDER = Pattern.compile("\\$(\\d+)");

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
     * 渲染 prompt 模板，支持 {@code $ARGUMENTS} / {@code {{args}}} / {@code $<n>} 占位。
     *
     * <p>暴露为 public 以便单元测试直接校验渲染行为（也方便上层工具链做 dry-run）。
     *
     * <p><b>【修复】</b>{@code $1..$9} 的旧实现使用循环 {@code out.replace("$i", ...)}，
     * 会被 {@code $10/$11/...} 碰撞（{@code $10} 被先替换成 {@code "<arg1>0"}）。
     * 新实现用一次性正则 {@link #NUMERIC_PLACEHOLDER} 按整数解析位置，消除碰撞。
     */
    public static String renderTemplate(String template, List<String> args) {
        if (template == null) return "";
        String joined = (args == null || args.isEmpty()) ? "" : String.join(" ", args);

        // 先替换具名占位符（这两个不会与数字占位符冲突）
        String out = template
                .replace("$ARGUMENTS", joined)
                .replace("{{args}}", joined);

        // 再用正则一次性替换所有 $<n> 占位符，避免 $1 先于 $10 被替换
        Matcher m = NUMERIC_PLACEHOLDER.matcher(out);
        StringBuilder sb = new StringBuilder(out.length());
        while (m.find()) {
            int idx;
            try {
                idx = Integer.parseInt(m.group(1));
            } catch (NumberFormatException nfe) {
                // 数字过大无法解析：替换为空串，保持与"越界"行为一致
                idx = Integer.MAX_VALUE;
            }
            String val = (args != null && idx >= 1 && idx <= args.size())
                    ? args.get(idx - 1)
                    : "";
            // appendReplacement 会把 val 中的 $ 当作反向引用，必须先 quote
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
