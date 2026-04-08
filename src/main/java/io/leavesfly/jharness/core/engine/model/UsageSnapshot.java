package io.leavesfly.jharness.core.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 使用量快照
 *
 * 记录 API 调用的 token 使用情况，用于成本追踪。
 */
public class UsageSnapshot {
    private final int inputTokens;
    private final int outputTokens;
    private final int cacheReadInputTokens;
    private final int cacheCreationInputTokens;

    @JsonCreator
    public UsageSnapshot(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens,
            @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens,
            @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheReadInputTokens = cacheReadInputTokens != null ? cacheReadInputTokens : 0;
        this.cacheCreationInputTokens = cacheCreationInputTokens != null ? cacheCreationInputTokens : 0;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public int getCacheReadInputTokens() {
        return cacheReadInputTokens;
    }

    public int getCacheCreationInputTokens() {
        return cacheCreationInputTokens;
    }

    /**
     * 获取总输入 token 数（包含缓存）
     *
     * @return 总输入 token 数
     */
    public int getTotalInputTokens() {
        return inputTokens + cacheReadInputTokens + cacheCreationInputTokens;
    }

    @Override
    public String toString() {
        return "UsageSnapshot{input=" + inputTokens + ", output=" + outputTokens +
                ", cacheRead=" + cacheReadInputTokens + ", cacheCreation=" + cacheCreationInputTokens + "}";
    }
}
