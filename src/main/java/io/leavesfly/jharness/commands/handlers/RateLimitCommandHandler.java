package io.leavesfly.jharness.commands.handlers;

import io.leavesfly.jharness.commands.CommandContext;
import io.leavesfly.jharness.commands.CommandResult;
import io.leavesfly.jharness.commands.SimpleSlashCommand;
import io.leavesfly.jharness.commands.SlashCommand;
import io.leavesfly.jharness.config.Settings;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 限流选项命令处理器
 *
 * 显示减少提供商速率限制的方法
 */
public class RateLimitCommandHandler {

    public static SlashCommand createRateLimitCommand() {
        return new SimpleSlashCommand("rate-limit-options", "显示减少提供商速率限制的方法", (args, ctx, ec) -> {
            try {
                Settings settings = Settings.load();
                String provider = detectProvider(settings);

                String message = String.join("\n", List.of(
                    "限流选项:",
                    "- 提供商: " + provider,
                    "- 减少 /passes 或切换 /effort low 以减少请求量",
                    "- 启用 /fast 以获得更短的响应和更少的工具调用",
                    "- 使用 /compact 在重试前缩短长对话记录",
                    "- 优先使用后台 /tasks 进行长时间运行的本地工作",
                    "- 考虑使用本地缓存和 MCP 资源减少 API 调用"
                ));
                return CompletableFuture.completedFuture(CommandResult.success(message));
            } catch (Exception e) {
                return CompletableFuture.completedFuture(CommandResult.error("限流选项命令失败: " + e.getMessage()));
            }
        });
    }

    private static String detectProvider(Settings settings) {
        if (settings.getBaseUrl() != null && settings.getBaseUrl().contains("moonshot")) {
            return "moonshot-compatible";
        }
        return "anthropic-compatible";
    }
}
