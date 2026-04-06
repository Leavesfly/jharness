package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.input.ConfigToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 配置管理工具
 * 
 * 读取或更新 OpenHarness 设置。
 */
public class ConfigTool extends BaseTool<ConfigToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(ConfigTool.class);
    private final Settings settings;

    public ConfigTool(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String getName() {
        return "config";
    }

    @Override
    public String getDescription() {
        return "读取或更新 OpenHarness 配置设置";
    }

    @Override
    public Class<ConfigToolInput> getInputClass() {
        return ConfigToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(ConfigToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String action = input.getAction();
                
                if ("get".equals(action) || action == null) {
                    return getConfig(input.getKey());
                } else if ("set".equals(action)) {
                    return setConfig(input.getKey(), input.getValue());
                } else if ("list".equals(action)) {
                    return listConfig();
                } else {
                    return ToolResult.error("未知操作: " + action + "。支持: get, set, list");
                }
            } catch (Exception e) {
                logger.error("配置操作失败", e);
                return ToolResult.error("配置操作失败: " + e.getMessage());
            }
        });
    }

    private ToolResult getConfig(String key) {
        if (key == null || key.isEmpty()) {
            return listConfig();
        }

        String value = settings.get(key);
        if (value == null) {
            return ToolResult.error("配置项不存在: " + key);
        }

        return ToolResult.success(key + " = " + value);
    }

    private ToolResult setConfig(String key, String value) {
        if (key == null || key.isEmpty()) {
            return ToolResult.error("配置键不能为空");
        }
        if (value == null) {
            return ToolResult.error("配置值不能为空");
        }

        boolean success = settings.set(key, value);
        if (!success) {
            return ToolResult.error("不支持的配置项: " + key);
        }

        settings.save();
        return ToolResult.success("配置已更新: " + key + " = " + value);
    }

    private ToolResult listConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("当前配置:\n\n");
        sb.append("模型: ").append(settings.getModel()).append("\n");
        sb.append("Base URL: ").append(settings.getBaseUrl() != null ? settings.getBaseUrl() : "(默认)").append("\n");
        sb.append("Max Tokens: ").append(settings.getMaxTokens()).append("\n");
        sb.append("主题: ").append(settings.getTheme()).append("\n");
        sb.append("快速模式: ").append(settings.isFastMode() ? "开启" : "关闭").append("\n");
        sb.append("Effort: ").append(settings.getEffort()).append("\n");
        sb.append("Passes: ").append(settings.getPasses()).append("\n");
        
        return ToolResult.success(sb.toString().trim());
    }

    @Override
    public boolean isReadOnly(ConfigToolInput input) {
        return !"set".equals(input.getAction());
    }
}
