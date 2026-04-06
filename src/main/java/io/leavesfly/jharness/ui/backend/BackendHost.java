package io.leavesfly.jharness.ui.backend;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * UI 后端主机配置和协议
 * 管理前后端之间的 JSON 行协议通信
 */
public class BackendHost {
    
    /**
     * 后端主机配置
     */
    public static class BackendHostConfig {
        private final String model;
        private final String baseUrl;
        private final String systemPrompt;
        private final String apiKey;
        
        public BackendHostConfig(String model, String baseUrl, String systemPrompt, String apiKey) {
            this.model = model;
            this.baseUrl = baseUrl;
            this.systemPrompt = systemPrompt;
            this.apiKey = apiKey;
        }
        
        public String getModel() { return model; }
        public String getBaseUrl() { return baseUrl; }
        public String getSystemPrompt() { return systemPrompt; }
        public String getApiKey() { return apiKey; }
    }
    
    /**
     * 前端请求类型
     */
    public enum FrontendRequestType {
        @JsonProperty("submit_line") SUBMIT_LINE,
        @JsonProperty("shutdown") SHUTDOWN,
        @JsonProperty("permission_response") PERMISSION_RESPONSE,
        @JsonProperty("question_response") QUESTION_RESPONSE,
        @JsonProperty("list_sessions") LIST_SESSIONS
    }
    
    /**
     * 前端请求
     */
    public static class FrontendRequest {
        @JsonProperty("type")
        private FrontendRequestType type;
        
        @JsonProperty("line")
        private String line;
        
        @JsonProperty("request_id")
        private String requestId;
        
        @JsonProperty("allowed")
        private Boolean allowed;
        
        @JsonProperty("answer")
        private String answer;
        
        public FrontendRequest() {}
        
        public FrontendRequest(FrontendRequestType type, String line, String requestId, Boolean allowed, String answer) {
            this.type = type;
            this.line = line;
            this.requestId = requestId;
            this.allowed = allowed;
            this.answer = answer;
        }
        
        public FrontendRequestType getType() { return type; }
        public void setType(FrontendRequestType type) { this.type = type; }
        public String getLine() { return line; }
        public void setLine(String line) { this.line = line; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public Boolean getAllowed() { return allowed; }
        public void setAllowed(Boolean allowed) { this.allowed = allowed; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
    }
    
    /**
     * 后端事件类型
     */
    public enum BackendEventType {
        @JsonProperty("ready") READY,
        @JsonProperty("shutdown") SHUTDOWN,
        @JsonProperty("error") ERROR,
        @JsonProperty("transcript_item") TRANSCRIPT_ITEM,
        @JsonProperty("assistant_delta") ASSISTANT_DELTA,
        @JsonProperty("assistant_complete") ASSISTANT_COMPLETE,
        @JsonProperty("tool_started") TOOL_STARTED,
        @JsonProperty("tool_completed") TOOL_COMPLETED,
        @JsonProperty("clear_transcript") CLEAR_TRANSCRIPT,
        @JsonProperty("line_complete") LINE_COMPLETE,
        @JsonProperty("status_snapshot") STATUS_SNAPSHOT,
        @JsonProperty("tasks_snapshot") TASKS_SNAPSHOT,
        @JsonProperty("modal_request") MODAL_REQUEST,
        @JsonProperty("select_request") SELECT_REQUEST
    }
    
    /**
     * 后端事件
     */
    public static class BackendEvent {
        @JsonProperty("type")
        private BackendEventType type;
        
        @JsonProperty("message")
        private String message;
        
        @JsonProperty("tool_name")
        private String toolName;
        
        @JsonProperty("tool_input")
        private Map<String, Object> toolInput;
        
        @JsonProperty("output")
        private String output;
        
        @JsonProperty("is_error")
        private Boolean isError;
        
        @JsonProperty("modal")
        private Map<String, Object> modal;
        
        @JsonProperty("select_options")
        private List<Map<String, String>> selectOptions;
        
        public BackendEvent() {}
        
        public BackendEvent(BackendEventType type, String message, String toolName, 
                          Map<String, Object> toolInput, String output, Boolean isError,
                          Map<String, Object> modal, List<Map<String, String>> selectOptions) {
            this.type = type;
            this.message = message;
            this.toolName = toolName;
            this.toolInput = toolInput;
            this.output = output;
            this.isError = isError;
            this.modal = modal;
            this.selectOptions = selectOptions;
        }
        
        public BackendEventType getType() { return type; }
        public void setType(BackendEventType type) { this.type = type; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public Map<String, Object> getToolInput() { return toolInput; }
        public void setToolInput(Map<String, Object> toolInput) { this.toolInput = toolInput; }
        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }
        public Boolean getIsError() { return isError; }
        public void setIsError(Boolean isError) { this.isError = isError; }
        public Map<String, Object> getModal() { return modal; }
        public void setModal(Map<String, Object> modal) { this.modal = modal; }
        public List<Map<String, String>> getSelectOptions() { return selectOptions; }
        public void setSelectOptions(List<Map<String, String>> selectOptions) { this.selectOptions = selectOptions; }
        
        // 工厂方法
        public static BackendEvent ready() {
            BackendEvent event = new BackendEvent();
            event.type = BackendEventType.READY;
            return event;
        }
        
        public static BackendEvent error(String message) {
            BackendEvent event = new BackendEvent();
            event.type = BackendEventType.ERROR;
            event.message = message;
            return event;
        }
        
        public static BackendEvent assistantDelta(String text) {
            BackendEvent event = new BackendEvent();
            event.type = BackendEventType.ASSISTANT_DELTA;
            event.message = text;
            return event;
        }
        
        public static BackendEvent toolStarted(String toolName, Map<String, Object> toolInput) {
            BackendEvent event = new BackendEvent();
            event.type = BackendEventType.TOOL_STARTED;
            event.toolName = toolName;
            event.toolInput = toolInput;
            return event;
        }
        
        public static BackendEvent toolCompleted(String toolName, String output, boolean isError) {
            BackendEvent event = new BackendEvent();
            event.type = BackendEventType.TOOL_COMPLETED;
            event.toolName = toolName;
            event.output = output;
            event.isError = isError;
            return event;
        }
    }
}
