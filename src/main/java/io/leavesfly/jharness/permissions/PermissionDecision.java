package io.leavesfly.jharness.permissions;

/**
 * 权限决策结果
 *
 * 表示权限检查器对某个操作的决策结果。
 */
public class PermissionDecision {
    private final boolean allowed;
    private final boolean requiresConfirmation;
    private final String reason;

    public PermissionDecision(boolean allowed) {
        this(allowed, false, null);
    }

    public PermissionDecision(boolean allowed, boolean requiresConfirmation, String reason) {
        this.allowed = allowed;
        this.requiresConfirmation = requiresConfirmation;
        this.reason = reason;
    }

    /**
     * 创建允许决策
     */
    public static PermissionDecision allow() {
        return new PermissionDecision(true);
    }

    /**
     * 创建拒绝决策
     */
    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(false, false, reason);
    }

    /**
     * 创建需要确认的决策
     */
    public static PermissionDecision requiresConfirmation(String reason) {
        return new PermissionDecision(false, true, reason);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        if (allowed) return "PermissionDecision{allowed}";
        if (requiresConfirmation) return "PermissionDecision{requiresConfirmation, reason='" + reason + "'}";
        return "PermissionDecision{denied, reason='" + reason + "'}";
    }
}
