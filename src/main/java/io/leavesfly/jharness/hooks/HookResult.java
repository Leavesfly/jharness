package io.leavesfly.jharness.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 钩子执行结果
 */
public class HookResult {
    private final String hookType;
    private final boolean success;
    private final String output;
    private final boolean blocked;
    private final String reason;

    public HookResult(String hookType, boolean success, String output, boolean blocked, String reason) {
        this.hookType = hookType;
        this.success = success;
        this.output = output;
        this.blocked = blocked;
        this.reason = reason;
    }

    public String getHookType() {
        return hookType;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getOutput() {
        return output;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public String getReason() {
        return reason;
    }
}
