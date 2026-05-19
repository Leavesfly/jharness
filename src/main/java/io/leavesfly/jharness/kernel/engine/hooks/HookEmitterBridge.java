package io.leavesfly.jharness.kernel.engine.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Hook 发射桥。
 *
 * <p>通过反射调用 {@code capability.hook.HookExecutor.execute(HookEvent, Map)}，
 * 让 {@code kernel.engine} 包不直接依赖 {@code capability.hook} 包，
 * 保持内核与扩展能力解耦。任何异常都只记 debug，绝不影响主流程。
 */
public final class HookEmitterBridge {

    private static final Logger logger = LoggerFactory.getLogger(HookEmitterBridge.class);
    private static final String HOOK_EVENT_CLASS = "io.leavesfly.jharness.capability.hook.HookEvent";

    private volatile Object hookExecutor;
    private volatile String sessionId;
    private final Supplier<String> cwdSupplier;

    public HookEmitterBridge(Supplier<String> cwdSupplier) {
        this.cwdSupplier = cwdSupplier;
    }

    public void configure(Object hookExecutor, String sessionId) {
        this.hookExecutor = hookExecutor;
        this.sessionId = sessionId;
    }

    /**
     * 尽力而为发射一次 Hook 事件，eventName 对应 {@code HookEvent} 枚举名。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void fire(String eventName, Map<String, Object> payload) {
        Object emitter = this.hookExecutor;
        if (emitter == null) return;
        try {
            Class<?> hookEventCls = Class.forName(HOOK_EVENT_CLASS);
            Object eventEnum = Enum.valueOf((Class<Enum>) hookEventCls, eventName);

            Map<String, Object> fullPayload = new HashMap<>();
            if (sessionId != null) fullPayload.put("session_id", sessionId);
            String cwd = cwdSupplier == null ? null : cwdSupplier.get();
            if (cwd != null) fullPayload.put("cwd", cwd);
            if (payload != null) fullPayload.putAll(payload);

            Method execute = emitter.getClass().getMethod("execute", hookEventCls, Map.class);
            // 不 join：让 Hook 异步执行，避免拖慢主请求
            execute.invoke(emitter, eventEnum, fullPayload);
        } catch (Throwable t) {
            logger.debug("发射 Hook {} 失败（忽略）: {}", eventName, t.getMessage());
        }
    }
}
