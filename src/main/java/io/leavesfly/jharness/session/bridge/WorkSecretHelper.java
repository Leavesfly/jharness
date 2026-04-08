package io.leavesfly.jharness.session.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;

/**
 * 工作密钥编解码工具
 */
public class WorkSecretHelper {
    
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    /**
     * 将工作密钥编码为 base64url JSON
     */
    public static String encodeWorkSecret(BridgeSessionManager.WorkSecret secret) {
        try {
            String json = MAPPER.writeValueAsString(secret);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to encode work secret", e);
        }
    }
    
    /**
     * 从 base64url 解码工作密钥
     */
    public static BridgeSessionManager.WorkSecret decodeWorkSecret(String secret) {
        try {
            // Java 的 Base64.getUrlDecoder() 已自动处理 padding，无需手动添加
            // 手动添加 padding 反而可能导致解码失败（如 secret 长度已是 4 的倍数时会多余）
            byte[] decoded = Base64.getUrlDecoder().decode(secret);
            String json = new String(decoded);
            
            WorkSecretData data = MAPPER.readValue(json, WorkSecretData.class);
            if (data.version != 1) {
                throw new IllegalArgumentException("Unsupported work secret version: " + data.version);
            }
            if (data.sessionIngressToken == null || data.sessionIngressToken.isEmpty()) {
                throw new IllegalArgumentException("Invalid work secret: missing session_ingress_token");
            }
            if (data.apiBaseUrl == null || data.apiBaseUrl.isEmpty()) {
                throw new IllegalArgumentException("Invalid work secret: missing api_base_url");
            }
            
            return new BridgeSessionManager.WorkSecret(
                    data.version,
                    data.sessionIngressToken,
                    data.apiBaseUrl
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode work secret", e);
        }
    }
    
    /**
     * 构建会话 ingress WebSocket URL
     */
    public static String buildSdkUrl(String apiBaseUrl, String sessionId) {
        boolean isLocal = apiBaseUrl.contains("localhost") || apiBaseUrl.contains("127.0.0.1");
        String protocol = isLocal ? "ws" : "wss";
        String version = isLocal ? "v2" : "v1";
        String host = apiBaseUrl.replace("https://", "").replace("http://", "").replaceAll("/+$", "");
        return String.format("%s://%s/%s/session_ingress/ws/%s", protocol, host, version, sessionId);
    }
    
    /**
     * 内部数据结构类
     */
    private static class WorkSecretData {
        public int version;
        public String sessionIngressToken;
        public String apiBaseUrl;
    }
}
