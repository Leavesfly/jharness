package io.leavesfly.jharness.engine;

import io.leavesfly.jharness.engine.model.ContentBlock;
import io.leavesfly.jharness.engine.model.ConversationMessage;
import io.leavesfly.jharness.engine.model.MessageRole;
import io.leavesfly.jharness.engine.model.TextBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息压缩服务
 *
 * 当对话历史过长时，压缩早期消息以节省 Token。
 * 策略：保留最新 N 条消息，将早期消息压缩为摘要。
 */
public class MessageCompactionService {

    private static final int DEFAULT_MAX_MESSAGES = 20;
    private static final int DEFAULT_SUMMARY_MESSAGES = 5;

    private final int maxMessages;
    private final int summaryMessages;

    public MessageCompactionService() {
        this(DEFAULT_MAX_MESSAGES, DEFAULT_SUMMARY_MESSAGES);
    }

    public MessageCompactionService(int maxMessages, int summaryMessages) {
        this.maxMessages = maxMessages;
        this.summaryMessages = summaryMessages;
    }

    /**
     * 压缩消息列表
     *
     * @param messages 原始消息列表
     * @return 压缩后的消息列表
     */
    public List<ConversationMessage> compact(List<ConversationMessage> messages) {
        if (messages.size() <= maxMessages) {
            return new ArrayList<>(messages);
        }

        List<ConversationMessage> result = new ArrayList<>();

        // 计算需要保留的早期消息数量
        int earlyCount = Math.min(summaryMessages, messages.size() - maxMessages + 1);
        int keepFromEnd = maxMessages - 1; // -1 为摘要消息预留空间

        // 确保 keepFromEnd 不超过消息总数
        keepFromEnd = Math.min(keepFromEnd, messages.size() - 1);

        // 创建摘要（使用 USER 角色，因为 MessageRole 不包含 SYSTEM）
        List<ConversationMessage> earlyMessages = messages.subList(0, earlyCount);
        String summary = createSummary(earlyMessages);
        ConversationMessage summaryMsg = new ConversationMessage(MessageRole.USER, List.of(new TextBlock("[对话摘要] " + summary)));
        result.add(summaryMsg);

        // 保留最新的消息
        List<ConversationMessage> recentMessages = messages.subList(messages.size() - keepFromEnd, messages.size());
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
     * 判断是否需要压缩
     */
    public boolean needsCompaction(List<ConversationMessage> messages) {
        return messages.size() > maxMessages;
    }
}
