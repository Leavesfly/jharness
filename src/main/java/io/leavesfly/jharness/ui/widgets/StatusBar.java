package io.leavesfly.jharness.ui.widgets;

/**
 * 状态栏组件
 * 显示当前模型、权限模式等信息
 */
public class StatusBar extends Widget {
    
    private String model;
    private String permissionMode;
    private String statusMessage;
    private boolean vimEnabled;
    private boolean voiceEnabled;
    
    public StatusBar(String id) {
        super(id);
        this.model = "unknown";
        this.permissionMode = "default";
        this.statusMessage = "";
        this.vimEnabled = false;
        this.voiceEnabled = false;
    }
    
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
    public boolean isVimEnabled() { return vimEnabled; }
    public void setVimEnabled(boolean vimEnabled) { this.vimEnabled = vimEnabled; }
    public boolean isVoiceEnabled() { return voiceEnabled; }
    public void setVoiceEnabled(boolean voiceEnabled) { this.voiceEnabled = voiceEnabled; }
    
    @Override
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("模型: ").append(model);
        sb.append(" | 权限: ").append(permissionMode);
        if (vimEnabled) sb.append(" | VIM");
        if (voiceEnabled) sb.append(" | VOICE");
        if (!statusMessage.isEmpty()) sb.append(" | ").append(statusMessage);
        return sb.toString();
    }
}
