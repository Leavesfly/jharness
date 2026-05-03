package io.leavesfly.jharness.core;

import io.leavesfly.jharness.integration.api.OpenAiApiClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@link OpenAiApiClient} 的本地端点开箱即用行为：
 * - baseUrl 指向 localhost/127.0.0.1 时允许空 API Key 启动（使用 'ollama' 占位）；
 * - 指向远程端点时空 API Key 仍需抛异常；
 * - isLocalEndpoint 对 host 的解析应避免误判含 "localhost" 字样的远程域名。
 */
class OpenAiApiClientLocalEndpointTest {

    @Test
    void isLocalEndpointRecognizesCommonLoopbackHosts() {
        assertTrue(OpenAiApiClient.isLocalEndpoint("http://localhost:11434/v1"));
        assertTrue(OpenAiApiClient.isLocalEndpoint("http://127.0.0.1:11434/v1"));
        assertTrue(OpenAiApiClient.isLocalEndpoint("http://0.0.0.0:8080"));
        assertTrue(OpenAiApiClient.isLocalEndpoint("http://[::1]:11434"));
    }

    @Test
    void isLocalEndpointRejectsRemoteHosts() {
        assertFalse(OpenAiApiClient.isLocalEndpoint("https://api.openai.com/v1"));
        assertFalse(OpenAiApiClient.isLocalEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        // 恶意/误拼域名里包含 "localhost" 字样但 host 不是本地
        assertFalse(OpenAiApiClient.isLocalEndpoint("https://not-localhost.example.com/v1"));
    }

    @Test
    void isLocalEndpointHandlesNullAndBlank() {
        assertFalse(OpenAiApiClient.isLocalEndpoint(null));
        assertFalse(OpenAiApiClient.isLocalEndpoint(""));
        assertFalse(OpenAiApiClient.isLocalEndpoint("   "));
    }

    @Test
    void clientAcceptsEmptyApiKeyForLocalOllama() {
        // 本地端点 + 空 key：应成功构造（使用 'ollama' 占位），不触发异常
        OpenAiApiClient client = assertDoesNotThrow(() ->
                new OpenAiApiClient("http://localhost:11434/v1", "", "qwen3.5:4b", 4096));
        try {
            // 模型名应原样保留，便于成本/日志
            org.junit.jupiter.api.Assertions.assertEquals("qwen3.5:4b", client.getModelName());
        } finally {
            client.close();
        }
    }

    @Test
    void clientAcceptsNullApiKeyForLocalOllama() {
        OpenAiApiClient client = assertDoesNotThrow(() ->
                new OpenAiApiClient("http://127.0.0.1:11434/v1", null, "qwen3.5:4b", 4096));
        client.close();
    }

    @Test
    void clientRejectsEmptyApiKeyForRemoteEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiApiClient("https://api.openai.com/v1", "", "gpt-4", 4096));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiApiClient("https://api.openai.com/v1", null, "gpt-4", 4096));
    }
}
