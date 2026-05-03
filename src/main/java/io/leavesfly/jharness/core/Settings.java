package io.leavesfly.jharness.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.session.permissions.PermissionMode;
import io.leavesfly.jharness.util.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    /**
     * 默认模型：Ollama 上的 qwen3.5:4b（用户需自行 {@code ollama pull qwen3.5:4b} 拉取）。
     * 允许通过环境变量 {@code JHARNESS_MODEL} / {@code OPENAI_MODEL} 或 {@code ~/.jharness/settings.json} 覆盖。
     */
    public static final String DEFAULT_MODEL = "qwen3.5:4b";
    /**
     * 默认 API Base URL：本地 Ollama 的 OpenAI 兼容端点。
     * 允许通过环境变量 {@code OPENAI_BASE_URL} / {@code ANTHROPIC_BASE_URL} 或配置文件覆盖。
     */
    public static final String DEFAULT_BASE_URL = "http://localhost:11434/v1";
    /**
     * 默认 Provider：openai 协议（项目已统一使用 OpenAI 兼容协议客户端）。
     */
    public static final String DEFAULT_PROVIDER = "openai";
    /**
     * 占位 API Key：当使用本地 Ollama 端点且未显式配置 API Key 时，使用此占位符启动，
     * 便于开箱即用。Ollama 本地服务不校验 Authorization 头。
     */
    public static final String LOCAL_PLACEHOLDER_API_KEY = "ollama";

    private String model = DEFAULT_MODEL;
    private String apiKey;
    private String baseUrl = DEFAULT_BASE_URL;
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
    private String outputStyle = "default";
    private Map<String, Object> mcpServers = new HashMap<>();
    private Map<String, Boolean> enabledPlugins = new HashMap<>();
    private List<String> allowedTools = new ArrayList<>();
    private List<String> deniedTools = new ArrayList<>();
    /**
     * FP-1：路径规则列表。每条规则的结构：
     *   { "pattern": "src/**", "allow": true }
     *   { "pattern": "/etc/**", "allow": false }
     * 运行时被 JHarnessApplication.buildQueryEngine 装配到 PermissionChecker.addPathRule。
     */
    private List<Map<String, Object>> pathRules = new ArrayList<>();
    /**
     * FP-1：命令黑名单模式列表（glob 风格，如 "rm -rf *"、"sudo *"）。
     * 被 JHarnessApplication 装配到 PermissionChecker.addDeniedCommand。
     */
    private List<String> deniedCommandPatterns = new ArrayList<>();
    private String provider = DEFAULT_PROVIDER;

    /**
     * 【新增】每日预算上限（USD），&lt;=0 表示不限制。命中后 CostTracker 会抛出 BudgetExceededException。
     */
    private java.math.BigDecimal dailyBudgetUsd = java.math.BigDecimal.ZERO;

    /** 【新增】OpenAI 兼容端点连接超时（秒），默认 30。 */
    private int connectTimeoutSeconds = 30;
    /** 【新增】OpenAI 兼容端点读取超时（秒），默认 300。 */
    private int readTimeoutSeconds = 300;
    /** 【新增】OpenAI 兼容端点写入超时（秒），默认 30。 */
    private int writeTimeoutSeconds = 30;

    /** 【新增】消息压缩的 token 预算，&lt;=0 表示使用 MessageCompactionService 的默认值 32000。 */
    private int messageCompactionTokenBudget = 0;
    /** 【新增】消息压缩的条数阈值，&lt;=0 表示使用 MessageCompactionService 的默认值 20。 */
    private int messageCompactionMaxMessages = 0;

    /** 【新增】会话自动保存目录（相对于 ~/.jharness），默认 sessions。 */
    private boolean autoSaveSessions = true;

    private static final Logger logger = LoggerFactory.getLogger(Settings.class);
    // 统一复用 JacksonUtils 的全局单例，避免项目中散落多份 ObjectMapper 造成的性能/行为差异
    private static final ObjectMapper MAPPER = JacksonUtils.MAPPER;
    private static final ObjectMapper PRETTY_MAPPER = JacksonUtils.PRETTY_MAPPER;

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
        if (envModel == null) {
            envModel = System.getenv("OPENAI_MODEL");
        }
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

    /**
     * API Key 属于敏感凭据，禁止通过 toJson / save 持久化或回显（P2-M26）。
     * 读取时使用 @JsonIgnore 防止在 log/toString/toJson 中泄漏。
     */
    @JsonIgnore
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

    public String getOutputStyle() {
        return outputStyle;
    }

    public void setOutputStyle(String outputStyle) {
        this.outputStyle = outputStyle;
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
            case "outputStyle" -> { this.outputStyle = value; yield true; }
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
            case "outputStyle" -> outputStyle;
            case "effort" -> effort;
            case "passes" -> String.valueOf(passes);
            default -> null;
        };
    }

    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
    public List<String> getDeniedTools() { return deniedTools; }
    public void setDeniedTools(List<String> deniedTools) { this.deniedTools = deniedTools; }
    /** FP-1：路径规则列表（装配到 PermissionChecker.addPathRule）。 */
    public List<Map<String, Object>> getPathRules() { return pathRules; }
    public void setPathRules(List<Map<String, Object>> pathRules) {
        this.pathRules = pathRules != null ? pathRules : new ArrayList<>();
    }
    /** FP-1：命令黑名单（装配到 PermissionChecker.addDeniedCommand）。 */
    public List<String> getDeniedCommandPatterns() { return deniedCommandPatterns; }
    public void setDeniedCommandPatterns(List<String> deniedCommandPatterns) {
        this.deniedCommandPatterns = deniedCommandPatterns != null ? deniedCommandPatterns : new ArrayList<>();
    }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    /** 【新增】每日预算上限（USD）。 */
    public java.math.BigDecimal getDailyBudgetUsd() { return dailyBudgetUsd; }
    public void setDailyBudgetUsd(java.math.BigDecimal dailyBudgetUsd) {
        this.dailyBudgetUsd = dailyBudgetUsd != null ? dailyBudgetUsd : java.math.BigDecimal.ZERO;
    }

    /** 【新增】OpenAI 超时参数。 */
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int v) { this.connectTimeoutSeconds = v > 0 ? v : 30; }
    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int v) { this.readTimeoutSeconds = v > 0 ? v : 300; }
    public int getWriteTimeoutSeconds() { return writeTimeoutSeconds; }
    public void setWriteTimeoutSeconds(int v) { this.writeTimeoutSeconds = v > 0 ? v : 30; }

    /** 【新增】消息压缩参数。 */
    public int getMessageCompactionTokenBudget() { return messageCompactionTokenBudget; }
    public void setMessageCompactionTokenBudget(int v) { this.messageCompactionTokenBudget = v; }
    public int getMessageCompactionMaxMessages() { return messageCompactionMaxMessages; }
    public void setMessageCompactionMaxMessages(int v) { this.messageCompactionMaxMessages = v; }

    /** 【新增】是否在每轮对话后自动保存会话快照。 */
    public boolean isAutoSaveSessions() { return autoSaveSessions; }
    public void setAutoSaveSessions(boolean v) { this.autoSaveSessions = v; }

    public boolean setPermissionMode(String mode) {
        try {
            this.permissionMode = PermissionMode.valueOf(mode.toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 从默认配置文件加载设置。
     *
     * 合并策略（P1-L1）：
     * - 先用无参构造器创建默认值 + 环境变量；
     * - 再解析 JSON 为 JsonNode，仅当字段在 JSON 中显式存在时才覆盖默认值；
     * - 对 primitive（int/boolean）字段同样基于 JsonNode.has() 判断是否存在，
     *   避免 Jackson 默认 0 / false 覆盖掉合理默认值或环境变量带入的值。
     */
    public static Settings load() {
        Settings settings = new Settings();
        Path configFile = DEFAULT_CONFIG_DIR.resolve("settings.json");
        if (!Files.exists(configFile)) {
            return settings;
        }
        try {
            String json = Files.readString(configFile);
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) {
                logger.warn("配置文件根节点不是 JSON 对象，使用默认值: {}", configFile);
                return settings;
            }

            // ===== 引用类型：非空即覆盖 =====
            if (root.hasNonNull("model")) settings.model = root.get("model").asText();
            if (root.hasNonNull("apiKey")) settings.apiKey = root.get("apiKey").asText();
            if (root.hasNonNull("baseUrl")) settings.baseUrl = root.get("baseUrl").asText();
            if (root.hasNonNull("theme")) settings.theme = root.get("theme").asText();
            if (root.hasNonNull("provider")) settings.provider = root.get("provider").asText();
            if (root.hasNonNull("effort")) settings.effort = root.get("effort").asText();
            if (root.hasNonNull("outputStyle")) settings.outputStyle = root.get("outputStyle").asText();
            if (root.hasNonNull("systemPrompt")) settings.systemPrompt = root.get("systemPrompt").asText();

            if (root.hasNonNull("permissionMode")) {
                try {
                    settings.permissionMode = PermissionMode.valueOf(
                            root.get("permissionMode").asText().toUpperCase());
                } catch (IllegalArgumentException iae) {
                    logger.warn("permissionMode 无效，保留默认: {}", root.get("permissionMode").asText());
                }
            }

            // ===== 集合类型：通过 ObjectMapper 反序列化 =====
            if (root.hasNonNull("allowedTools")) {
                settings.allowedTools = MAPPER.convertValue(root.get("allowedTools"),
                        MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
            }
            if (root.hasNonNull("deniedTools")) {
                settings.deniedTools = MAPPER.convertValue(root.get("deniedTools"),
                        MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
            }
            // FP-1：反序列化 pathRules / deniedCommandPatterns
            if (root.hasNonNull("pathRules")) {
                settings.pathRules = MAPPER.convertValue(root.get("pathRules"),
                        MAPPER.getTypeFactory().constructCollectionType(
                                List.class,
                                MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
            }
            if (root.hasNonNull("deniedCommandPatterns")) {
                settings.deniedCommandPatterns = MAPPER.convertValue(root.get("deniedCommandPatterns"),
                        MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
            }
            if (root.hasNonNull("mcpServers")) {
                settings.mcpServers = MAPPER.convertValue(root.get("mcpServers"),
                        MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            }
            if (root.hasNonNull("enabledPlugins")) {
                settings.enabledPlugins = MAPPER.convertValue(root.get("enabledPlugins"),
                        MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Boolean.class));
            }

            // ===== primitive 字段：仅当 JSON 中显式存在时才覆盖 =====
            if (root.has("maxTokens") && root.get("maxTokens").isInt()) {
                settings.maxTokens = root.get("maxTokens").asInt();
            }
            if (root.has("maxTurns") && root.get("maxTurns").isInt()) {
                settings.maxTurns = root.get("maxTurns").asInt();
            }
            if (root.has("passes") && root.get("passes").isInt()) {
                settings.passes = root.get("passes").asInt();
            }
            if (root.has("vimEnabled") && root.get("vimEnabled").isBoolean()) {
                settings.vimEnabled = root.get("vimEnabled").asBoolean();
            }
            if (root.has("voiceEnabled") && root.get("voiceEnabled").isBoolean()) {
                settings.voiceEnabled = root.get("voiceEnabled").asBoolean();
            }
            if (root.has("fastMode") && root.get("fastMode").isBoolean()) {
                settings.fastMode = root.get("fastMode").asBoolean();
            }
            // 【新增】加载预算 / 超时 / 压缩相关字段
            if (root.hasNonNull("dailyBudgetUsd")) {
                try {
                    settings.dailyBudgetUsd = new java.math.BigDecimal(
                            root.get("dailyBudgetUsd").asText());
                } catch (NumberFormatException nfe) {
                    logger.warn("dailyBudgetUsd 格式非法，忽略: {}",
                            root.get("dailyBudgetUsd").asText());
                }
            }
            if (root.has("connectTimeoutSeconds") && root.get("connectTimeoutSeconds").isInt()) {
                int v = root.get("connectTimeoutSeconds").asInt();
                if (v > 0) settings.connectTimeoutSeconds = v;
            }
            if (root.has("readTimeoutSeconds") && root.get("readTimeoutSeconds").isInt()) {
                int v = root.get("readTimeoutSeconds").asInt();
                if (v > 0) settings.readTimeoutSeconds = v;
            }
            if (root.has("writeTimeoutSeconds") && root.get("writeTimeoutSeconds").isInt()) {
                int v = root.get("writeTimeoutSeconds").asInt();
                if (v > 0) settings.writeTimeoutSeconds = v;
            }
            if (root.has("messageCompactionTokenBudget")
                    && root.get("messageCompactionTokenBudget").isInt()) {
                settings.messageCompactionTokenBudget =
                        root.get("messageCompactionTokenBudget").asInt();
            }
            if (root.has("messageCompactionMaxMessages")
                    && root.get("messageCompactionMaxMessages").isInt()) {
                settings.messageCompactionMaxMessages =
                        root.get("messageCompactionMaxMessages").asInt();
            }
            if (root.has("autoSaveSessions") && root.get("autoSaveSessions").isBoolean()) {
                settings.autoSaveSessions = root.get("autoSaveSessions").asBoolean();
            }
        } catch (Exception e) {
            // 配置文件解析失败时使用默认值，但记录警告日志便于排查
            logger.warn("配置文件解析失败，将使用默认值: {} (配置文件路径: {})", e.getMessage(), configFile);
        }
        return settings;
    }

    public void save() {
        try {
            Files.createDirectories(DEFAULT_CONFIG_DIR);
            Path configFile = DEFAULT_CONFIG_DIR.resolve("settings.json");
            PRETTY_MAPPER.writeValue(configFile.toFile(), this);
        } catch (Exception e) {
            throw new RuntimeException("保存设置失败", e);
        }
    }

    public String toJson() {
        try {
            return PRETTY_MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            logger.debug("序列化 Settings 为 JSON 失败", e);
            return "{}";
        }
    }
}
