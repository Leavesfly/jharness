package io.leavesfly.jharness.capability.hook;

import io.leavesfly.jharness.capability.hook.runtime.AgentHookRunner;
import io.leavesfly.jharness.capability.hook.runtime.CommandHookRunner;
import io.leavesfly.jharness.capability.hook.runtime.HookDepthGuard;
import io.leavesfly.jharness.capability.hook.runtime.HookMatcher;
import io.leavesfly.jharness.capability.hook.runtime.HookRunContext;
import io.leavesfly.jharness.capability.hook.runtime.HttpHookRunner;
import io.leavesfly.jharness.capability.hook.runtime.PromptHookRunner;
import io.leavesfly.jharness.capability.hook.schemas.HookDefinition;
import io.leavesfly.jharness.capability.permission.PermissionChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Hook 执行编排者。
 *
 * 自身仅负责装配 Runner、按 hook 类型路由、递归深度门禁与并发执行；
 * 各类型 hook 的真实执行逻辑下沉到 {@code capability/hook/runtime/} 下的 4 个 Runner：
 *   - {@link CommandHookRunner}
 *   - {@link HttpHookRunner}
 *   - {@link PromptHookRunner}
 *   - {@link AgentHookRunner}
 *
 * 横切能力（深度计数 / matcher / 子进程 IO）也下沉为独立工具类：
 *   - {@link HookDepthGuard}    ThreadLocal + env 跨进程深度计数
 *   - {@link HookMatcher}       payload matcher 匹配
 *   - {@link io.leavesfly.jharness.capability.hook.runtime.SubprocessIo}
 */
public class HookExecutor {
    private static final Logger logger = LoggerFactory.getLogger(HookExecutor.class);

    private final HookRegistry registry;
    private final java.nio.file.Path cwd;

    private final CommandHookRunner commandRunner = new CommandHookRunner();
    private final HttpHookRunner httpRunner = new HttpHookRunner();
    private final PromptHookRunner promptRunner = new PromptHookRunner();
    private final AgentHookRunner agentRunner = new AgentHookRunner();

    /**
     * 可选的 PermissionChecker。注入后，Command Hook 在 fork 子进程前会先走一次
     * 权限评估（按 "bash" 工具名 + 命令字符串）。未注入时保持旧行为。
     */
    private volatile PermissionChecker permissionChecker;

    public HookExecutor(HookRegistry registry, java.nio.file.Path cwd) {
        this.registry = registry;
        this.cwd = cwd;
    }

    /**
     * 注入 PermissionChecker，使 Command Hook 与前台工具共用同一套权限栅栏。
     */
    public void setPermissionChecker(PermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    /**
     * 执行指定事件的所有 Hook。
     *
     * 递归深度防护：入口检查 {@link HookDepthGuard#currentDepth()}，若 >= MAX_HOOK_DEPTH
     * 则拒绝所有 Hook 并返回 blocking 结果；执行过程中深度 +1，子进程通过环境变量
     * JHARNESS_HOOK_DEPTH 继续累计。
     */
    public CompletableFuture<List<HookResult>> execute(HookEvent event, Map<String, Object> payload) {
        int enterDepth = HookDepthGuard.currentDepth();
        if (enterDepth >= HookDepthGuard.MAX_HOOK_DEPTH) {
            HookResult blocked = new HookResult(
                    "depth-guard", false, null, true,
                    "Hook 递归深度超过上限 " + HookDepthGuard.MAX_HOOK_DEPTH + "，已阻止继续触发");
            logger.warn("Hook 递归深度 {} 已达上限 {}，事件 {} 被阻断",
                    enterDepth, HookDepthGuard.MAX_HOOK_DEPTH, event);
            return CompletableFuture.completedFuture(List.of(blocked));
        }

        return CompletableFuture.supplyAsync(() -> {
            logger.debug("执行 Hook 事件: {} (depth={})", event, enterDepth);

            HookDepthGuard.enter(enterDepth + 1);
            try {
                HookRunContext ctx = new HookRunContext(
                        event, payload, cwd, permissionChecker, enterDepth + 1);

                List<HookResult> results = new ArrayList<>();
                for (Object hookDef : registry.get(event)) {
                    if (!HookMatcher.matches(hookDef, payload)) {
                        continue;
                    }
                    HookResult result = dispatch(hookDef, ctx);
                    if (result != null) {
                        results.add(result);
                    }
                }
                return results;
            } finally {
                HookDepthGuard.leave();
            }
        });
    }

    /** 按 hook 类型路由到具体 Runner。未识别的类型返回 null（被 execute 跳过）。 */
    private HookResult dispatch(Object hookDef, HookRunContext ctx) {
        if (hookDef instanceof HookDefinition.CommandHookDefinition c) {
            return commandRunner.run(c, ctx);
        }
        if (hookDef instanceof HookDefinition.HttpHookDefinition h) {
            return httpRunner.run(h, ctx);
        }
        if (hookDef instanceof HookDefinition.PromptHookDefinition p) {
            return promptRunner.run(p, ctx);
        }
        if (hookDef instanceof HookDefinition.AgentHookDefinition a) {
            return agentRunner.run(a, ctx);
        }
        return null;
    }
}
