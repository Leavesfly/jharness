package io.leavesfly.jharness.core.engine;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 模型定价表（F-P0-5）。
 *
 * 维护常见模型的 token 价格（单位：美元 / 1K tokens），用于本地成本估算。
 *
 * 注意事项：
 * - 价格随供应商官方公告变动，本表以调研时的公开价格为准；
 * - 匹配策略：按模型名前缀匹配（例如 "gpt-4o-mini-2024-07-18" 匹配 "gpt-4o-mini"），
 *   未命中则使用 {@link #DEFAULT_PRICING}；
 * - 阿里云 DashScope 的 qwen 系列按人民币计价，此处统一换算为美元便于横向对比
 *   （汇率按 7.2 粗略估算，仅用于粗粒度成本提示，不用于计费）。
 */
public final class ModelPricing {

    /** 单一模型的价格信息（USD / 1K tokens）。 */
    public record Pricing(BigDecimal inputPer1K, BigDecimal outputPer1K) {
        public BigDecimal cost(long inputTokens, long outputTokens) {
            BigDecimal in = inputPer1K.multiply(BigDecimal.valueOf(inputTokens))
                    .divide(BigDecimal.valueOf(1000L));
            BigDecimal out = outputPer1K.multiply(BigDecimal.valueOf(outputTokens))
                    .divide(BigDecimal.valueOf(1000L));
            return in.add(out);
        }
    }

    /** 未识别模型时的兜底价格（按 gpt-4o-mini 估算，偏保守）。 */
    public static final Pricing DEFAULT_PRICING = new Pricing(
            new BigDecimal("0.00015"), new BigDecimal("0.0006"));

    /** 前缀 -> 价格表。按最长前缀匹配原则查找。 */
    private static final Map<String, Pricing> PRICING = new HashMap<>();

    static {
        // OpenAI
        PRICING.put("gpt-4o-mini", new Pricing(new BigDecimal("0.00015"), new BigDecimal("0.0006")));
        PRICING.put("gpt-4o", new Pricing(new BigDecimal("0.0025"), new BigDecimal("0.01")));
        PRICING.put("gpt-4-turbo", new Pricing(new BigDecimal("0.01"), new BigDecimal("0.03")));
        PRICING.put("gpt-4", new Pricing(new BigDecimal("0.03"), new BigDecimal("0.06")));
        PRICING.put("gpt-3.5-turbo", new Pricing(new BigDecimal("0.0005"), new BigDecimal("0.0015")));
        PRICING.put("o1-mini", new Pricing(new BigDecimal("0.003"), new BigDecimal("0.012")));
        PRICING.put("o1", new Pricing(new BigDecimal("0.015"), new BigDecimal("0.06")));
        PRICING.put("o3-mini", new Pricing(new BigDecimal("0.0011"), new BigDecimal("0.0044")));

        // Anthropic Claude
        PRICING.put("claude-3-5-sonnet", new Pricing(new BigDecimal("0.003"), new BigDecimal("0.015")));
        PRICING.put("claude-3-5-haiku", new Pricing(new BigDecimal("0.0008"), new BigDecimal("0.004")));
        PRICING.put("claude-3-opus", new Pricing(new BigDecimal("0.015"), new BigDecimal("0.075")));
        PRICING.put("claude-3-sonnet", new Pricing(new BigDecimal("0.003"), new BigDecimal("0.015")));
        PRICING.put("claude-3-haiku", new Pricing(new BigDecimal("0.00025"), new BigDecimal("0.00125")));

        // DeepSeek（USD）
        PRICING.put("deepseek-chat", new Pricing(new BigDecimal("0.00027"), new BigDecimal("0.0011")));
        PRICING.put("deepseek-reasoner", new Pricing(new BigDecimal("0.00055"), new BigDecimal("0.00219")));

        // Qwen（RMB->USD 按 7.2 换算，仅粗略）
        PRICING.put("qwen3-max", new Pricing(new BigDecimal("0.0028"), new BigDecimal("0.0111")));
        PRICING.put("qwen-max", new Pricing(new BigDecimal("0.0028"), new BigDecimal("0.0111")));
        PRICING.put("qwen-plus", new Pricing(new BigDecimal("0.00056"), new BigDecimal("0.00167")));
        PRICING.put("qwen-turbo", new Pricing(new BigDecimal("0.00011"), new BigDecimal("0.00028")));

        // Moonshot
        PRICING.put("moonshot-v1-8k", new Pricing(new BigDecimal("0.00167"), new BigDecimal("0.00167")));
        PRICING.put("moonshot-v1-32k", new Pricing(new BigDecimal("0.00333"), new BigDecimal("0.00333")));
        PRICING.put("moonshot-v1-128k", new Pricing(new BigDecimal("0.00833"), new BigDecimal("0.00833")));
    }

    private ModelPricing() {
        // 工具类禁止实例化
    }

    /**
     * 根据模型名查询价格，按最长前缀匹配，未命中返回 {@link #DEFAULT_PRICING}。
     */
    public static Pricing lookup(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return DEFAULT_PRICING;
        }
        String normalized = modelName.toLowerCase();
        String bestKey = null;
        for (String key : PRICING.keySet()) {
            if (normalized.startsWith(key) && (bestKey == null || key.length() > bestKey.length())) {
                bestKey = key;
            }
        }
        return bestKey == null ? DEFAULT_PRICING : PRICING.get(bestKey);
    }

    /**
     * 计算成本（USD）。
     */
    public static BigDecimal estimateCost(String modelName, long inputTokens, long outputTokens) {
        return lookup(modelName).cost(inputTokens, outputTokens);
    }
}
