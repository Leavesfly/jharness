package io.leavesfly.jharness.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 桥接会话管理器
 * 管理桥接运行的子会话并捕获其输出
 */
public class BridgeSessionManager {
    
    // 默认会话超时时间 (24 小时)
    public static final long DEFAULT_SESSION_TIMEOUT_MS = 24 * 60 * 60 * 1000;
    
    /**
     * 桥接会话记录
     */
    public static class BridgeSessionRecord {
        private final String sessionId;
        private final String command;
        private final String cwd;
        private final int pid;
        private final String status;
        private final long startedAt;
        private final String outputPath;

        public BridgeSessionRecord(String sessionId, String command, String cwd, int pid, 
                                   String status, long startedAt, String outputPath) {
            this.sessionId = sessionId;
            this.command = command;
            this.cwd = cwd;
            this.pid = pid;
            this.status = status;
            this.startedAt = startedAt;
            this.outputPath = outputPath;
        }

        public String getSessionId() { return sessionId; }
        public String getCommand() { return command; }
        public String getCwd() { return cwd; }
        public int getPid() { return pid; }
        public String getStatus() { return status; }
        public long getStartedAt() { return startedAt; }
        public String getOutputPath() { return outputPath; }
    }
    
    /**
     * 工作项数据类型
     */
    public enum WorkDataType {
        @JsonProperty("session") SESSION,
        @JsonProperty("healthcheck") HEALTHCHECK
    }
    
    /**
     * 工作项数据
     */
    public static class WorkData {
        private final WorkDataType type;
        private final String id;

        public WorkData(WorkDataType type, String id) {
            this.type = type;
            this.id = id;
        }

        public WorkDataType getType() { return type; }
        public String getId() { return id; }
    }
    
    /**
     * 工作密钥
     */
    public static class WorkSecret {
        private final int version;
        private final String sessionIngressToken;
        private final String apiBaseUrl;

        public WorkSecret(int version, String sessionIngressToken, String apiBaseUrl) {
            this.version = version;
            this.sessionIngressToken = sessionIngressToken;
            this.apiBaseUrl = apiBaseUrl;
        }

        public int getVersion() { return version; }
        public String getSessionIngressToken() { return sessionIngressToken; }
        public String getApiBaseUrl() { return apiBaseUrl; }
    }
    
    /**
     * 桥接配置
     */
    public static class BridgeConfig {
        private final String dir;
        private final String machineName;
        private final int maxSessions;
        private final boolean verbose;
        private final long sessionTimeoutMs;

        public BridgeConfig(String dir, String machineName) {
            this(dir, machineName, 1, false, DEFAULT_SESSION_TIMEOUT_MS);
        }

        public BridgeConfig(String dir, String machineName, int maxSessions, boolean verbose, long sessionTimeoutMs) {
            this.dir = dir;
            this.machineName = machineName;
            this.maxSessions = maxSessions;
            this.verbose = verbose;
            this.sessionTimeoutMs = sessionTimeoutMs;
        }

        public String getDir() { return dir; }
        public String getMachineName() { return machineName; }
        public int getMaxSessions() { return maxSessions; }
        public boolean isVerbose() { return verbose; }
        public long getSessionTimeoutMs() { return sessionTimeoutMs; }
    }
}
