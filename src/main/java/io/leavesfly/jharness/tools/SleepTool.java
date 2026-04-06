package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.SleepToolInput;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 睡眠工具
 *
 * 延迟执行指定的秒数。用于等待外部进程或冷却。
 */
public class SleepTool extends BaseTool<SleepToolInput> {
    @Override
    public String getName() {
        return "sleep";
    }

    @Override
    public String getDescription() {
        return "延迟执行指定的秒数。用于等待外部进程完成或避免速率限制。";
    }

    @Override
    public Class<SleepToolInput> getInputClass() {
        return SleepToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(SleepToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int seconds = Math.min(input.getSeconds(), 60); // 最多 60 秒
                TimeUnit.SECONDS.sleep(seconds);
                return ToolResult.success("已等待 " + seconds + " 秒");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("睡眠被中断");
            }
        });
    }

    @Override
    public boolean isReadOnly(SleepToolInput input) {
        return true;
    }
}
