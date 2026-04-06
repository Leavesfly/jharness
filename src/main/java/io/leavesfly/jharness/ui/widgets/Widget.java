package io.leavesfly.jharness.ui.widgets;

/**
 * UI 组件基类
 */
public abstract class Widget {
    
    protected String id;
    protected boolean visible = true;
    
    public Widget(String id) {
        this.id = id;
    }
    
    public String getId() { return id; }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
    
    /**
     * 渲染组件为文本
     */
    public abstract String render();
}
