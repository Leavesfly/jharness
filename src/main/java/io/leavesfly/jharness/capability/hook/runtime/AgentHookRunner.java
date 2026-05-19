package io.leavesfly.jharness.capability.hook.runtime;

import io.leavesfly.jharness.capability.hook.HookResult;
import io.leavesfly.jharness.capability.hook.schemas.HookDefinition;

import java.util.List;

/**
 * Agent Hook：启动独立 JHarness Agent 子进程进行深度验证，
 * Agent 可使用工具进行更复杂的检查（限 3 轮 ReAct）。
 */
public class AgentHookRunner implements HookRunner<HookDefinition.AgentHookDefinition> {

    @Override
    public HookResult run(HookDefinition.AgentHookDefinition hook, HookRunContext ctx) {
        String fullPrompt = hook.getPrompt()
                + "\n\nContext:\n" + JavaSubprocessHookRunner.payloadToString(ctx.getPayload())
                + "\n\nPerform your analysis and respond with ALLOW or DENY followed by your reasoning.";

        return JavaSubprocessHookRunner.run(
                hook.getType(),
                fullPrompt,
                hook.getTimeoutSeconds(),
                hook.isBlockOnFailure(),
                List.of("--permission-mode", "full_auto", "--max-turns", "3"),
                "Agent",
                ctx);
    }
}
