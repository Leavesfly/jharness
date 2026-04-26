package io.leavesfly.jharness.command.commands.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.core.engine.CostTracker;
import io.leavesfly.jharness.core.engine.QueryEngine;
import io.leavesfly.jharness.core.engine.model.*;
import io.leavesfly.jharness.session.sessions.SessionSnapshot;
import io.leavesfly.jharness.session.sessions.SessionStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 会话管理命令 Handler
 * 处理: /resume, /export, /share, /session, /tag, /rewind, /copy, /compact, /context, /summary
 */
public class SessionCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(SessionCommandHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static String joinArgs(List<String> args) {
        return args == null || args.isEmpty() ? "" : String.join(" ", args);
    }

    private static SimpleSlashCommand cmd(String name, String desc, SimpleSlashCommand.CommandHandler handler) {
        return new SimpleSlashCommand(name, desc, handler);
    }

    /**
     * /resume - 恢复会话
     */
    public static SlashCommand createResumeCommand(SessionStorage sessionStorage) {
        return cmd("resume", "恢复会话", (args, ctx, ec) -> {
            String trimmed = joinArgs(args);

            if (trimmed.isEmpty()) {
                return listRecentSessions(sessionStorage, ctx);
            }

            String sessionId = trimmed;
            SessionSnapshot snapshot = sessionStorage.loadSession(sessionId);
            if (snapshot == null) {
                return CompletableFuture.completedFuture(
                        CommandResult.error("未找到会话: " + sessionId));
            }

            QueryEngine engine = ctx.getEngine();
            if (engine != null) {
                engine.loadMessages(snapshot.getMessages());
            }

            String msg = String.format(
                    "已恢复会话 %s\n消息数: %d\n模型: %s\n摘要: %s",
                    sessionId, snapshot.getMessageCount(),
                    snapshot.getModel(), snapshot.getSummary());
            return CompletableFuture.completedFuture(CommandResult.success(msg));
        });
    }

    /**
     * /export - 导出会话记录为 Markdown
     */
    public static SlashCommand createExportCommand() {
        return cmd("export", "导出记录", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }

            Path exportPath = exportSessionMarkdown(ctx.getCwd(), engine.getMessages());
            return CompletableFuture.completedFuture(
                    CommandResult.success("会话已导出为 Markdown:\n" + exportPath));
        });
    }

    /**
     * /share - 创建可分享的快照
     */
    public static SlashCommand createShareCommand(SessionStorage sessionStorage) {
        return cmd("share", "分享快照", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }

            Path exportPath = exportSessionMarkdown(ctx.getCwd(), engine.getMessages());

            String sessionId = UUID.randomUUID().toString().substring(0, 12);
            SessionSnapshot snapshot = createSnapshot(sessionId, ctx, engine);
            sessionStorage.saveSession(snapshot);

            String msg = String.format(
                    "会话快照已创建:\nID: %s\nMarkdown: %s\nJSON: session-%s.json",
                    sessionId, exportPath, sessionId);
            return CompletableFuture.completedFuture(CommandResult.success(msg));
        });
    }

    /**
     * /session - 查看会话信息
     */
    public static SlashCommand createSessionCommand(SessionStorage sessionStorage) {
        return cmd("session", "查看会话", (args, ctx, ec) -> {
            String joined = joinArgs(args);
            String[] parts = joined.isEmpty() ? new String[0] : joined.split("\\s+");
            String subcmd = parts.length > 0 ? parts[0] : "show";

            return switch (subcmd) {
                case "ls" -> listSessionFiles(ctx);
                case "path" -> showSessionPath(ctx);
                case "tag" -> {
                    if (parts.length < 2) {
                        yield CompletableFuture.completedFuture(CommandResult.error("用法: /session tag <名称>"));
                    }
                    yield tagSession(sessionStorage, parts[1], ctx);
                }
                case "clear" -> clearSession(ctx);
                default -> showSessionStatus(sessionStorage, ctx);
            };
        });
    }

    /**
     * /tag - 创建命名快照
     */
    public static SlashCommand createTagCommand(SessionStorage sessionStorage) {
        return cmd("tag", "创建快照", (args, ctx, ec) -> {
            String name = joinArgs(args);
            if (name.isEmpty()) {
                return CompletableFuture.completedFuture(CommandResult.error("用法: /tag <名称>"));
            }
            return tagSession(sessionStorage, name, ctx);
        });
    }

    /**
     * /rewind - 回退对话轮次
     */
    public static SlashCommand createRewindCommand() {
        return cmd("rewind", "回退轮次", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }

            int turns = 1;
            String trimmed = joinArgs(args);
            if (!trimmed.isEmpty()) {
                try {
                    turns = Integer.parseInt(trimmed);
                } catch (NumberFormatException e) {
                    return CompletableFuture.completedFuture(CommandResult.error("无效的轮次数: " + trimmed));
                }
            }

            List<ConversationMessage> messages = engine.getMessages();
            int originalCount = messages.size();
            List<ConversationMessage> rewound = rewindTurns(messages, turns);
            engine.loadMessages(rewound);

            int removed = originalCount - rewound.size();
            return CompletableFuture.completedFuture(
                    CommandResult.success(String.format("已回退 %d 轮（删除 %d 条消息）", turns, removed)));
        });
    }

    /**
     * /copy - 复制最后回复
     */
    public static SlashCommand createCopyCommand() {
        return cmd("copy", "复制回复", (args, ctx, ec) -> {
            String trimmed = joinArgs(args);
            String text;

            if (!trimmed.isEmpty()) {
                text = trimmed;
            } else {
                QueryEngine engine = ctx.getEngine();
                if (engine == null) {
                    return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
                }

                text = findLastAssistantText(engine.getMessages());
                if (text == null) {
                    return CompletableFuture.completedFuture(CommandResult.error("没有找到可复制的回复"));
                }
            }

            Path copyPath = ctx.getCwd().resolve(".openharness/data/last_copy.txt");
            try {
                Files.createDirectories(copyPath.getParent());
                Files.writeString(copyPath, text);
            } catch (IOException e) {
                copyPath = Path.of(System.getProperty("user.home"), ".openharness/data/last_copy.txt");
                try {
                    Files.createDirectories(copyPath.getParent());
                    Files.writeString(copyPath, text);
                } catch (IOException ex) {
                    return CompletableFuture.completedFuture(CommandResult.error("写入失败: " + ex.getMessage()));
                }
            }

            tryCopyToClipboard(text);

            int previewLen = Math.min(text.length(), 100);
            String msg = String.format(
                    "已复制 (%d 字符):\n%s%s\n保存到: %s",
                    text.length(),
                    text.substring(0, previewLen),
                    text.length() > 100 ? "..." : "",
                    copyPath);
            return CompletableFuture.completedFuture(CommandResult.success(msg));
        });
    }

    /**
     * /compact - 压缩历史
     */
    public static SlashCommand createCompactCommand() {
        return cmd("compact", "压缩历史", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }

            int preserveRecent = 6;
            String trimmed = joinArgs(args);
            if (!trimmed.isEmpty()) {
                try {
                    preserveRecent = Integer.parseInt(trimmed);
                } catch (NumberFormatException e) {
                    return CompletableFuture.completedFuture(CommandResult.error("无效的保留数: " + trimmed));
                }
            }

            List<ConversationMessage> messages = engine.getMessages();
            int originalCount = messages.size();

            if (originalCount <= preserveRecent) {
                return CompletableFuture.completedFuture(
                        CommandResult.success(String.format("消息数 (%d) 未超过保留数 (%d)，无需压缩",
                                originalCount, preserveRecent)));
            }

            List<ConversationMessage> compacted = compactMessages(messages, preserveRecent);
            engine.loadMessages(compacted);

            return CompletableFuture.completedFuture(
                    CommandResult.success(String.format("已压缩历史: %d 条消息 -> %d 条消息（保留最近 %d 条）",
                            originalCount, compacted.size(), preserveRecent)));
        });
    }

    /**
     * /context - 显示系统提示词
     */
    public static SlashCommand createContextCommand() {
        return cmd("context", "系统提示词", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(
                        CommandResult.error("查询引擎未初始化"));
            }
            String prompt = engine.getSystemPrompt();
            if (prompt == null || prompt.isBlank()) {
                return CompletableFuture.completedFuture(
                        CommandResult.success("系统提示词未设置"));
            }
            return CompletableFuture.completedFuture(
                    CommandResult.success("=== 当前系统提示词 ===\n" + prompt));
        });
    }

    /**
     * /summary - 总结对话历史
     */
    public static SlashCommand createSummaryCommand() {
        return cmd("summary", "总结历史", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }

            List<ConversationMessage> messages = engine.getMessages();
            if (messages.isEmpty()) {
                return CompletableFuture.completedFuture(CommandResult.success("当前会话为空"));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("对话摘要 (").append(messages.size()).append(" 条消息):\n\n");

            int showCount = Math.min(10, messages.size());
            for (int i = 0; i < showCount; i++) {
                ConversationMessage msg = messages.get(i);
                String text = extractText(msg);
                if (text != null && !text.isEmpty()) {
                    String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                    sb.append(msg.getRole().name().toLowerCase()).append(": ").append(preview).append("\n");
                }
            }

            if (messages.size() > showCount) {
                sb.append("\n... (还有 ").append(messages.size() - showCount).append(" 条消息)");
            }

            return CompletableFuture.completedFuture(CommandResult.success(sb.toString().trim()));
        });
    }

    // === 私有辅助方法 ===

    private static CompletableFuture<CommandResult> listRecentSessions(SessionStorage sessionStorage, CommandContext ctx) {
        List<SessionSnapshot> sessions = sessionStorage.listSessions(10);

        if (sessions.isEmpty()) {
            return CompletableFuture.completedFuture(CommandResult.success("没有找到已保存的会话"));
        }

        StringBuilder sb = new StringBuilder("最近的会话:\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (SessionSnapshot s : sessions) {
            String time = s.getCreatedAt() != null ? fmt.format(s.getCreatedAt()) : "未知";
            sb.append(String.format("  %s - %s (%d 条消息)\n", s.getSessionId(), time, s.getMessageCount()));
            if (s.getSummary() != null) {
                sb.append("    摘要: ").append(s.getSummary()).append("\n");
            }
        }
        sb.append("\n使用 /resume <会话ID> 恢复");

        return CompletableFuture.completedFuture(CommandResult.success(sb.toString().trim()));
    }

    private static CompletableFuture<CommandResult> listSessionFiles(CommandContext ctx) {
        Path sessionDir = getSessionDir(ctx);
        try {
            if (!Files.exists(sessionDir)) {
                return CompletableFuture.completedFuture(CommandResult.success("会话目录不存在"));
            }
            StringBuilder sb = new StringBuilder("会话文件:\n");
            try (var stream = Files.list(sessionDir)) {
                stream.sorted().forEach(p -> sb.append("  ").append(p.getFileName()).append("\n"));
            }
            return CompletableFuture.completedFuture(CommandResult.success(sb.toString().trim()));
        } catch (IOException e) {
            return CompletableFuture.completedFuture(CommandResult.error("列出文件失败: " + e.getMessage()));
        }
    }

    private static CompletableFuture<CommandResult> showSessionPath(CommandContext ctx) {
        return CompletableFuture.completedFuture(
                CommandResult.success("会话目录: " + getSessionDir(ctx)));
    }

    private static CompletableFuture<CommandResult> showSessionStatus(SessionStorage sessionStorage, CommandContext ctx) {
        QueryEngine engine = ctx.getEngine();
        Path sessionDir = getSessionDir(ctx);

        StringBuilder sb = new StringBuilder("会话状态:\n");
        sb.append("  目录: ").append(sessionDir).append("\n");
        sb.append("  消息数: ").append(engine != null ? engine.getMessages().size() : 0).append("\n");
        sb.append("  存在: ").append(Files.exists(sessionDir) ? "是" : "否");

        if (Files.exists(sessionDir.resolve("latest.json"))) {
            sb.append("\n  最新快照: 存在");
        }
        if (Files.exists(sessionDir.resolve("transcript.md"))) {
            sb.append("\n  导出文件: 存在");
        }

        return CompletableFuture.completedFuture(CommandResult.success(sb.toString()));
    }

    private static CompletableFuture<CommandResult> clearSession(CommandContext ctx) {
        Path sessionDir = getSessionDir(ctx);
        try {
            if (Files.exists(sessionDir)) {
                try (var stream = Files.list(sessionDir)) {
                    stream.filter(Files::isRegularFile).forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) { /* ignore */ }
                    });
                }
            }
            Files.createDirectories(sessionDir);

            QueryEngine engine = ctx.getEngine();
            if (engine != null) {
                engine.clear();
            }

            return CompletableFuture.completedFuture(CommandResult.success("会话已清空"));
        } catch (IOException e) {
            return CompletableFuture.completedFuture(CommandResult.error("清除会话失败: " + e.getMessage()));
        }
    }

    private static CompletableFuture<CommandResult> tagSession(SessionStorage sessionStorage, String name,
                                                                CommandContext ctx) {
        QueryEngine engine = ctx.getEngine();
        if (engine == null) {
            return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
        }

        String safeName = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        String sessionId = UUID.randomUUID().toString().substring(0, 12);

        SessionSnapshot snapshot = createSnapshot(sessionId, ctx, engine);
        sessionStorage.saveSession(snapshot);

        Path exportPath = exportSessionMarkdown(ctx.getCwd(), engine.getMessages());
        Path taggedMd = exportPath.getParent().resolve(safeName + ".md");
        try {
            Files.copy(exportPath, taggedMd, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // ignore
        }

        String msg = String.format(
                "快照已标记: %s\nJSON: session-%s.json\nMarkdown: %s",
                safeName, sessionId, taggedMd);
        return CompletableFuture.completedFuture(CommandResult.success(msg));
    }

    // === 核心逻辑方法 ===

    public static Path exportSessionMarkdown(Path cwd, List<ConversationMessage> messages) {
        Path sessionDir = getSessionDirForCwd(cwd);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            // ignore
        }

        Path path = sessionDir.resolve("transcript.md");
        StringBuilder sb = new StringBuilder("# JHarness Session Transcript\n");

        for (ConversationMessage message : messages) {
            String role = message.getRole().name().substring(0, 1).toUpperCase() +
                         message.getRole().name().substring(1).toLowerCase();
            sb.append("\n## ").append(role).append("\n");

            String text = extractText(message);
            if (text != null && !text.isEmpty()) {
                sb.append(text).append("\n");
            }

            for (ContentBlock content : message.getContent()) {
                if (content instanceof ToolUseBlock toolUse) {
                    try {
                        String inputJson = JSON.writeValueAsString(toolUse.getInput());
                        sb.append("\n```tool\n").append(toolUse.getName()).append(" ").append(inputJson).append("\n```\n");
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

    public static List<ConversationMessage> rewindTurns(List<ConversationMessage> messages, int turns) {
        List<ConversationMessage> result = new ArrayList<>(messages);
        for (int t = 0; t < Math.max(0, turns); t++) {
            if (result.isEmpty()) break;
            while (!result.isEmpty()) {
                ConversationMessage popped = result.remove(result.size() - 1);
                if (MessageRole.USER.equals(popped.getRole()) && hasTextContent(popped)) {
                    break;
                }
            }
        }
        return result;
    }

    public static List<ConversationMessage> compactMessages(List<ConversationMessage> messages, int preserveRecent) {
        if (messages.size() <= preserveRecent) {
            return new ArrayList<>(messages);
        }

        List<ConversationMessage> older = messages.subList(0, messages.size() - preserveRecent);
        List<ConversationMessage> newer = messages.subList(messages.size() - preserveRecent, messages.size());

        String summary = summarizeMessages(older);
        if (summary == null || summary.isEmpty()) {
            return new ArrayList<>(newer);
        }

        List<ConversationMessage> result = new ArrayList<>();
        result.add(ConversationMessage.assistantText("[对话摘要]\n" + summary));
        result.addAll(newer);
        return result;
    }

    private static String summarizeMessages(List<ConversationMessage> messages) {
        int maxMessages = 8;
        List<String> lines = new ArrayList<>();

        for (int i = Math.max(0, messages.size() - maxMessages); i < messages.size(); i++) {
            ConversationMessage msg = messages.get(i);
            String text = extractText(msg);
            if (text != null && !text.isEmpty()) {
                String preview = text.length() > 300 ? text.substring(0, 300) : text;
                lines.add(msg.getRole().name().toLowerCase() + ": " + preview);
            }
        }

        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static String extractText(ConversationMessage message) {
        for (ContentBlock content : message.getContent()) {
            if (content instanceof TextBlock textBlock) {
                return textBlock.getText();
            }
        }
        return null;
    }

    private static String findLastAssistantText(List<ConversationMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = messages.get(i);
            if (MessageRole.ASSISTANT.equals(msg.getRole())) {
                String text = extractText(msg);
                if (text != null && !text.trim().isEmpty()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private static boolean hasTextContent(ConversationMessage message) {
        String text = extractText(message);
        return text != null && !text.trim().isEmpty();
    }

    private static SessionSnapshot createSnapshot(String sessionId, CommandContext ctx, QueryEngine engine) {
        List<ConversationMessage> messages = engine.getMessages();
        String summary = messages.stream()
                .filter(m -> MessageRole.USER.equals(m.getRole()))
                .map(SessionCommandHandler::extractText)
                .filter(t -> t != null && !t.isEmpty())
                .findFirst()
                .map(t -> t.length() > 80 ? t.substring(0, 80) : t)
                .orElse("空会话");

        CostTracker tracker = engine.getCostTracker();
        UsageSnapshot usage = tracker != null ? tracker.toUsageSnapshot() : null;

        return new SessionSnapshot(
                sessionId,
                ctx.getCwd().toString(),
                ctx.getSettings() != null ? ctx.getSettings().getModel() : "unknown",
                messages,
                usage,
                Instant.now(),
                summary,
                messages.size());
    }

    private static Path getSessionDir(CommandContext ctx) {
        return getSessionDirForCwd(ctx.getCwd());
    }

    private static Path getSessionDirForCwd(Path cwd) {
        String projectName = cwd.getFileName() != null ? cwd.getFileName().toString() : "default";
        String digest = String.valueOf(Math.abs(cwd.toString().hashCode()));
        String sessionDirName = projectName + "-" + digest.substring(0, Math.min(8, digest.length()));
        return Path.of(System.getProperty("user.home"), ".openharness", "sessions", sessionDirName);
    }

    private static void tryCopyToClipboard(String text) {
        try {
            ProcessBuilder pb = new ProcessBuilder("pbcopy");
            Process process = pb.start();
            process.getOutputStream().write(text.getBytes());
            process.getOutputStream().close();
            process.waitFor();
        } catch (Exception e) {
            // 剪贴板不可用，忽略
        }
    }
}
