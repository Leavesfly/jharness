package io.leavesfly.jharness.capability.hook.runtime;

import io.leavesfly.jharness.capability.hook.HookResult;
import io.leavesfly.jharness.capability.hook.schemas.HookDefinition;

import java.util.List;

/**
 * Prompt Hook：在子进程中以 plan 权限模式运行 JHarness 单次查询，
 * 由 LLM 根据 hook prompt + payload 判断 ALLOW/DENY。
 */
public class PromptHookRunner implements HookRunner<HookDefinition.PromptHookDefinition> {

    @Override
    public HookResult run(HookDefinition.PromptHookDefinition hook, HookRunContext ctx) {
        String fullPrompt = hook.getPrompt()
                + "\n\nPayload:\n" + JavaSubprocessHookRunner.payloadToString(ctx.getPayload())
                + "\n\nRespond with ONLY 'ALLOW' or 'DENY' followed by a brief reason.";

        return JavaSubprocessHookRunner.run(
                hook.getType(),
                fullPrompt,
                hook.getTimeoutSeconds(),
                hook.isBlockOnFailure(),
                List.of("--permission-mode", "plan"),
                "Prompt",
                ctx);
    }
}
