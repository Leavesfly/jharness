package io.leavesfly.jharness.core.engine;

import io.leavesfly.jharness.core.engine.model.ConversationMessage;
import io.leavesfly.jharness.core.engine.model.ContentBlock;
import io.leavesfly.jharness.core.engine.model.MessageRole;
import io.leavesfly.jharness.core.engine.model.TextBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息压缩服务
 *
 * 当对话历史过长时，压缩早期消息以节省 Token。
 * 策略：保留最新 N 条消息，将早期消息压缩为摘要。
 *
 * F-P0-2 升级：支持基于 token 总量的触发（优先于条数阈值）。
 * - 若设置 maxTokenBudget（&gt;0），当估算总 token 超过该值时触发压缩；
 * - 条数阈值仍保留作为兜底，避免即使 token 少但消息太多也会带来响应延迟。
 */
public class MessageCompactionService {

    private static final int DEFAULT_MAX_MESSAGES = 20;
    private static final int DEFAULT_SUMMARY_MESSAGES = 5;
    /** 默认 token 预算：大多数模型上下文在 8k-128k，取 32k 作为安全的压缩触发阈值。 */
    private static final int DEFAULT_MAX_TOKEN_BUDGET = 32_000;

    private final int maxMessages;
    private final int summaryMessages;
    private final int maxTokenBudget;

    public MessageCompactionService() {
        this(DEFAULT_MAX_MESSAGES, DEFAULT_SUMMARY_MESSAGES, DEFAULT_MAX_TOKEN_BUDGET);
    }

    public MessageCompactionService(int maxMessages, int summaryMessages) {
        this(maxMessages, summaryMessages, DEFAULT_MAX_TOKEN_BUDGET);
    }

    public MessageCompactionService(int maxMessages, int summaryMessages, int maxTokenBudget) {
        this.maxMessages = maxMessages;
        this.summaryMessages = summaryMessages;
        this.maxTokenBudget = maxTokenBudget;
    }

    /**
     * 压缩消息列表。
     *
     * F-P0-2 升级：如果仅按条数压缩后 token 仍超预算，则继续迭代压缩，
     * 每轮将"保留条数"减半直到满足预算或仅剩 2 条（保留摘要 + 最新一条用户消息）。
     *
     * @param messages 原始消息列表
     * @return 压缩后的消息列表
     */
    public List<ConversationMessage> compact(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }

        // 第一次：按条数压缩
        List<ConversationMessage> result = compactByCount(messages, maxMessages);

        // 若设置了 token 预算且仍超限，则按 token 预算迭代收紧保留条数
        if (maxTokenBudget > 0) {
            int keep = Math.max(maxMessages, 2);
            while (TokenEstimator.estimateMessages(result) > maxTokenBudget && keep > 2) {
                keep = Math.max(2, keep / 2);
                result = compactByCount(messages, keep);
            }
        }

        return result;
    }

    /**
     * 按"保留最新 targetKeep 条 + 1 条摘要"的策略压缩。
     */
    private List<ConversationMessage> compactByCount(List<ConversationMessage> messages, int targetKeep) {
        if (messages.size() <= targetKeep) {
            return new ArrayList<>(messages);
        }

        List<ConversationMessage> result = new ArrayList<>();

        int earlyCount = Math.min(summaryMessages, messages.size() - targetKeep + 1);
        int keepFromEnd = Math.min(Math.max(targetKeep - 1, 1), messages.size() - 1);

        List<ConversationMessage> earlyMessages = messages.subList(0, earlyCount);
        String summary = createSummary(earlyMessages);
        ConversationMessage summaryMsg = new ConversationMessage(
                MessageRole.USER, List.of(new TextBlock("[对话摘要] " + summary)));
        result.add(summaryMsg);

        List<ConversationMessage> recentMessages = messages.subList(
                messages.size() - keepFromEnd, messages.size());
        result.addAll(recentMessages);

        return result;
    }

    /**
     * 创建消息摘要
     */
    private String createSummary(List<ConversationMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户和助手进行了 ").append(messages.size()).append(" 轮对话。");

        // 提取关键信息
        List<String> topics = new ArrayList<>();
        List<String> toolActions = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock textBlock) {
                    String text = textBlock.getText();
                    if (text.length() > 100) {
                        topics.add(text.substring(0, 100) + "...");
                    } else {
                        topics.add(text);
                    }
                } else {
                    // 记录非文本块的类型信息（如工具调用、工具结果等）
                    String blockType = block.getClass().getSimpleName();
                    if (!toolActions.contains(blockType)) {
                        toolActions.add(blockType);
                    }
                }
            }
        }

        if (!topics.isEmpty()) {
            sb.append("主要话题: ").append(String.join("; ", topics.subList(0, Math.min(3, topics.size()))));
        }

        if (!toolActions.isEmpty()) {
            sb.append(" 涉及操作类型: ").append(String.join(", ", toolActions)).append("。");
        }

        return sb.toString();
    }

    /**
     * 判断是否需要压缩（F-P0-2：条数 OR token 预算 任一超限即触发）。
     */
    public boolean needsCompaction(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        if (messages.size() > maxMessages) {
            return true;
        }
        if (maxTokenBudget > 0) {
            int estimated = TokenEstimator.estimateMessages(messages);
            return estimated > maxTokenBudget;
        }
        return false;
    }

    /** 暴露当前配置的 token 预算，供日志和 UI 展示。 */
    public int getMaxTokenBudget() {
        return maxTokenBudget;
    }
}
