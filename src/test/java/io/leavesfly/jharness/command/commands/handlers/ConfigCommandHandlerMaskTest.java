package io.leavesfly.jharness.command.commands.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConfigCommandHandler#maskApiKey(String)} 单元测试。
 *
 * <p>API Key 脱敏是安全点：必须保证未配置时给出明确提示、短 Key 不泄漏任何片段、
 * 长 Key 仅保留可供识别的前缀 + 后缀。</p>
 */
class ConfigCommandHandlerMaskTest {

    @Test
    void maskApiKey_nullOrEmpty_returnsPlaceholder() {
        assertEquals("(未配置)", ConfigCommandHandler.maskApiKey(null));
        assertEquals("(未配置)", ConfigCommandHandler.maskApiKey(""));
    }

    @Test
    void maskApiKey_shortKey_fullyMasked() {
        // 长度 <=10 的 Key 一律打码成 ***，避免通过前后缀反推
        assertEquals("***", ConfigCommandHandler.maskApiKey("short"));
        assertEquals("***", ConfigCommandHandler.maskApiKey("0123456789")); // 恰好 10
    }

    @Test
    void maskApiKey_longKey_prefixAndSuffixOnly() {
        String key = "sk-abcdefg1234567890xyz";
        String masked = ConfigCommandHandler.maskApiKey(key);

        assertTrue(masked.startsWith("sk-abc"), "应保留前 6 位");
        assertTrue(masked.endsWith("0xyz"), "应保留后 4 位");
        assertTrue(masked.contains("..."), "中间应为省略号");
        assertFalse(masked.contains("defg1234567"),
                "中间敏感片段不应泄漏，masked=" + masked);
    }
}
