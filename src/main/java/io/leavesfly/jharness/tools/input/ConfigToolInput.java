package io.leavesfly.jharness.tools.input;

/**
 * 配置工具输入
 */
public class ConfigToolInput {
    private String action; // get, set, list
    private String scope;
    private String key;
    private String value;

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
