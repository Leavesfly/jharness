package io.leavesfly.jharness.hooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Hook 注册表
 * 
 * 按 HookEvent 分组管理所有已注册的 Hook 定义。
 */
public class HookRegistry {
    private final Map<HookEvent, List<Object>> hooksByEvent = new HashMap<>();

    /**
     * 注册 Hook
     */
    public void register(HookEvent event, Object hookDefinition) {
        hooksByEvent.computeIfAbsent(event, k -> new ArrayList<>()).add(hookDefinition);
    }

    /**
     * 获取指定事件的所有 Hook
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> get(HookEvent event) {
        return (List<T>) hooksByEvent.getOrDefault(event, new ArrayList<>());
    }

    /**
     * 获取所有事件的 Hook 摘要
     */
    public String summary() {
        List<String> lines = new ArrayList<>();
        lines.add("Registered hooks:");
        
        for (HookEvent event : HookEvent.values()) {
            List<?> hooks = hooksByEvent.getOrDefault(event, new ArrayList<>());
            if (!hooks.isEmpty()) {
                lines.add(String.format("  %s: %d hook(s)", event, hooks.size()));
                for (Object hook : hooks) {
                    lines.add(String.format("    - %s", hook.getClass().getSimpleName()));
                }
            }
        }

        int total = hooksByEvent.values().stream().mapToInt(List::size).sum();
        if (total == 0) {
            return "No hooks registered.";
        }

        return lines.stream().collect(Collectors.joining("\n"));
    }

    /**
     * 获取总 Hook 数
     */
    public int size() {
        return hooksByEvent.values().stream().mapToInt(List::size).sum();
    }
}
