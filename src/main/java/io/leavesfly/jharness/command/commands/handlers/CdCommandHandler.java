package io.leavesfly.jharness.command.commands.handlers;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.core.engine.QueryEngine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

/**
 * /cd 命令处理器
 *
 * 支持在会话中动态切换工作目录。
 */
public class CdCommandHandler {

    /**
     * 创建 /cd 斜杠命令
     *
     * 用法：
     *   /cd <path>    — 切换到指定目录（支持绝对路径和相对路径）
     *   /cd           — 显示当前工作目录
     */
    public static SlashCommand createCdCommand() {
        return new SimpleSlashCommand("cd", "切换工作目录", (args, context, eventConsumer) ->
                CompletableFuture.supplyAsync(() -> {
                    QueryEngine engine = context.getEngine();
                    if (engine == null) {
                        return CommandResult.success("错误: 引擎未初始化，无法切换工作目录");
                    }

                    if (args.isEmpty()) {
                        return CommandResult.success("当前工作目录: " + engine.getCwd());
                    }

                    String targetPath = String.join(" ", args);
                    try {
                        Path newCwd = resolveTargetPath(engine.getCwd(), targetPath);
                        engine.setCwd(newCwd);
                        return CommandResult.success("工作目录已切换到: " + engine.getCwd());
                    } catch (IllegalArgumentException e) {
                        return CommandResult.success("切换失败: " + e.getMessage());
                    }
                })
        );
    }

    /**
     * 解析目标路径，支持绝对路径、相对路径和 ~ 家目录
     */
    private static Path resolveTargetPath(Path currentCwd, String targetPath) {
        if (targetPath.startsWith("~")) {
            String home = System.getProperty("user.home");
            targetPath = targetPath.replaceFirst("^~", home);
        }

        Path target = Paths.get(targetPath);
        if (target.isAbsolute()) {
            return target;
        }
        return currentCwd.resolve(target);
    }
}
