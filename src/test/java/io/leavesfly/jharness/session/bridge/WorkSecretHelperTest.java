package io.leavesfly.jharness.session.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WorkSecretHelper} 单元测试。
 *
 * <p>本轮改造了 UTF-8 编码显式化 + 复用全局 ObjectMapper，这里做核心行为回归。</p>
 */
class WorkSecretHelperTest {

    @Test
    void encodeThenDecode_roundTrip_preservesFields() {
        BridgeSessionManager.WorkSecret original = new BridgeSessionManager.WorkSecret(
                1,
                "ingress-token-xyz-中文-🔑", // 故意加 Unicode 验证 UTF-8 显式化
                "https://example.test");

        String encoded = WorkSecretHelper.encodeWorkSecret(original);
        assertNotNull(encoded);
        assertFalse(encoded.contains("="), "base64url withoutPadding 不应包含 '='");
        assertFalse(encoded.contains("+") || encoded.contains("/"),
                "base64url 不应包含标准 base64 的 + / 字符");

        BridgeSessionManager.WorkSecret decoded = WorkSecretHelper.decodeWorkSecret(encoded);
        assertEquals(original.getVersion(), decoded.getVersion());
        assertEquals(original.getSessionIngressToken(), decoded.getSessionIngressToken());
        assertEquals(original.getApiBaseUrl(), decoded.getApiBaseUrl());
    }

    @Test
    void decode_invalidBase64_wrappedInRuntimeException() {
        // 非法 base64 字符会被 Base64.getUrlDecoder 拒绝，最终包装成 RuntimeException
        assertThrows(RuntimeException.class,
                () -> WorkSecretHelper.decodeWorkSecret("!!!not-base64!!!"));
    }

    @Test
    void buildSdkUrl_localhost_usesWsAndV2() {
        String url = WorkSecretHelper.buildSdkUrl("http://localhost:8080/", "sess-1");
        assertEquals("ws://localhost:8080/v2/session_ingress/ws/sess-1", url);
    }

    @Test
    void buildSdkUrl_remote_usesWssAndV1() {
        String url = WorkSecretHelper.buildSdkUrl("https://api.example.com", "s2");
        assertEquals("wss://api.example.com/v1/session_ingress/ws/s2", url);
    }
}
