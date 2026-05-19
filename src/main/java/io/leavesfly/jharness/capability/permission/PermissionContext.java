package io.leavesfly.jharness.capability.permission;

import java.util.List;
import java.util.Set;

/**
 * 权限规则链上下文。
 *
 * 承载本次评估的全部输入参数与跨规则共享的中间状态（如 {@code pathAllowHit}）。
 * 为不可变上下文 + 可变标志位的组合：输入参数 final，标志位由前序规则写入。
 */
public class PermissionContext {

    private final String toolName;
    private final boolean readOnly;
    private final String filePath;
    private final String command;
    private final PermissionMode mode;

    // 共享状态：路径白名单命中（用于 DEFAULT 模式跳过确认）
    private boolean pathAllowHit;

    // 共享只读视图：规则链所需的策略集合，避免每个规则各自持有 checker
    private final Set<String> deniedTools;
    private final Set<String> deniedCommands;
    private final Set<String> allowedTools;
    private final List<PathRule> pathRules;

    public PermissionContext(String toolName, boolean readOnly, String filePath, String command,
                             PermissionMode mode,
                             Set<String> deniedTools, Set<String> deniedCommands,
                             Set<String> allowedTools, List<PathRule> pathRules) {
        this.toolName = toolName;
        this.readOnly = readOnly;
        this.filePath = filePath;
        this.command = command;
        this.mode = mode;
        this.deniedTools = deniedTools;
        this.deniedCommands = deniedCommands;
        this.allowedTools = allowedTools;
        this.pathRules = pathRules;
    }

    public String getToolName() { return toolName; }
    public boolean isReadOnly() { return readOnly; }
    public String getFilePath() { return filePath; }
    public String getCommand() { return command; }
    public PermissionMode getMode() { return mode; }

    public Set<String> getDeniedTools() { return deniedTools; }
    public Set<String> getDeniedCommands() { return deniedCommands; }
    public Set<String> getAllowedTools() { return allowedTools; }
    public List<PathRule> getPathRules() { return pathRules; }

    public boolean isPathAllowHit() { return pathAllowHit; }
    public void markPathAllowHit() { this.pathAllowHit = true; }
}
