package io.leavesfly.jharness.state;

import java.util.HashMap;
import java.util.Map;

/**
 * 应用程序状态模型
 * 共享的可变 UI/会话状态
 */
public class AppState {
    
    private String model;
    private String permissionMode;
    private String theme;
    private String cwd;
    private String provider;
    private String authStatus;
    private String baseUrl;
    private boolean vimEnabled;
    private boolean voiceEnabled;
    private boolean voiceAvailable;
    private String voiceReason;
    private boolean fastMode;
    private String effort;
    private int passes;
    private int mcpConnected;
    private int mcpFailed;
    private int bridgeSessions;
    private String outputStyle;
    private Map<String, String> keybindings;
    
    public AppState() {
        this.model = "unknown";
        this.permissionMode = "default";
        this.theme = "default";
        this.cwd = ".";
        this.provider = "unknown";
        this.authStatus = "missing";
        this.baseUrl = "";
        this.vimEnabled = false;
        this.voiceEnabled = false;
        this.voiceAvailable = false;
        this.voiceReason = "";
        this.fastMode = false;
        this.effort = "medium";
        this.passes = 1;
        this.mcpConnected = 0;
        this.mcpFailed = 0;
        this.bridgeSessions = 0;
        this.outputStyle = "default";
        this.keybindings = new HashMap<>();
    }
    
    // Getters and Setters
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    
    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
    
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    
    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }
    
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    
    public String getAuthStatus() { return authStatus; }
    public void setAuthStatus(String authStatus) { this.authStatus = authStatus; }
    
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    
    public boolean isVimEnabled() { return vimEnabled; }
    public void setVimEnabled(boolean vimEnabled) { this.vimEnabled = vimEnabled; }
    
    public boolean isVoiceEnabled() { return voiceEnabled; }
    public void setVoiceEnabled(boolean voiceEnabled) { this.voiceEnabled = voiceEnabled; }
    
    public boolean isVoiceAvailable() { return voiceAvailable; }
    public void setVoiceAvailable(boolean voiceAvailable) { this.voiceAvailable = voiceAvailable; }
    
    public String getVoiceReason() { return voiceReason; }
    public void setVoiceReason(String voiceReason) { this.voiceReason = voiceReason; }
    
    public boolean isFastMode() { return fastMode; }
    public void setFastMode(boolean fastMode) { this.fastMode = fastMode; }
    
    public String getEffort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }
    
    public int getPasses() { return passes; }
    public void setPasses(int passes) { this.passes = passes; }
    
    public int getMcpConnected() { return mcpConnected; }
    public void setMcpConnected(int mcpConnected) { this.mcpConnected = mcpConnected; }
    
    public int getMcpFailed() { return mcpFailed; }
    public void setMcpFailed(int mcpFailed) { this.mcpFailed = mcpFailed; }
    
    public int getBridgeSessions() { return bridgeSessions; }
    public void setBridgeSessions(int bridgeSessions) { this.bridgeSessions = bridgeSessions; }
    
    public String getOutputStyle() { return outputStyle; }
    public void setOutputStyle(String outputStyle) { this.outputStyle = outputStyle; }
    
    public Map<String, String> getKeybindings() { return keybindings; }
    public void setKeybindings(Map<String, String> keybindings) { this.keybindings = keybindings; }
    
    /**
     * 创建当前状态的副本
     */
    public AppState copy() {
        AppState copy = new AppState();
        copy.model = this.model;
        copy.permissionMode = this.permissionMode;
        copy.theme = this.theme;
        copy.cwd = this.cwd;
        copy.provider = this.provider;
        copy.authStatus = this.authStatus;
        copy.baseUrl = this.baseUrl;
        copy.vimEnabled = this.vimEnabled;
        copy.voiceEnabled = this.voiceEnabled;
        copy.voiceAvailable = this.voiceAvailable;
        copy.voiceReason = this.voiceReason;
        copy.fastMode = this.fastMode;
        copy.effort = this.effort;
        copy.passes = this.passes;
        copy.mcpConnected = this.mcpConnected;
        copy.mcpFailed = this.mcpFailed;
        copy.bridgeSessions = this.bridgeSessions;
        copy.outputStyle = this.outputStyle;
        copy.keybindings = new HashMap<>(this.keybindings);
        return copy;
    }
}
