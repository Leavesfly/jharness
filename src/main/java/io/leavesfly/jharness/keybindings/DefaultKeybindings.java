package io.leavesfly.jharness.keybindings;

import java.util.HashMap;
import java.util.Map;

/**
 * 默认按键绑定映射
 */
public class DefaultKeybindings {
    
    private DefaultKeybindings() {}
    
    /**
     * 返回默认按键绑定
     */
    public static Map<String, String> getDefaults() {
        Map<String, String> bindings = new HashMap<>();
        bindings.put("ctrl+l", "clear");
        bindings.put("ctrl+k", "toggle_vim");
        bindings.put("ctrl+v", "toggle_voice");
        bindings.put("ctrl+t", "tasks");
        return bindings;
    }
}
