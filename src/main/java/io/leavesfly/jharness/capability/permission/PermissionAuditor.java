package io.leavesfly.jharness.capability.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 权限审计日志输出。
 *
 * 字段为 key=value 形式，便于日志平台按字段切片。审计失败降级到 debug，不影响主链路。
 */
public final class PermissionAuditor {

    private static final Logger logger = LoggerFactory.getLogger(PermissionAuditor.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("jharness.permission.audit");

    private PermissionAuditor() {}

    public static void audit(String toolName, boolean readOnly, PermissionMode mode,
                             String filePath, String command, PermissionDecision decision) {
        try {
            auditLogger.info("[PERM] tool={} ro={} mode={} decision={} reason=\"{}\" path=\"{}\" cmd=\"{}\"",
                    toolName, readOnly, mode, decisionStr(decision),
                    shorten(decision != null ? decision.getReason() : null, 200),
                    shorten(filePath, 200),
                    shorten(command, 200));
        } catch (Exception e) {
            logger.debug("审计日志输出失败（忽略）", e);
        }
    }

    public static void auditModeChange(PermissionMode oldMode, PermissionMode newMode) {
        auditLogger.info("[PERM] mode_changed: {} -> {}", oldMode, newMode);
    }

    private static String decisionStr(PermissionDecision decision) {
        if (decision == null) {
            return "null";
        }
        if (decision.isAllowed()) {
            return "allow";
        }
        if (decision.isRequiresConfirmation()) {
            return "confirm";
        }
        return "deny";
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[truncated " + (s.length() - max) + "B]";
    }
}
