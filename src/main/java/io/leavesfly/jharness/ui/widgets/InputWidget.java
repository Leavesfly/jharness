package io.leavesfly.jharness.ui.widgets;

/**
 * 输入框组件
 */
public class InputWidget extends Widget {
    
    private final StringBuilder content = new StringBuilder();
    private String prompt = "> ";
    private int maxLength = 10000;
    
    public InputWidget(String id) {
        super(id);
    }
    
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public int getMaxLength() { return maxLength; }
    public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
    
    public String getContent() {
        return content.toString();
    }
    
    public void appendChar(char c) {
        if (content.length() < maxLength) {
            content.append(c);
        }
    }
    
    public void backspace() {
        if (content.length() > 0) {
            content.deleteCharAt(content.length() - 1);
        }
    }
    
    public String submit() {
        String text = content.toString().trim();
        content.setLength(0);
        return text;
    }
    
    public void clear() {
        content.setLength(0);
    }
    
    @Override
    public String render() {
        return prompt + content.toString();
    }
}
