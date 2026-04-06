package io.leavesfly.jharness.commands.handlers;

import io.leavesfly.jharness.commands.CommandContext;
import io.leavesfly.jharness.commands.CommandResult;
import io.leavesfly.jharness.commands.SimpleSlashCommand;
import io.leavesfly.jharness.commands.SlashCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 快捷键命令处理器
 *
 * 显示当前已解析的快捷键绑定
 */
public class KeybindingsCommandHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static SlashCommand createKeybindingsCommand() {
        return new SimpleSlashCommand("keybindings", "显示当前快捷键绑定", (args, ctx, ec) -> {
            try {
                Path keybindingsPath = getKeybindingsPath();
                Map<String, String> bindings = loadKeybindings(keybindingsPath);

                List<String> lines = new java.util.ArrayList<>();
                lines.add("快捷键配置文件: " + keybindingsPath);
                lines.add("");
                for (Map.Entry<String, String> entry : bindings.entrySet()) {
                    lines.add(entry.getKey() + " -> " + entry.getValue());
                }

                return CompletableFuture.completedFuture(CommandResult.success(String.join("\n", lines)));
            } catch (Exception e) {
                return CompletableFuture.completedFuture(CommandResult.error("快捷键命令失败: " + e.getMessage()));
            }
        });
    }

    private static Path getKeybindingsPath() {
        return Path.of(System.getProperty("user.home"), ".jharness", "keybindings.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> loadKeybindings(Path path) throws Exception {
        if (!Files.exists(path)) {
            return getDefaultKeybindings();
        }

        String content = Files.readString(path);
        return MAPPER.readValue(content, Map.class);
    }

    private static Map<String, String> getDefaultKeybindings() {
        Map<String, String> bindings = new TreeMap<>();
        bindings.put("Ctrl+C", "中断当前操作");
        bindings.put("Ctrl+L", "清屏");
        bindings.put("Ctrl+D", "退出");
        bindings.put("Tab", "自动补全");
        bindings.put("Up/Down", "历史命令导航");
        return bindings;
    }
}
