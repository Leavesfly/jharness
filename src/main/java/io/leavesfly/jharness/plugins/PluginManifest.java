package io.leavesfly.jharness.plugins;

/**
 * 插件清单
 *
 * 描述插件的元数据。
 */
public class PluginManifest {
    private String name;
    private String version;
    private String description;
    private boolean enabledByDefault = true;
    private String skillsDir;
    private String hooksFile;
    private String mcpFile;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    public void setEnabledByDefault(boolean enabledByDefault) {
        this.enabledByDefault = enabledByDefault;
    }

    public String getSkillsDir() {
        return skillsDir;
    }

    public void setSkillsDir(String skillsDir) {
        this.skillsDir = skillsDir;
    }

    public String getHooksFile() {
        return hooksFile;
    }

    public void setHooksFile(String hooksFile) {
        this.hooksFile = hooksFile;
    }

    public String getMcpFile() {
        return mcpFile;
    }

    public void setMcpFile(String mcpFile) {
        this.mcpFile = mcpFile;
    }
}
