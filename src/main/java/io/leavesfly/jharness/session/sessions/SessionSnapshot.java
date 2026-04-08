package io.leavesfly.jharness.session.sessions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.leavesfly.jharness.core.engine.model.ConversationMessage;
import io.leavesfly.jharness.core.engine.model.UsageSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * 会话快照
 *
 * 表示一个会话的完整快照，包含消息历史和元数据。
 */
public class SessionSnapshot {
    private final String sessionId;
    private final String cwd;
    private final String model;
    private final List<ConversationMessage> messages;
    private final UsageSnapshot usage;
    private final Instant createdAt;
    private final String summary;
    private final int messageCount;

    @JsonCreator
    public SessionSnapshot(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("cwd") String cwd,
            @JsonProperty("model") String model,
            @JsonProperty("messages") List<ConversationMessage> messages,
            @JsonProperty("usage") UsageSnapshot usage,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("summary") String summary,
            @JsonProperty("message_count") int messageCount) {
        this.sessionId = sessionId;
        this.cwd = cwd;
        this.model = model;
        this.messages = messages;
        this.usage = usage;
        this.createdAt = createdAt;
        this.summary = summary;
        this.messageCount = messageCount;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getCwd() {
        return cwd;
    }

    public String getModel() {
        return model;
    }

    public List<ConversationMessage> getMessages() {
        return messages;
    }

    public UsageSnapshot getUsage() {
        return usage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getSummary() {
        return summary;
    }

    public int getMessageCount() {
        return messageCount;
    }
}
