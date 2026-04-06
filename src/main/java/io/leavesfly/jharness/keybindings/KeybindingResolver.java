package io.leavesfly.jharness.keybindings;

import java.util.HashMap;
import java.util.Map;

/**
 * 按键绑定解析器
 * 合并用户覆盖配置到默认绑定
 */
public class KeybindingResolver {
    
    /**
     * 合并用户覆盖配置到默认按键绑定
     */
    public static Map<String, String> resolve() {
        return resolve(null);
    }
    
    /**
     * 合并用户覆盖配置到默认按键绑定
     */
    public static Map<String, String> resolve(Map<String, String> overrides) {
        Map<String, String> resolved = new HashMap<>(DefaultKeybindings.getDefaults());
        if (overrides != null) {
            resolved.putAll(overrides);
        }
        return resolved;
    }
}
