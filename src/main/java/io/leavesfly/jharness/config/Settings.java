package io.leavesfly.jharness.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.permissions.PermissionMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 应用程序设置
 *
 * 包含所有配置选项，支持多层配置覆盖。
 */
public class Settings {
    private String model = "claude-3-5-sonnet-20241022";
    private String apiKey;
    private String baseUrl = "https://api.openai.com";
    private int maxTokens = 4096;
    private String systemPrompt;
    private PermissionMode permissionMode = PermissionMode.DEFAULT;
    private int maxTurns = 8;
    private String effort = "medium";
    private int passes = 1;
    private String theme = "default";
    private boolean vimEnabled = false;
    private boolean voiceEnabled = false;
    private boolean fastMode = false;
    private Map<String, Object> mcpServers = new HashMap<>();
    private Map<String, Boolean> enabledPlugins = new HashMap<>();
    private List<String> allowedTools = new ArrayList<>();
    private List<String> deniedTools = new ArrayList<>();
    private String provider = "anthropic";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 默认配置
    private static final Path DEFAULT_CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".jharness");
    private static final Path DEFAULT_DATA_DIR = Paths.get(System.getProperty("user.home"), ".jharness", "data");

    public Settings() {
        // 从环境变量加载配置
        loadFromEnvironment();
    }

    /**
     * 从环境变量加载配置
     */
    private void loadFromEnvironment() {
        String envModel = System.getenv("JHARNESS_MODEL");
        if (envModel != null) this.model = envModel;

        String envApiKey = System.getenv("OPENAI_API_KEY");
        if (envApiKey == null) {
            envApiKey = System.getenv("ANTHROPIC_API_KEY");
        }
        if (envApiKey != null) this.apiKey = envApiKey;

        String envBaseUrl = System.getenv("OPENAI_BASE_URL");
        if (envBaseUrl == null) {
            envBaseUrl = System.getenv("ANTHROPIC_BASE_URL");
        }
        if (envBaseUrl != null) this.baseUrl = envBaseUrl;

        String envMaxTokens = System.getenv("JHARNESS_MAX_TOKENS");
        if (envMaxTokens != null) {
            try {
                this.maxTokens = Integer.parseInt(envMaxTokens);
            } catch (NumberFormatException e) {
                // 环境变量格式错误时使用默认值，不中断启动
            }
        }
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public PermissionMode getPermissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(PermissionMode permissionMode) {
        this.permissionMode = permissionMode;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public String getEffort() {
        return effort;
    }

    public void setEffort(String effort) {
        this.effort = effort;
    }

    public int getPasses() {
        return passes;
    }

    public void setPasses(int passes) {
        this.passes = passes;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public boolean isVimEnabled() {
        return vimEnabled;
    }

    public void setVimEnabled(boolean vimEnabled) {
        this.vimEnabled = vimEnabled;
    }

    public boolean isVoiceEnabled() {
        return voiceEnabled;
    }

    public void setVoiceEnabled(boolean voiceEnabled) {
        this.voiceEnabled = voiceEnabled;
    }

    public boolean isFastMode() {
        return fastMode;
    }

    public void setFastMode(boolean fastMode) {
        this.fastMode = fastMode;
    }

    public Map<String, Object> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(Map<String, Object> mcpServers) {
        this.mcpServers = mcpServers;
    }

    public Map<String, Boolean> getEnabledPlugins() {
        return enabledPlugins;
    }

    public void setEnabledPlugins(Map<String, Boolean> enabledPlugins) {
        this.enabledPlugins = enabledPlugins;
    }

    public static Path getDefaultConfigDir() {
        return DEFAULT_CONFIG_DIR;
    }

    public static Path getDefaultDataDir() {
        return DEFAULT_DATA_DIR;
    }

    public boolean set(String key, String value) {
        return switch (key) {
            case "model" -> { this.model = value; yield true; }
            case "baseUrl" -> { this.baseUrl = value; yield true; }
            case "maxTokens" -> { try { this.maxTokens = Integer.parseInt(value); yield true; } catch (NumberFormatException e) { yield false; } }
            case "theme" -> { this.theme = value; yield true; }
            case "provider" -> { this.provider = value; yield true; }
            case "fastMode" -> { this.fastMode = Boolean.parseBoolean(value); yield true; }
            case "effort" -> { this.effort = value; yield true; }
            case "passes" -> { try { this.passes = Integer.parseInt(value); yield true; } catch (NumberFormatException e) { yield false; } }
            default -> false;
        };
    }

    public String get(String key) {
        return switch (key) {
            case "model" -> model;
            case "baseUrl" -> baseUrl;
            case "maxTokens" -> String.valueOf(maxTokens);
            case "theme" -> theme;
            case "provider" -> provider;
            case "fastMode" -> String.valueOf(fastMode);
            case "effort" -> effort;
            case "passes" -> String.valueOf(passes);
            default -> null;
        };
    }

    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
    public List<String> getDeniedTools() { return deniedTools; }
    public void setDeniedTools(List<String> deniedTools) { this.deniedTools = deniedTools; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public boolean setPermissionMode(String mode) {
        try {
            this.permissionMode = PermissionMode.valueOf(mode.toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static Settings load() {
        Settings settings = new Settings();
        Path configFile = DEFAULT_CONFIG_DIR.resolve("settings.json");
        if (Files.exists(configFile)) {
            try {
                String json = Files.readString(configFile);
                Settings loaded = MAPPER.readValue(json, Settings.class);
                if (loaded.model != null) settings.model = loaded.model;
                if (loaded.apiKey != null) settings.apiKey = loaded.apiKey;
                if (loaded.baseUrl != null) settings.baseUrl = loaded.baseUrl;
                if (loaded.permissionMode != null) settings.permissionMode = loaded.permissionMode;
                if (loaded.theme != null) settings.theme = loaded.theme;
                if (loaded.provider != null) settings.provider = loaded.provider;
                if (loaded.allowedTools != null) settings.allowedTools = loaded.allowedTools;
                if (loaded.deniedTools != null) settings.deniedTools = loaded.deniedTools;
                if (loaded.mcpServers != null) settings.mcpServers = loaded.mcpServers;
                if (loaded.enabledPlugins != null) settings.enabledPlugins = loaded.enabledPlugins;
                // 补全遗漏的字段合并
                settings.maxTokens = loaded.maxTokens;
                settings.maxTurns = loaded.maxTurns;
                if (loaded.effort != null) settings.effort = loaded.effort;
                settings.passes = loaded.passes;
                settings.vimEnabled = loaded.vimEnabled;
                settings.voiceEnabled = loaded.voiceEnabled;
                settings.fastMode = loaded.fastMode;
                if (loaded.systemPrompt != null) settings.systemPrompt = loaded.systemPrompt;
            } catch (Exception e) {
                // 配置文件解析失败时使用默认值
            }
        }
        return settings;
    }

    public void save() {
        try {
            Files.createDirectories(DEFAULT_CONFIG_DIR);
            Path configFile = DEFAULT_CONFIG_DIR.resolve("settings.json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), this);
        } catch (Exception e) {
            throw new RuntimeException("保存设置失败", e);
        }
    }

    public String toJson() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }
}
