package io.leavesfly.jharness.commands.handlers;

import io.leavesfly.jharness.commands.CommandContext;
import io.leavesfly.jharness.commands.CommandResult;
import io.leavesfly.jharness.commands.SimpleSlashCommand;
import io.leavesfly.jharness.commands.SlashCommand;
import io.leavesfly.jharness.config.Settings;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 隐私设置命令处理器
 *
 * 显示本地隐私和存储设置
 */
public class PrivacyCommandHandler {

    public static SlashCommand createPrivacyCommand() {
        return new SimpleSlashCommand("privacy-settings", "显示本地隐私和存储设置", (args, ctx, ec) -> {
            try {
                Settings settings = Settings.load();
                Path cwd = ctx.getCwd();

                String userConfigDir = System.getProperty("user.home") + "/.jharness";
                String projectConfigDir = cwd.resolve(".jharness").toString();
                String sessionDir = cwd.resolve(".jharness").resolve("sessions").toString();
                String feedbackLog = System.getProperty("user.home") + "/.jharness/feedback.log";

                String message = String.join("\n", List.of(
                    "隐私设置:",
                    "- 用户配置目录: " + userConfigDir,
                    "- 项目配置目录: " + projectConfigDir,
                    "- 会话目录: " + sessionDir,
                    "- 反馈日志: " + feedbackLog,
                    "- API 地址: " + (settings.getBaseUrl() != null ? settings.getBaseUrl() : "(默认 Anthropic 兼容端点)"),
                    "- 网络: 仅为 provider 和显式的 web/MCP 调用启用",
                    "- 存储: 本地文件在 ~/.jharness 和项目 .jharness 下"
                ));

                return CompletableFuture.completedFuture(CommandResult.success(message));
            } catch (Exception e) {
                return CompletableFuture.completedFuture(CommandResult.error("隐私设置命令失败: " + e.getMessage()));
            }
        });
    }
}
