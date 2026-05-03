package io.leavesfly.jharness.extension.plugins;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 插件清单
 *
 * 描述插件的元数据。对齐 Claude Code plugin manifest 字段，未识别字段直接忽略
 * （用 {@code @JsonIgnoreProperties(ignoreUnknown = true)}），以便 Claude Code
 * 生态里的插件能被直接塞到 ~/.jharness/plugins/ 里加载。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginManifest {
    private String name;
    private String version;
    private String description;
    private boolean enabledByDefault = true;
    private String skillsDir;
    private String hooksFile;
    private String mcpFile;

    // 【P1】Claude Code 对齐字段
    private String author;
    private String homepage;
    private String repository;
    private String license;
    private List<String> keywords;
    private String commandsDir;
    private String agentsDir;

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

    // ===== 【P1】Claude Code 对齐字段 =====

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getHomepage() { return homepage; }
    public void setHomepage(String homepage) { this.homepage = homepage; }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getLicense() { return license; }
    public void setLicense(String license) { this.license = license; }

    public List<String> getKeywords() { return keywords; }
    public void setKeywords(List<String> keywords) { this.keywords = keywords; }

    public String getCommandsDir() { return commandsDir; }
    public void setCommandsDir(String commandsDir) { this.commandsDir = commandsDir; }

    public String getAgentsDir() { return agentsDir; }
    public void setAgentsDir(String agentsDir) { this.agentsDir = agentsDir; }
}
