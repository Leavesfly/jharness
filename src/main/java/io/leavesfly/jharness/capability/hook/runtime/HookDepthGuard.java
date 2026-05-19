package io.leavesfly.jharness.capability.hook.runtime;

/**
 * Hook 递归深度门禁。
 *
 * Prompt/Agent Hook 会 fork 子进程调用 JHarness 本身，子进程若又触发 Hook 会递归爆炸。
 * 本工具用 ThreadLocal 维护当前线程深度，并通过环境变量 JHARNESS_HOOK_DEPTH
 * 跨进程传递，超过 {@link #MAX_HOOK_DEPTH} 即拒绝执行。
 */
public final class HookDepthGuard {

    /** Hook 递归最大深度。3 层已足够覆盖"Hook → Hook 触发 Agent → Agent 里又触发 Hook"场景。 */
    public static final int MAX_HOOK_DEPTH = 3;

    private static final ThreadLocal<Integer> HOOK_DEPTH = ThreadLocal.withInitial(() -> 0);

    private HookDepthGuard() {}

    /**
     * 读取当前线程深度。若 ThreadLocal 未设置，则回退读取父进程通过环境变量传入的深度。
     */
    public static int currentDepth() {
        Integer local = HOOK_DEPTH.get();
        if (local != null && local > 0) {
            return local;
        }
        String env = System.getenv("JHARNESS_HOOK_DEPTH");
        if (env == null || env.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(env.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void enter(int depth) {
        HOOK_DEPTH.set(depth);
    }

    public static void leave() {
        HOOK_DEPTH.remove();
    }
}
