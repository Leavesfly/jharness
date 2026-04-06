package io.leavesfly.jharness.ui.widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史记录组件
 */
public class TranscriptWidget extends Widget {
    
    public enum Role {
        USER, ASSISTANT, SYSTEM, TOOL, TOOL_RESULT
    }
    
    public static class TranscriptItem {
        private final Role role;
        private final String text;
        private final String toolName;
        private final boolean isError;
        
        public TranscriptItem(Role role, String text) {
            this(role, text, null, false);
        }
        
        public TranscriptItem(Role role, String text, String toolName, boolean isError) {
            this.role = role;
            this.text = text;
            this.toolName = toolName;
            this.isError = isError;
        }
        
        public Role getRole() { return role; }
        public String getText() { return text; }
        public String getToolName() { return toolName; }
        public boolean isError() { return isError; }
    }
    
    private final List<TranscriptItem> items = new ArrayList<>();
    private final int maxItems;
    
    public TranscriptWidget(String id) {
        this(id, 100);
    }
    
    public TranscriptWidget(String id, int maxItems) {
        super(id);
        this.maxItems = maxItems;
    }
    
    public void addItem(TranscriptItem item) {
        items.add(item);
        if (items.size() > maxItems) {
            items.subList(0, items.size() - maxItems).clear();
        }
    }
    
    public void addUserMessage(String text) {
        addItem(new TranscriptItem(Role.USER, text));
    }
    
    public void addAssistantMessage(String text) {
        addItem(new TranscriptItem(Role.ASSISTANT, text));
    }
    
    public void addSystemMessage(String text) {
        addItem(new TranscriptItem(Role.SYSTEM, text));
    }
    
    public void clear() {
        items.clear();
    }
    
    public List<TranscriptItem> getItems() {
        return List.copyOf(items);
    }
    
    @Override
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (TranscriptItem item : items) {
            switch (item.getRole()) {
                case USER -> sb.append("> ").append(item.getText());
                case ASSISTANT -> sb.append("[AI] ").append(item.getText());
                case SYSTEM -> sb.append("  ").append(item.getText());
                case TOOL -> sb.append("[Tool] ").append(item.getToolName()).append(": ").append(item.getText());
                case TOOL_RESULT -> {
                    if (item.isError()) {
                        sb.append("[Error] ").append(item.getText());
                    } else {
                        sb.append("[Result] ").append(item.getText());
                    }
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
