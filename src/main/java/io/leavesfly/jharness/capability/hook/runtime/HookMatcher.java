package io.leavesfly.jharness.capability.hook.runtime;

import io.leavesfly.jharness.capability.hook.schemas.HookDefinition;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Hook 匹配器：根据 hook 定义的 matcher 与 payload 判断当前 hook 是否生效。
 *
 * matcher 为空 → 匹配一切；否则用 fnmatch（仅 *、? 通配符）匹配 payload 中的
 * tool_name → prompt → event 字段。
 */
public final class HookMatcher {

    private HookMatcher() {}

    public static boolean matches(Object hookDef, Map<String, Object> payload) {
        String matcher = extractMatcher(hookDef);
        if (matcher == null || matcher.isEmpty()) {
            return true;
        }
        String subject = extractSubject(payload);
        return fnmatch(subject, matcher);
    }

    private static String extractMatcher(Object hookDef) {
        if (hookDef instanceof HookDefinition.CommandHookDefinition c) return c.getMatcher();
        if (hookDef instanceof HookDefinition.HttpHookDefinition h) return h.getMatcher();
        if (hookDef instanceof HookDefinition.PromptHookDefinition p) return p.getMatcher();
        if (hookDef instanceof HookDefinition.AgentHookDefinition a) return a.getMatcher();
        return null;
    }

    private static String extractSubject(Map<String, Object> payload) {
        if (payload.containsKey("tool_name")) {
            return String.valueOf(payload.get("tool_name"));
        }
        if (payload.containsKey("prompt")) {
            return String.valueOf(payload.get("prompt"));
        }
        if (payload.containsKey("event")) {
            return String.valueOf(payload.get("event"));
        }
        return "";
    }

    /**
     * 简单的 fnmatch 实现（支持 * 和 ? 通配符），通配符外字符用 Pattern.quote 包裹防注入。
     */
    static boolean fnmatch(String str, String pattern) {
        StringBuilder regex = new StringBuilder(pattern.length() + 8);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.matches(regex.toString(), str);
    }
}
