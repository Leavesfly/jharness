package io.leavesfly.jharness.command.keybindings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * 按键绑定文件解析器
 */
public class KeybindingParser {
    
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    /**
     * 从 JSON 文本解析按键绑定
     */
    public static Map<String, String> parse(String jsonText) throws IOException {
        Map<String, String> parsed = MAPPER.readValue(jsonText, new TypeReference<Map<String, String>>() {});
        
        for (Map.Entry<String, String> entry : parsed.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException("keybindings keys and values must be strings");
            }
        }
        
        return parsed;
    }
}
