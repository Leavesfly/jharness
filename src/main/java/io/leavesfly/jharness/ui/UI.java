package io.leavesfly.jharness.ui;

import io.leavesfly.jharness.ui.widgets.InputWidget;
import io.leavesfly.jharness.ui.widgets.StatusBar;
import io.leavesfly.jharness.ui.widgets.TranscriptWidget;

/**
 * UI 入口点
 * 协调各个 UI 组件
 */
public class UI {
    
    private final StatusBar statusBar;
    private final TranscriptWidget transcript;
    private final InputWidget input;
    private boolean running;
    
    public UI() {
        this.statusBar = new StatusBar("status");
        this.transcript = new TranscriptWidget("transcript");
        this.input = new InputWidget("input");
        this.running = false;
    }
    
    public StatusBar getStatusBar() { return statusBar; }
    public TranscriptWidget getTranscript() { return transcript; }
    public InputWidget getInput() { return input; }
    public boolean isRunning() { return running; }
    
    /**
     * 启动 UI
     */
    public void start() {
        running = true;
        transcript.addSystemMessage("欢迎使用 JHarness");
        transcript.addSystemMessage("输入 /help 查看可用命令，输入 /exit 退出");
    }
    
    /**
     * 停止 UI
     */
    public void stop() {
        running = false;
    }
    
    /**
     * 处理用户输入
     */
    public String handleInput() {
        String text = input.submit();
        if (!text.isEmpty()) {
            transcript.addUserMessage(text);
        }
        return text;
    }
    
    /**
     * 添加助手消息
     */
    public void addAssistantMessage(String message) {
        transcript.addAssistantMessage(message);
    }
    
    /**
     * 添加系统消息
     */
    public void addSystemMessage(String message) {
        transcript.addSystemMessage(message);
    }
    
    /**
     * 更新状态栏
     */
    public void updateStatus(String model, String permissionMode, String statusMessage) {
        statusBar.setModel(model);
        statusBar.setPermissionMode(permissionMode);
        statusBar.setStatusMessage(statusMessage);
    }
    
    /**
     * 清空对话历史
     */
    public void clearHistory() {
        transcript.clear();
    }
}
