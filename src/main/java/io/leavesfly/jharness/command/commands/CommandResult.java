package io.leavesfly.jharness.command.commands;

import io.leavesfly.jharness.core.engine.model.ConversationMessage;

import java.util.List;

/**
 * 命令执行结果
 */
public class CommandResult {
    private final String message;
    private final List<ConversationMessage> replayMessages;
    private final boolean continueLoop;

    public CommandResult(String message) {
        this(message, List.of(), true);
    }

    public CommandResult(String message, List<ConversationMessage> replayMessages, boolean continueLoop) {
        this.message = message;
        this.replayMessages = replayMessages;
        this.continueLoop = continueLoop;
    }

    public String getMessage() {
        return message;
    }

    public List<ConversationMessage> getReplayMessages() {
        return replayMessages;
    }

    public boolean shouldContinueLoop() {
        return continueLoop;
    }

    public static CommandResult success(String message) {
        return new CommandResult(message);
    }

    public static CommandResult withReplay(String message, List<ConversationMessage> messages) {
        return new CommandResult(message, messages, true);
    }

    public static CommandResult exit(String message) {
        return new CommandResult(message, List.of(), false);
    }

    public static CommandResult error(String message) {
        return new CommandResult("错误: " + message, List.of(), false);
    }
}
