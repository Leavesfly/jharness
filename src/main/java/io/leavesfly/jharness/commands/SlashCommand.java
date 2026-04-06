package io.leavesfly.jharness.commands;

import io.leavesfly.jharness.engine.model.ConversationMessage;
import io.leavesfly.jharness.engine.stream.StreamEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 斜杠命令接口
 *
 * 所有命令处理器必须实现此接口。
 */
public interface SlashCommand {
    /**
     * 获取命令名称（不含斜杠）
     */
    String getName();

    /**
     * 获取命令描述
     */
    String getDescription();

    /**
     * 执行命令
     *
     * @param args            命令参数
     * @param context         命令上下文
     * @param eventConsumer   事件消费者
     * @return 执行结果
     */
    CompletableFuture<CommandResult> execute(List<String> args, CommandContext context, Consumer<StreamEvent> eventConsumer);
}
