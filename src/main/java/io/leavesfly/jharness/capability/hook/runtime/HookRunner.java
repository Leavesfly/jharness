package io.leavesfly.jharness.capability.hook.runtime;

import io.leavesfly.jharness.capability.hook.HookResult;
import io.leavesfly.jharness.capability.hook.schemas.HookDefinition;

/**
 * Hook 执行策略接口。
 *
 * 每种 hook 类型（command/http/prompt/agent）对应一个 Runner 实现，
 * 由 {@link io.leavesfly.jharness.capability.hook.HookExecutor} 按定义类型路由。
 */
public interface HookRunner<D extends HookDefinition> {
    HookResult run(D hook, HookRunContext ctx);
}
