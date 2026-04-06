package io.leavesfly.jharness.outputstyles;

/**
 * 输出样式定义
 */
public class OutputStyle {
    
    private final String name;
    private final String content;
    private final String source;
    
    public OutputStyle(String name, String content, String source) {
        this.name = name;
        this.content = content;
        this.source = source;
    }
    
    public String getName() { return name; }
    public String getContent() { return content; }
    public String getSource() { return source; }
    
    @Override
    public String toString() {
        return "OutputStyle{name='" + name + "', source='" + source + "'}";
    }
}
