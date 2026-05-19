package io.leavesfly.jharness.command.commands.builtin.system;

import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;

import java.util.List;

/**
 * /system 域命令共享的小工具：构造 SimpleSlashCommand、拼接参数、推断项目名。
 *
 * 抽自原 SystemCommandHandler，让 8 个子命令独立类不再重复同一组私有 helper。
 */
final class SystemCommandSupport {

    private SystemCommandSupport() {}

    static String joinArgs(List<String> args) {
        return args == null || args.isEmpty() ? "" : String.join(" ", args);
    }

    static SimpleSlashCommand cmd(String name, String desc, SimpleSlashCommand.CommandHandler handler) {
        return new SimpleSlashCommand(name, desc, handler);
    }

    static String getProjectName(CommandContext ctx) {
        return ctx.getCwd().getFileName() != null ? ctx.getCwd().getFileName().toString() : "default";
    }
}
