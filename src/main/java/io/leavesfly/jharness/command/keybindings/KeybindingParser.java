package io.leavesfly.jharness.command.keybindings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.util.JacksonUtils;

import java.io.IOException;
import java.util.Map;

/**
 * 按键绑定文件解析器
 */
public class KeybindingParser {

    // 复用全局 ObjectMapper，避免按需实例化开销
    private static final ObjectMapper MAPPER = JacksonUtils.MAPPER;

    private KeybindingParser() {
        // 工具类不允许实例化
    }

    /**
     * 从 JSON 文本解析按键绑定。
     *
     * 说明：旧实现遍历 entrySet 检查 instanceof String，但 TypeReference<Map<String,String>>
     * 反序列化时非字符串值已经在 Jackson 层抛 MismatchedInputException，这里的 instanceof
     * 对泛型擦除后的对象永远为 true，属于无效防御。改为直接透传反序列化结果，同时对非 Map
     * 根节点提前抛出更清晰的错误。
     */
    public static Map<String, String> parse(String jsonText) throws IOException {
        if (jsonText == null || jsonText.isBlank()) {
            throw new IllegalArgumentException("keybindings JSON 文本不能为空");
        }
        return MAPPER.readValue(jsonText, new TypeReference<Map<String, String>>() {});
    }
}
