package io.leavesfly.jharness.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@link Settings} 的开箱即用默认值：
 * 默认模型指向 Ollama 的 qwen3.5:4b，baseUrl 指向本地 Ollama OpenAI 兼容端点，provider 为 openai。
 */
class SettingsDefaultsTest {

    @Test
    void defaultsPointToLocalOllama() {
        Settings settings = new Settings();
        // 默认值未被环境变量覆盖时才断言：避免 CI 上设置了 OPENAI_* 环境变量导致误失败
        if (System.getenv("JHARNESS_MODEL") == null && System.getenv("OPENAI_MODEL") == null) {
            assertEquals(Settings.DEFAULT_MODEL, settings.getModel());
        }
        if (System.getenv("OPENAI_BASE_URL") == null && System.getenv("ANTHROPIC_BASE_URL") == null) {
            assertEquals(Settings.DEFAULT_BASE_URL, settings.getBaseUrl());
        }
        assertEquals(Settings.DEFAULT_PROVIDER, settings.getProvider());
    }

    @Test
    void defaultModelIsQwen354b() {
        assertEquals("qwen3.5:4b", Settings.DEFAULT_MODEL);
    }

    @Test
    void defaultBaseUrlIsLocalOllama() {
        assertEquals("http://localhost:11434/v1", Settings.DEFAULT_BASE_URL);
        assertTrue(Settings.DEFAULT_BASE_URL.contains("localhost"));
    }

    @Test
    void defaultProviderIsOpenai() {
        assertEquals("openai", Settings.DEFAULT_PROVIDER);
    }
}
