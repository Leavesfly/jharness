package io.leavesfly.jharness.command.keybindings;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link KeybindingParser} 单元测试。
 *
 * <p>重点覆盖本轮重构：</p>
 * <ul>
 *   <li>合法 JSON 能正确解析；</li>
 *   <li>空 / 空白输入快速失败（新增的 IllegalArgumentException 分支）；</li>
 *   <li>非对象根节点由 Jackson 侧抛出 MismatchedInputException，给出清晰错误。</li>
 * </ul>
 *
 * <p>注意：旧实现里的 "非字符串 value 检测" 属于对泛型擦除后永远为真的 instanceof 校验，
 * 实际上 Jackson 会把数字等 scalar 值按 {@code toString()} 强制转为字符串——这是 Jackson
 * 的默认行为，不属于 bug。因此移除了该错误的防御性检查，同时测试也不再断言"非字符串抛异常"。</p>
 */
class KeybindingParserTest {

    @Test
    void parse_validJson_returnsMap() throws IOException {
        Map<String, String> bindings = KeybindingParser.parse(
                "{\"Ctrl+C\":\"abort\",\"Tab\":\"complete\"}");

        assertEquals(2, bindings.size());
        assertEquals("abort", bindings.get("Ctrl+C"));
        assertEquals("complete", bindings.get("Tab"));
    }

    @Test
    void parse_nullOrBlank_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> KeybindingParser.parse(null));
        assertThrows(IllegalArgumentException.class, () -> KeybindingParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> KeybindingParser.parse("   \n\t  "));
    }

    @Test
    void parse_numericValue_coercedToString() throws IOException {
        // Jackson 默认允许 scalar 强转为字符串，这里做行为固化：数字 123 -> "123"
        Map<String, String> bindings = KeybindingParser.parse("{\"Ctrl+C\": 123}");
        assertEquals("123", bindings.get("Ctrl+C"));
    }

    @Test
    void parse_notAnObject_throwsJacksonException() {
        // 顶层是数组：Jackson 会抛 MismatchedInputException，调用方得到明确错误
        assertThrows(MismatchedInputException.class,
                () -> KeybindingParser.parse("[\"Ctrl+C\",\"abort\"]"));
    }
}
