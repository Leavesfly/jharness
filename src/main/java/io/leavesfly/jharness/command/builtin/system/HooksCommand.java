package io.leavesfly.jharness.command.builtin.system;

import io.leavesfly.jharness.capability.hook.HookRegistry;
import io.leavesfly.jharness.command.CommandResult;
import io.leavesfly.jharness.command.SlashCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static io.leavesfly.jharness.command.builtin.system.SystemCommandSupport.cmd;
import static io.leavesfly.jharness.command.builtin.system.SystemCommandSupport.joinArgs;

/**
 * /hooks - 查看 hooks（两种模式）：
 *   - {@link #create()} 仅扫描 .openharness/hooks 目录；
 *   - {@link #createWithRegistry(HookRegistry)} 走 HookRegistry summary。
 */
public final class HooksCommand {

    private HooksCommand() {}

    public static SlashCommand create() {
        return cmd("hooks", "查看 hooks", (args, ctx, ec) -> {
            Path hooksDir = ctx.getCwd().resolve(".openharness/hooks");
            Path globalHooksDir = Path.of(System.getProperty("user.home"), ".openharness/hooks");

            StringBuilder sb = new StringBuilder("Hooks 状态:\n");
            sb.append("  项目 hooks 目录: ").append(hooksDir).append("\n");
            sb.append("  全局 hooks 目录: ").append(globalHooksDir).append("\n");

            if (Files.exists(hooksDir)) {
                sb.append("  项目 hooks: 已配置\n");
                try (var stream = Files.list(hooksDir)) {
                    long count = stream.count();
                    sb.append("    文件数: ").append(count);
                } catch (Exception e) {
                    sb.append("    读取失败");
                }
            } else {
                sb.append("  项目 hooks: 未配置");
            }
            return CompletableFuture.completedFuture(CommandResult.success(sb.toString()));
        });
    }

    public static SlashCommand createWithRegistry(HookRegistry hookRegistry) {
        return cmd("hooks", "查看 hooks", (args, ctx, ec) -> {
            String joined = joinArgs(args);
            String[] parts = joined.isEmpty() ? new String[0] : joined.split("\\s+");
            String subcmd = parts.length > 0 ? parts[0] : "status";

            CommandResult result = switch (subcmd) {
                case "status", "list", "ls" -> CommandResult.success(hookRegistry.summary());
                default -> CommandResult.error("未知子命令: " + subcmd + "\n可用: status, list");
            };
            return CompletableFuture.completedFuture(result);
        });
    }
}
