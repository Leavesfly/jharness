package io.leavesfly.jharness.command.builtin.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.kernel.engine.model.ContentBlock;
import io.leavesfly.jharness.kernel.engine.model.ConversationMessage;
import io.leavesfly.jharness.kernel.engine.model.MessageRole;
import io.leavesfly.jharness.kernel.engine.model.ToolUseBlock;
import io.leavesfly.jharness.util.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话历史的纯算法操作：导出 Markdown、按用户轮次回退、压缩成摘要 + 最近 N 条。
 *
 * 与命令处理解耦，便于在 /share、/export、/rewind、/compact 多处复用。
 */
public final class SessionTranscript {

    private static final Logger logger = LoggerFactory.getLogger(SessionTranscript.class);
    private static final ObjectMapper JSON = JacksonUtils.PRETTY_MAPPER;

    private SessionTranscript() {}

    /**
     * 将整个对话历史以 Markdown 形式写入 {@code <sessionDir>/transcript.md}。
     */
    public static Path exportMarkdown(Path cwd, List<ConversationMessage> messages) {
        Path sessionDir = SessionCommandSupport.getSessionDirForCwd(cwd);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            logger.warn("创建会话目录失败: {}", sessionDir, e);
        }

        Path path = sessionDir.resolve("transcript.md");
        StringBuilder sb = new StringBuilder("# JHarness Session Transcript\n");

        for (ConversationMessage message : messages) {
            String role = message.getRole().name().substring(0, 1).toUpperCase()
                    + message.getRole().name().substring(1).toLowerCase();
            sb.append("\n## ").append(role).append("\n");

            String text = SessionCommandSupport.extractText(message);
            if (text != null && !text.isEmpty()) {
                sb.append(text).append("\n");
            }

            for (ContentBlock content : message.getContent()) {
                if (content instanceof ToolUseBlock toolUse) {
                    try {
                        String inputJson = JSON.writeValueAsString(toolUse.getInput());
                        sb.append("\n```tool\n").append(toolUse.getName())
                                .append(" ").append(inputJson).append("\n```\n");
                    } catch (IOException e) {
                        sb.append("\n```tool\n").append(toolUse.getName()).append("\n```\n");
                    }
                }
            }
        }

        try {
            Files.writeString(path, sb.toString());
        } catch (IOException e) {
            logger.error("导出 Markdown 失败", e);
        }
        return path;
    }

    /**
     * 按用户轮次回退：每次回退会从尾部弹出直到遇到一条带文本的 USER 消息。
     */
    public static List<ConversationMessage> rewindTurns(List<ConversationMessage> messages, int turns) {
        List<ConversationMessage> result = new ArrayList<>(messages);
        for (int t = 0; t < Math.max(0, turns); t++) {
            if (result.isEmpty()) break;
            while (!result.isEmpty()) {
                ConversationMessage popped = result.remove(result.size() - 1);
                if (MessageRole.USER.equals(popped.getRole()) && SessionCommandSupport.hasTextContent(popped)) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 压缩历史：把较旧的若干条合并为 "[对话摘要]\n..."，与最近 preserveRecent 条拼接返回。
     */
    public static List<ConversationMessage> compact(List<ConversationMessage> messages, int preserveRecent) {
        if (messages.size() <= preserveRecent) {
            return new ArrayList<>(messages);
        }

        List<ConversationMessage> older = messages.subList(0, messages.size() - preserveRecent);
        List<ConversationMessage> newer = messages.subList(messages.size() - preserveRecent, messages.size());

        String summary = summarize(older);
        if (summary == null || summary.isEmpty()) {
            return new ArrayList<>(newer);
        }

        List<ConversationMessage> result = new ArrayList<>();
        result.add(ConversationMessage.assistantText("[对话摘要]\n" + summary));
        result.addAll(newer);
        return result;
    }

    private static String summarize(List<ConversationMessage> messages) {
        int maxMessages = 8;
        List<String> lines = new ArrayList<>();

        for (int i = Math.max(0, messages.size() - maxMessages); i < messages.size(); i++) {
            ConversationMessage msg = messages.get(i);
            String text = SessionCommandSupport.extractText(msg);
            if (text != null && !text.isEmpty()) {
                String preview = text.length() > 300 ? text.substring(0, 300) : text;
                lines.add(msg.getRole().name().toLowerCase() + ": " + preview);
            }
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }
}
