package io.leavesfly.jharness.hooks.schemas;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Hook 配置模式定义
 * 支持四种 Hook 类型：command、prompt、http、agent
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = HookDefinition.CommandHookDefinition.class, name = "command"),
    @JsonSubTypes.Type(value = HookDefinition.PromptHookDefinition.class, name = "prompt"),
    @JsonSubTypes.Type(value = HookDefinition.HttpHookDefinition.class, name = "http"),
    @JsonSubTypes.Type(value = HookDefinition.AgentHookDefinition.class, name = "agent")
})
public abstract class HookDefinition {

    /**
     * 获取 Hook 类型名称
     */
    public abstract String getType();

    /**
     * 执行 shell 命令的 Hook
     */
    public static class CommandHookDefinition extends HookDefinition {
        @JsonProperty("command")
        private String command;
        
        @JsonProperty("timeout_seconds")
        private int timeoutSeconds = 30;
        
        @JsonProperty("matcher")
        private String matcher;
        
        @JsonProperty("block_on_failure")
        private boolean blockOnFailure = false;
        
        public CommandHookDefinition() {}
        
        public CommandHookDefinition(String command, int timeoutSeconds, String matcher, boolean blockOnFailure) {
            this.command = command;
            this.timeoutSeconds = timeoutSeconds;
            this.matcher = matcher;
            this.blockOnFailure = blockOnFailure;
        }
        
        @Override
        public String getType() { return "command"; }

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public String getMatcher() { return matcher; }
        public void setMatcher(String matcher) { this.matcher = matcher; }
        public boolean isBlockOnFailure() { return blockOnFailure; }
        public void setBlockOnFailure(boolean blockOnFailure) { this.blockOnFailure = blockOnFailure; }
    }
    
    /**
     * 请求模型验证条件的 Hook
     */
    public static class PromptHookDefinition extends HookDefinition {
        @JsonProperty("prompt")
        private String prompt;
        
        @JsonProperty("model")
        private String model;
        
        @JsonProperty("timeout_seconds")
        private int timeoutSeconds = 30;
        
        @JsonProperty("matcher")
        private String matcher;
        
        @JsonProperty("block_on_failure")
        private boolean blockOnFailure = true;
        
        public PromptHookDefinition() {}
        
        public PromptHookDefinition(String prompt, String model, int timeoutSeconds, String matcher, boolean blockOnFailure) {
            this.prompt = prompt;
            this.model = model;
            this.timeoutSeconds = timeoutSeconds;
            this.matcher = matcher;
            this.blockOnFailure = blockOnFailure;
        }
        
        @Override
        public String getType() { return "prompt"; }

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public String getMatcher() { return matcher; }
        public void setMatcher(String matcher) { this.matcher = matcher; }
        public boolean isBlockOnFailure() { return blockOnFailure; }
        public void setBlockOnFailure(boolean blockOnFailure) { this.blockOnFailure = blockOnFailure; }
    }
    
    /**
     * 向 HTTP 端点 POST 事件负载的 Hook
     */
    public static class HttpHookDefinition extends HookDefinition {
        @JsonProperty("url")
        private String url;
        
        @JsonProperty("headers")
        private java.util.Map<String, String> headers = new java.util.HashMap<>();
        
        @JsonProperty("timeout_seconds")
        private int timeoutSeconds = 30;
        
        @JsonProperty("matcher")
        private String matcher;
        
        @JsonProperty("block_on_failure")
        private boolean blockOnFailure = false;
        
        public HttpHookDefinition() {}
        
        public HttpHookDefinition(String url, java.util.Map<String, String> headers, int timeoutSeconds, String matcher, boolean blockOnFailure) {
            this.url = url;
            this.headers = headers;
            this.timeoutSeconds = timeoutSeconds;
            this.matcher = matcher;
            this.blockOnFailure = blockOnFailure;
        }
        
        @Override
        public String getType() { return "http"; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public java.util.Map<String, String> getHeaders() { return headers; }
        public void setHeaders(java.util.Map<String, String> headers) { this.headers = headers; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public String getMatcher() { return matcher; }
        public void setMatcher(String matcher) { this.matcher = matcher; }
        public boolean isBlockOnFailure() { return blockOnFailure; }
        public void setBlockOnFailure(boolean blockOnFailure) { this.blockOnFailure = blockOnFailure; }
    }
    
    /**
     * 执行更深层模型验证的 Hook
     */
    public static class AgentHookDefinition extends HookDefinition {
        @JsonProperty("prompt")
        private String prompt;
        
        @JsonProperty("model")
        private String model;
        
        @JsonProperty("timeout_seconds")
        private int timeoutSeconds = 60;
        
        @JsonProperty("matcher")
        private String matcher;
        
        @JsonProperty("block_on_failure")
        private boolean blockOnFailure = true;
        
        public AgentHookDefinition() {}
        
        public AgentHookDefinition(String prompt, String model, int timeoutSeconds, String matcher, boolean blockOnFailure) {
            this.prompt = prompt;
            this.model = model;
            this.timeoutSeconds = timeoutSeconds;
            this.matcher = matcher;
            this.blockOnFailure = blockOnFailure;
        }
        
        @Override
        public String getType() { return "agent"; }

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public String getMatcher() { return matcher; }
        public void setMatcher(String matcher) { this.matcher = matcher; }
        public boolean isBlockOnFailure() { return blockOnFailure; }
        public void setBlockOnFailure(boolean blockOnFailure) { this.blockOnFailure = blockOnFailure; }
    }
}
