package io.leavesfly.jharness.command.keybindings;

import io.leavesfly.jharness.core.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 从配置文件加载按键绑定
 */
public class KeybindingLoader {
    
    /**
     * 获取用户按键绑定文件路径
     */
    public static Path getKeybindingsPath() {
        return Settings.getDefaultConfigDir().resolve("keybindings.json");
    }
    
    /**
     * 加载并合并按键绑定
     */
    public static Map<String, String> loadKeybindings() {
        Path path = getKeybindingsPath();
        if (!Files.exists(path)) {
            return KeybindingResolver.resolve();
        }
        
        try {
            String json = Files.readString(path);
            Map<String, String> parsed = KeybindingParser.parse(json);
            return KeybindingResolver.resolve(parsed);
        } catch (IOException e) {
            return KeybindingResolver.resolve();
        }
    }
}
