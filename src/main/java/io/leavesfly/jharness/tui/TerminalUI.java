package io.leavesfly.jharness.tui;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.UnixTerminal;
import io.leavesfly.jharness.commands.CommandContext;
import io.leavesfly.jharness.commands.CommandRegistry;
import io.leavesfly.jharness.engine.QueryEngine;
import io.leavesfly.jharness.engine.stream.AssistantTextDelta;
import io.leavesfly.jharness.engine.stream.AssistantTurnComplete;
import io.leavesfly.jharness.engine.stream.StreamEvent;
import io.leavesfly.jharness.engine.stream.ToolExecutionCompleted;
import io.leavesfly.jharness.engine.stream.ToolExecutionStarted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 基于 Lanterna 的终端用户界面
 *
 * 使用 Terminal 接口直接渲染，集成 QueryEngine 实现 AI 对话。
 */
public class TerminalUI {
    private static final Logger logger = LoggerFactory.getLogger(TerminalUI.class);

    private Terminal terminal;
    private volatile boolean running;
    private volatile boolean waitingForResponse;
    private final List<String> conversationHistory = java.util.Collections.synchronizedList(new ArrayList<>());
    private final StringBuilder currentInput = new StringBuilder();
    private final StringBuffer pendingAssistantText = new StringBuffer();
    private String currentModel = "default";
    private String permissionMode = "default";
    private String statusMessage = "就绪";
    private QueryEngine queryEngine;
    private CommandRegistry commandRegistry;
    private static final int HEADER_HEIGHT = 3;
    private static final int FOOTER_HEIGHT = 2;
    private static final int INPUT_HEIGHT = 3;

    // ANSI 转义序列辅助
    private static final String RESET = "\033[0m";
    private static final String BLUE_BG = "\033[44m";
    private static final String BLUE_FG = "\033[34m";
    private static final String WHITE_FG = "\033[37m";
    private static final String CYAN_FG = "\033[36m";
    private static final String GREEN_FG = "\033[32m";
    private static final String YELLOW_FG = "\033[33m";

    public void start() {
        try {
            // 强制使用 Unix 终端，避免 macOS 上 Swing 窗口的 AWT 输入法兼容问题
            try {
                terminal = new UnixTerminal(System.in, System.out, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                logger.warn("无法创建 Unix 终端，回退到默认终端工厂", e);
                DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory();
                terminalFactory.setForceTextTerminal(true);
                terminal = terminalFactory.createTerminal();
            }

            // 隐藏光标
            terminal.setCursorVisible(false);

            running = true;

            addSystemMessage("欢迎使用 JHarness TUI 界面");
            addSystemMessage("输入 /help 查看可用命令，输入 /exit 退出");

            mainLoop();

        } catch (IOException e) {
            System.err.println("启动 TUI 失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            stop();
        }
    }

    private void mainLoop() throws IOException {
        while (running) {
            render();

            KeyStroke keyStroke = terminal.readInput();
            if (keyStroke == null) continue;
            handleInput(keyStroke);
        }
    }

    private void handleInput(KeyStroke keyStroke) {
        if (keyStroke.getKeyType() == KeyType.Escape || keyStroke.getKeyType() == KeyType.EOF) {
            running = false; return;
        }
        if (keyStroke.getKeyType() == KeyType.Enter) { processInput(); return; }
        if (keyStroke.getKeyType() == KeyType.Backspace) {
            if (currentInput.length() > 0) currentInput.deleteCharAt(currentInput.length() - 1); return;
        }
        if (keyStroke.getKeyType() == KeyType.Character) currentInput.append(keyStroke.getCharacter());
    }

    private void processInput() {
        String input = currentInput.toString().trim();
        currentInput.setLength(0);
        if (input.isEmpty()) return;
        conversationHistory.add("> " + input);
        if (input.startsWith("/")) handleSlashCommand(input);
        else handleConversation(input);
        if (conversationHistory.size() > 100) conversationHistory.subList(0, conversationHistory.size() - 100).clear();
    }

    private void handleSlashCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";
        switch (command) {
            case "/exit": case "/quit": running = false; break;
            case "/clear": conversationHistory.clear(); addSystemMessage("对话历史已清空"); break;
            case "/status": addSystemMessage("模型: " + currentModel + " | 权限: " + permissionMode + " | " + statusMessage); break;
            case "/model": currentModel = args.isEmpty() ? currentModel : args; addSystemMessage("模型: " + currentModel); break;
            case "/permissions": addSystemMessage("权限模式: " + permissionMode); break;
            default:
                if (commandRegistry != null) {
                    commandRegistry.lookup(input).ifPresentOrElse(
                        slashCmd -> {
                            List<String> argList = args.isEmpty() ? List.of() : List.of(args.split("\\s+"));
                            CommandContext ctx = new CommandContext(
                                    java.nio.file.Paths.get(System.getProperty("user.dir")),
                                    queryEngine, null, null, null);
                            slashCmd.execute(argList, ctx, event -> {})
                                .thenAccept(result -> addSystemMessage(result.getMessage()));
                        },
                        () -> addSystemMessage("未知命令: " + command)
                    );
                } else {
                    addSystemMessage("未知命令: " + command);
                }
                break;
        }
    }

    /**
     * 处理用户对话输入，调用 QueryEngine 进行 AI 推理
     */
    private void handleConversation(String input) {
        if (queryEngine == null) {
            addSystemMessage("⚠ QueryEngine 未初始化，无法处理对话。请检查 API Key 配置。");
            return;
        }

        if (waitingForResponse) {
            addSystemMessage("⚠ 正在等待 AI 响应，请稍候...");
            return;
        }

        waitingForResponse = true;
        statusMessage = "思考中...";
        pendingAssistantText.setLength(0);

        CompletableFuture<Void> queryFuture = queryEngine.submitMessage(input, this::handleStreamEvent);

        queryFuture.whenComplete((result, error) -> {
            if (error != null) {
                logger.error("查询执行失败", error);
                addSystemMessage("❌ 错误: " + error.getMessage());
            }
            flushPendingAssistantText();
            waitingForResponse = false;
            statusMessage = "就绪";
        });
    }

    /**
     * 处理来自 QueryEngine 的流式事件
     */
    private void handleStreamEvent(StreamEvent event) {
        if (event instanceof AssistantTextDelta textDelta) {
            pendingAssistantText.append(textDelta.getText());
            if (textDelta.getText().contains("\n")) {
                flushPendingAssistantText();
            }
        } else if (event instanceof ToolExecutionStarted toolStart) {
            flushPendingAssistantText();
            addSystemMessage("🔧 执行工具: " + toolStart.getToolName());
        } else if (event instanceof ToolExecutionCompleted toolDone) {
            String prefix = toolDone.isError() ? "❌ 工具失败: " : "✅ 工具完成: ";
            addSystemMessage(prefix + toolDone.getToolName());
        } else if (event instanceof AssistantTurnComplete) {
            flushPendingAssistantText();
        }
    }

    /**
     * 将缓冲的 AI 文本刷新到对话历史
     */
    private void flushPendingAssistantText() {
        synchronized (pendingAssistantText) {
            if (pendingAssistantText.length() > 0) {
                addAssistantMessage(pendingAssistantText.toString());
                pendingAssistantText.setLength(0);
            }
        }
    }

    public void setQueryEngine(QueryEngine queryEngine) {
        this.queryEngine = queryEngine;
    }

    public void setCommandRegistry(CommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
    }

    public void addSystemMessage(String message) {
        conversationHistory.add("  " + message);
    }

    public void addAssistantMessage(String message) {
        for (String line : message.split("\n")) {
            conversationHistory.add("[AI] " + line);
        }
    }

    public void setStatus(String model, String permissionMode, String statusMessage) {
        this.currentModel = model;
        this.permissionMode = permissionMode;
        this.statusMessage = statusMessage;
    }

    private void render() throws IOException {
        TerminalSize size = terminal.getTerminalSize();
        int width = size.getColumns(), height = size.getRows();

        // 清屏并移动光标到左上角
        terminal.clearScreen();
        terminal.setCursorPosition(new TerminalPosition(0, 0));

        // 使用 ANSI 转义序列渲染
        StringBuilder sb = new StringBuilder();

        // 头部
        sb.append(BLUE_BG);
        sb.append("─".repeat(width));
        sb.append(RESET);
        sb.append("\n");

        String title = " JHarness - AI Agent ";
        int titleX = Math.max(0, (width - title.length()) / 2);
        sb.append(" ".repeat(titleX));
        sb.append(BLUE_BG);
        sb.append(WHITE_FG);
        sb.append(title);
        sb.append(RESET);
        sb.append(BLUE_BG);
        sb.append("─".repeat(Math.max(0, width - titleX - title.length())));
        sb.append(RESET);
        sb.append("\n");

        sb.append(CYAN_FG);
        String status = " 模型:" + currentModel + " | 权限:" + permissionMode + " | " + statusMessage;
        sb.append(status.substring(0, Math.min(status.length(), width)));
        sb.append(RESET);
        sb.append("\n");

        sb.append(BLUE_BG);
        sb.append("─".repeat(width));
        sb.append(RESET);
        sb.append("\n");

        // 对话历史
        int historyStart = HEADER_HEIGHT;
        int historyEnd = height - FOOTER_HEIGHT - INPUT_HEIGHT;
        int visibleLines = historyEnd - historyStart;
        if (visibleLines > 0) {
            int startIndex = Math.max(0, conversationHistory.size() - visibleLines);
            List<String> visibleHistory = conversationHistory.subList(startIndex, conversationHistory.size());
            for (String line : visibleHistory) {
                String display = line.length() > width ? line.substring(0, width) : line;
                if (line.startsWith("> ")) sb.append(GREEN_FG);
                else if (line.startsWith("[AI]")) sb.append(YELLOW_FG);
                else if (line.startsWith("  ")) sb.append(CYAN_FG);
                sb.append(display);
                sb.append(RESET);
                sb.append("\n");
            }
        }

        // 输入区域
        int remainingLines = height - FOOTER_HEIGHT - HEADER_HEIGHT - Math.min(conversationHistory.size(), visibleLines);
        for (int i = 0; i < Math.max(0, remainingLines - 1); i++) {
            sb.append("\n");
        }

        sb.append(BLUE_BG);
        sb.append("─".repeat(width));
        sb.append(RESET);
        sb.append("\n");

        sb.append(GREEN_FG);
        sb.append("> ");
        sb.append(RESET);
        sb.append(currentInput.toString());
        sb.append("\n");

        // 底部
        sb.append(BLUE_BG);
        sb.append("─".repeat(width));
        sb.append(RESET);
        sb.append("\n");

        String footerText = " Ctrl+C 退出 | 输入 /help 获取帮助 ";
        int footerX = Math.max(0, (width - footerText.length()) / 2);
        sb.append(" ".repeat(footerX));
        sb.append(BLUE_BG);
        sb.append(WHITE_FG);
        sb.append(footerText);
        sb.append(RESET);
        sb.append("\n");

        System.out.print("\033[H\033[2J"); // 清屏
        System.out.print(sb.toString());
        System.out.flush();
    }

    public void stop() {
        running = false;
        try {
            if (terminal != null) {
                terminal.close();
                terminal = null;
            }
        } catch (IOException e) {
            logger.warn("关闭终端时发生异常", e);
        }
        System.out.println("\n感谢使用 JHarness!");
    }
}
