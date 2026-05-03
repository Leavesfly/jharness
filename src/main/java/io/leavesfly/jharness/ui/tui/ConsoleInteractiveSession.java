package io.leavesfly.jharness.ui.tui;

import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.CommandRegistry;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.core.engine.CostTracker;
import io.leavesfly.jharness.core.engine.QueryEngine;
import io.leavesfly.jharness.core.engine.stream.AssistantTextDelta;
import io.leavesfly.jharness.core.engine.stream.AssistantTurnComplete;
import io.leavesfly.jharness.core.engine.stream.StreamEvent;
import io.leavesfly.jharness.core.engine.stream.ToolExecutionCompleted;
import io.leavesfly.jharness.core.engine.stream.ToolExecutionStarted;
import io.leavesfly.jharness.session.permissions.PermissionChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.leavesfly.jharness.ui.tui.AnsiConsole.*;

/**
 * Claude Code 风格的终端交互会话
 *
 * 直接在终端中运行，使用 ANSI 转义序列实现彩色输出、
 * Markdown 渲染、工具执行可视化和状态栏。
 */
public class ConsoleInteractiveSession {
    private static final Logger logger = LoggerFactory.getLogger(ConsoleInteractiveSession.class);

    private final QueryEngine queryEngine;
    private final String modelName;
    private final String permissionMode;
    private final MarkdownRenderer markdownRenderer;
    private final int terminalWidth;

    private final CommandRegistry commandRegistry;
    private final AtomicBoolean waitingForResponse = new AtomicBoolean(false);
    private final StringBuffer pendingText = new StringBuffer();
    private Instant queryStartTime;
    private int currentToolCount;

    /**
     * 流式渲染缓冲区：累积所有 delta 文本，
     * flush 时渲染已完成的行并清除已渲染部分，保留不完整的末尾行继续缓冲。
     */
    private final StringBuffer streamBuffer = new StringBuffer();

    public ConsoleInteractiveSession(QueryEngine queryEngine, String modelName, String permissionMode) {
        this(queryEngine, modelName, permissionMode, new CommandRegistry());
    }

    public ConsoleInteractiveSession(QueryEngine queryEngine, String modelName, String permissionMode, CommandRegistry commandRegistry) {
        this.queryEngine = queryEngine;
        this.modelName = modelName;
        this.permissionMode = permissionMode;
        this.terminalWidth = AnsiConsole.getTerminalWidth();
        this.markdownRenderer = new MarkdownRenderer(terminalWidth);
        this.commandRegistry = commandRegistry;
    }

    /**
     * 启动交互会话
     */
    public void start() {
        printWelcomeBanner();
        runInputLoop();
        printGoodbye();
    }

    /**
     * 打印 Claude Code 风格的欢迎横幅
     */
    private void printWelcomeBanner() {
        System.out.println();
        System.out.println(BOLD + BRIGHT_MAGENTA + "  ╭─────────────────────────────────────────╮" + RESET);
        System.out.println(BOLD + BRIGHT_MAGENTA + "  │" + RESET
                + BOLD + "         🤖 JHarness v0.1.0              " + RESET
                + BOLD + BRIGHT_MAGENTA + "│" + RESET);
        System.out.println(BOLD + BRIGHT_MAGENTA + "  │" + RESET
                + DIM + "       Java AI Agent Framework            " + RESET
                + BOLD + BRIGHT_MAGENTA + "│" + RESET);
        System.out.println(BOLD + BRIGHT_MAGENTA + "  ╰─────────────────────────────────────────╯" + RESET);
        System.out.println();

        // 模型和权限信息
        System.out.println("  " + DIM + "Model:" + RESET + " " + BOLD + modelName + RESET
                + "  " + DIM + "Permission:" + RESET + " " + formatPermissionMode(permissionMode));
        System.out.println("  " + DIM + "Type " + RESET + BRIGHT_CYAN + "/help" + RESET
                + DIM + " for commands, " + RESET + BRIGHT_CYAN + "/exit" + RESET + DIM + " to quit" + RESET);
        System.out.println();
        System.out.println(DIM + "  " + "─".repeat(Math.max(1, terminalWidth - 4)) + RESET);
        System.out.println();
    }

    /**
     * 格式化权限模式显示
     */
    private String formatPermissionMode(String mode) {
        return switch (mode.toLowerCase()) {
            case "full_auto" -> BRIGHT_RED + "⚡ Full Auto" + RESET;
            case "plan" -> BRIGHT_YELLOW + "📋 Plan" + RESET;
            default -> BRIGHT_GREEN + "🛡 Default" + RESET;
        };
    }

    /**
     * 主输入循环
     */
    private void runInputLoop() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            printPrompt();

            String input;
            try {
                input = reader.readLine();
            } catch (IOException e) {
                break;
            }

            if (input == null) {
                break;
            }

            input = input.trim();
            if (input.isEmpty()) {
                continue;
            }

            if (input.startsWith("/")) {
                if (!handleCommand(input)) {
                    break;
                }
                continue;
            }

            handleUserQuery(input);
        }
    }

    /**
     * 打印 Claude Code 风格的输入提示符
     */
    private void printPrompt() {
        CostTracker tracker = queryEngine.getCostTracker();
        long totalTokens = tracker.getTotalTokens();

        // 右侧 token 计数（如果有的话）
        String tokenInfo = "";
        if (totalTokens > 0) {
            tokenInfo = DIM + formatTokenCount(totalTokens) + " tokens" + RESET;
        }

        if (!tokenInfo.isEmpty()) {
            // 右对齐 token 信息
            int promptLen = 4; // "> " 的视觉长度
            int padding = Math.max(1, terminalWidth - promptLen - stripAnsi(tokenInfo).length());
            System.out.print(BOLD + BRIGHT_GREEN + "> " + RESET);
            // token 信息在上一行右侧
            System.out.print(moveTo(0, 0)); // 不实际移动，只是占位
        }

        System.out.print(BOLD + BRIGHT_GREEN + "> " + RESET);
    }

    /**
     * 处理斜杠命令
     *
     * @return false 表示退出
     */
    private boolean handleCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0].toLowerCase();

        switch (command) {
            case "/exit", "/quit" -> {
                return false;
            }
            case "/clear" -> {
                queryEngine.clear();
                System.out.print(CLEAR_SCREEN);
                printWelcomeBanner();
                printSystemMessage("对话历史已清空");
            }
            case "/help" -> printHelp();
            case "/status" -> printStatus();
            case "/cost" -> printCost();
            case "/compact" -> {
                printSystemMessage("消息历史已压缩");
            }
            default -> {
                var optionalCmd = commandRegistry.lookup(input);
                if (optionalCmd.isPresent()) {
                    try {
                        // FP-2：/permissions、/plan 等命令需要通过 CommandContext 拿到 PermissionChecker
                        // 才能把切模式同步到运行时。这里从 queryEngine 反向获取，避免旧构造方式下为 null。
                        PermissionChecker runtimeChecker =
                                (queryEngine != null) ? queryEngine.getPermissionChecker() : null;
                        CommandContext ctx = new CommandContext(
                                java.nio.file.Paths.get(System.getProperty("user.dir")),
                                queryEngine, null, runtimeChecker, null);
                        var result = optionalCmd.get().execute(
                                parts.length > 1 ? java.util.List.of(parts[1].split("\\s+")) : java.util.List.of(),
                                ctx, event -> {}).join();
                        printSystemMessage(result.getMessage());
                    } catch (Exception e) {
                        printSystemMessage("命令执行失败: " + e.getMessage());
                    }
                } else {
                    printSystemMessage("未知命令: " + command + "，输入 " + BRIGHT_CYAN + "/help" + RESET + " 查看可用命令");
                }
            }
        }
        System.out.println();
        return true;
    }

    /**
     * 处理用户查询
     */
    private void handleUserQuery(String input) {
        if (waitingForResponse.get()) {
            printSystemMessage("⏳ 正在等待 AI 响应，请稍候...");
            return;
        }

        System.out.println();
        waitingForResponse.set(true);
        queryStartTime = Instant.now();
        currentToolCount = 0;
        pendingText.setLength(0);

        try {
            queryEngine.submitMessage(input, this::handleStreamEvent).join();
        } catch (Exception e) {
            printError("查询执行失败: " + e.getMessage());
            logger.error("查询执行失败", e);
        } finally {
            flushPendingText();
            printQueryFooter();
            waitingForResponse.set(false);
        }
    }

    /**
     * 处理流式事件
     *
     * 使用增量渲染策略：将所有 delta 累积到 streamBuffer 中，
     * 每次 flush 时只渲染新增的完整行，保留不完整的末尾行继续缓冲，
     * 避免 markdown 元素（粗体、列表等）被拆分到不同渲染批次。
     */
    private void handleStreamEvent(StreamEvent event) {
        if (event instanceof AssistantTextDelta textDelta) {
            streamBuffer.append(textDelta.getText());
            if (textDelta.getText().contains("\n")) {
                flushCompleteLines(false);
            }
        } else if (event instanceof ToolExecutionStarted toolStart) {
            flushCompleteLines(true);
            currentToolCount++;
            printToolStart(toolStart.getToolName());
        } else if (event instanceof ToolExecutionCompleted toolDone) {
            printToolComplete(toolDone.getToolName(), toolDone.getResult(), toolDone.isError());
        } else if (event instanceof AssistantTurnComplete) {
            flushCompleteLines(true);
        }
    }

    /**
     * 渲染流式缓冲区中的完整行。
     *
     * @param flushAll true 时强制渲染所有内容（包括最后不完整的行）
     */
    private void flushCompleteLines(boolean flushAll) {
        synchronized (streamBuffer) {
            if (streamBuffer.length() == 0) {
                return;
            }

            String buffered = streamBuffer.toString();

            if (!flushAll) {
                // 非强制模式：只渲染到最后一个换行符为止，保留不完整的末尾行
                int lastNewline = buffered.lastIndexOf('\n');
                if (lastNewline < 0) {
                    return;
                }
                // 提取完整行部分进行渲染，保留剩余部分
                String completeText = buffered.substring(0, lastNewline + 1);
                String remainder = buffered.substring(lastNewline + 1);
                streamBuffer.setLength(0);
                streamBuffer.append(remainder);

                String rendered = markdownRenderer.render(completeText);
                System.out.print(rendered);
                System.out.flush();
            } else {
                // 强制模式：渲染全部内容并清空缓冲区
                streamBuffer.setLength(0);

                String rendered = markdownRenderer.render(buffered);
                System.out.print(rendered);
                System.out.flush();
            }
        }
    }

    /**
     * 刷新缓冲的文本（带 Markdown 渲染）
     * 保留用于非流式场景的兼容
     */
    private void flushPendingText() {
        synchronized (pendingText) {
            if (pendingText.length() == 0) {
                return;
            }
            String text = pendingText.toString();
            pendingText.setLength(0);

            String rendered = markdownRenderer.render(text);
            System.out.print(rendered);
            System.out.flush();
        }
    }

    /**
     * 打印工具执行开始
     */
    private void printToolStart(String toolName) {
        System.out.println();
        System.out.println("  " + BG_BLUE + WHITE + BOLD + " 🔧 " + toolName + " " + RESET);
        System.out.println("  " + DIM + "├─ 执行中..." + RESET);
    }

    /**
     * 打印工具执行完成
     */
    private void printToolComplete(String toolName, String result, boolean isError) {
        if (isError) {
            System.out.println("  " + RED + "╰─ ❌ 失败" + RESET);
        } else {
            System.out.println("  " + GREEN + "╰─ ✅ 完成" + RESET);
        }

        // 显示工具结果摘要（截断过长内容）
        if (result != null && !result.isEmpty()) {
            String summary = result.length() > 200
                    ? result.substring(0, 200) + "..."
                    : result;
            String[] resultLines = summary.split("\n");
            int maxLines = Math.min(resultLines.length, 5);
            for (int i = 0; i < maxLines; i++) {
                System.out.println("  " + DIM + "  " + resultLines[i] + RESET);
            }
            if (resultLines.length > maxLines) {
                System.out.println("  " + DIM + "  ... (" + (resultLines.length - maxLines) + " more lines)" + RESET);
            }
        }
        System.out.println();
    }

    /**
     * 打印查询完成后的状态栏
     */
    private void printQueryFooter() {
        Duration elapsed = Duration.between(queryStartTime, Instant.now());
        CostTracker tracker = queryEngine.getCostTracker();

        StringBuilder footer = new StringBuilder();
        footer.append("  ").append(DIM);
        footer.append("─".repeat(Math.max(1, terminalWidth - 4)));
        footer.append(RESET).append("\n");

        footer.append("  ").append(DIM);
        footer.append("⏱ ").append(formatDuration(elapsed));
        footer.append("  │  ");
        footer.append("📊 ").append(formatTokenCount(tracker.getTotalInputTokens())).append(" in / ");
        footer.append(formatTokenCount(tracker.getTotalOutputTokens())).append(" out");

        if (tracker.getTotalCacheReadTokens() > 0) {
            footer.append("  │  💾 ").append(formatTokenCount(tracker.getTotalCacheReadTokens())).append(" cached");
        }

        if (currentToolCount > 0) {
            footer.append("  │  🔧 ").append(currentToolCount).append(" tool").append(currentToolCount > 1 ? "s" : "");
        }

        footer.append(RESET).append("\n");
        System.out.println(footer);
    }

    /**
     * 打印帮助信息
     */
    private void printHelp() {
        System.out.println();
        System.out.println(BOLD + "  可用命令" + RESET);
        System.out.println();

        commandRegistry.getAllCommands().stream()
            .sorted(java.util.Comparator.comparing(SlashCommand::getName))
            .forEach(cmd -> {
                String name = "/" + cmd.getName();
                int padding = Math.max(1, 12 - name.length());
                System.out.println("  " + BRIGHT_CYAN + name + RESET + " ".repeat(padding) + DIM + cmd.getDescription() + RESET);
            });
    }

    /**
     * 打印状态信息
     */
    private void printStatus() {
        System.out.println();
        System.out.println("  " + BOLD + "当前状态" + RESET);
        System.out.println("  " + DIM + "Model:      " + RESET + BOLD + modelName + RESET);
        System.out.println("  " + DIM + "Permission: " + RESET + formatPermissionMode(permissionMode));
        System.out.println("  " + DIM + "Messages:   " + RESET + queryEngine.getMessages().size());
        System.out.println("  " + DIM + "Requests:   " + RESET + queryEngine.getCostTracker().getRequestCount());
    }

    /**
     * 打印 Token 使用量
     */
    private void printCost() {
        CostTracker tracker = queryEngine.getCostTracker();
        System.out.println();
        System.out.println("  " + BOLD + "Token 使用量" + RESET);
        System.out.println("  " + DIM + "Requests:       " + RESET + tracker.getRequestCount());
        System.out.println("  " + DIM + "Input tokens:   " + RESET + formatTokenCount(tracker.getTotalInputTokens()));
        System.out.println("  " + DIM + "Output tokens:  " + RESET + formatTokenCount(tracker.getTotalOutputTokens()));
        System.out.println("  " + DIM + "Cache read:     " + RESET + formatTokenCount(tracker.getTotalCacheReadTokens()));
        System.out.println("  " + DIM + "Cache created:  " + RESET + formatTokenCount(tracker.getTotalCacheCreationTokens()));
        System.out.println("  " + DIM + "Total:          " + RESET + BOLD + formatTokenCount(tracker.getTotalTokens()) + RESET);
    }

    /**
     * 打印系统消息
     */
    private void printSystemMessage(String message) {
        System.out.println("  " + BRIGHT_CYAN + "ℹ " + RESET + message);
    }

    /**
     * 打印错误消息
     */
    private void printError(String message) {
        System.out.println("  " + BRIGHT_RED + "✖ " + RESET + RED + message + RESET);
    }

    /**
     * 打印退出信息
     */
    private void printGoodbye() {
        System.out.println();
        System.out.println("  " + DIM + "Goodbye! 👋" + RESET);
        System.out.println();
    }

    /**
     * 格式化 token 数量（带千分位）
     */
    private String formatTokenCount(long count) {
        if (count >= 1_000_000) {
            return String.format("%.1fM", count / 1_000_000.0);
        } else if (count >= 1_000) {
            return String.format("%.1fk", count / 1_000.0);
        }
        return String.valueOf(count);
    }

    /**
     * 格式化持续时间
     */
    private String formatDuration(Duration duration) {
        long totalSeconds = duration.getSeconds();
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + "m " + seconds + "s";
    }

    /**
     * 去除 ANSI 转义序列（用于计算实际字符宽度）
     */
    private String stripAnsi(String text) {
        return text.replaceAll("\033\\[[0-9;]*m", "");
    }
}