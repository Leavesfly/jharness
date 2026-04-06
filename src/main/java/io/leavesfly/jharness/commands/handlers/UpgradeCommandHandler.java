package io.leavesfly.jharness.commands.handlers;

import io.leavesfly.jharness.commands.CommandContext;
import io.leavesfly.jharness.commands.CommandResult;
import io.leavesfly.jharness.commands.SimpleSlashCommand;
import io.leavesfly.jharness.commands.SlashCommand;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/**
 * 升级命令处理器
 *
 * 显示升级说明
 */
public class UpgradeCommandHandler {

    public static SlashCommand createUpgradeCommand() {
        return new SimpleSlashCommand("upgrade", "显示升级说明", (args, ctx, ec) -> {
            try {
                String version = getCurrentVersion();
                String message = String.join("\n", List.of(
                    "当前版本: " + version,
                    "",
                    "升级说明:",
                    "- Java 版本: 使用 Maven 构建",
                    "  mvn clean package",
                    "- 依赖更新: mvn dependency:update",
                    "- 配置文件: 检查 ~/.jharness/settings.json 是否需要更新"
                ));
                return CompletableFuture.completedFuture(CommandResult.success(message));
            } catch (Exception e) {
                return CompletableFuture.completedFuture(CommandResult.error("升级命令失败: " + e.getMessage()));
            }
        });
    }

    private static String getCurrentVersion() {
        try {
            Properties props = new Properties();
            InputStream is = UpgradeCommandHandler.class.getClassLoader()
                .getResourceAsStream("META-INF/maven/io.leavesfly/jharness/pom.properties");
            if (is != null) {
                props.load(is);
                return props.getProperty("version", "unknown");
            }
        } catch (Exception e) {
            // 忽略
        }
        return "development";
    }
}
