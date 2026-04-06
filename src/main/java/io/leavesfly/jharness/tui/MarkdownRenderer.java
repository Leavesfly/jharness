package io.leavesfly.jharness.tui;

import static io.leavesfly.jharness.tui.AnsiConsole.*;

/**
 * 终端 Markdown 渲染器
 *
 * 将 Markdown 文本转换为带 ANSI 颜色的终端输出。
 * 支持标题、代码块、行内代码、粗体、斜体、列表、分隔线等。
 */
public class MarkdownRenderer {

    private final int terminalWidth;

    public MarkdownRenderer() {
        this.terminalWidth = AnsiConsole.getTerminalWidth();
    }

    public MarkdownRenderer(int terminalWidth) {
        this.terminalWidth = terminalWidth;
    }

    /**
     * 渲染 Markdown 文本为 ANSI 彩色终端输出
     */
    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }

        StringBuilder output = new StringBuilder();
        String[] lines = markdown.split("\n", -1);
        boolean inCodeBlock = false;
        String codeLanguage = "";

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // 代码块开始/结束
            if (line.trim().startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    codeLanguage = line.trim().substring(3).trim();
                    output.append(DIM).append("  ┌─");
                    if (!codeLanguage.isEmpty()) {
                        output.append(" ").append(codeLanguage).append(" ");
                    }
                    output.append("─".repeat(Math.max(1, terminalWidth - 8 - codeLanguage.length())));
                    output.append(RESET).append("\n");
                } else {
                    inCodeBlock = false;
                    codeLanguage = "";
                    output.append(DIM).append("  └─")
                            .append("─".repeat(Math.max(1, terminalWidth - 6)))
                            .append(RESET).append("\n");
                }
                continue;
            }

            // 代码块内容
            if (inCodeBlock) {
                output.append(DIM).append("  │ ").append(RESET)
                        .append(BRIGHT_GREEN).append(line).append(RESET).append("\n");
                continue;
            }

            // 空行
            if (line.trim().isEmpty()) {
                output.append("\n");
                continue;
            }

            // 标题
            if (line.startsWith("### ")) {
                output.append(BOLD).append(BRIGHT_CYAN).append("   ")
                        .append(line.substring(4)).append(RESET).append("\n");
                continue;
            }
            if (line.startsWith("## ")) {
                output.append(BOLD).append(BRIGHT_BLUE).append("  ")
                        .append(line.substring(3)).append(RESET).append("\n");
                continue;
            }
            if (line.startsWith("# ")) {
                output.append(BOLD).append(BRIGHT_MAGENTA).append(" ")
                        .append(line.substring(2)).append(RESET).append("\n");
                continue;
            }

            // 水平分隔线
            if (line.matches("^---+$") || line.matches("^\\*\\*\\*+$") || line.matches("^___+$")) {
                output.append(horizontalRule(terminalWidth)).append("\n");
                continue;
            }

            // 无序列表
            if (line.matches("^\\s*[-*+]\\s+.*")) {
                int indent = line.indexOf(line.trim().charAt(0));
                String trimmed = line.trim();
                String content = trimmed.length() > 2 ? trimmed.substring(2).trim() : "";
                output.append(" ".repeat(indent))
                        .append(BRIGHT_CYAN).append("  • ").append(RESET)
                        .append(renderInline(content)).append("\n");
                continue;
            }

            // 有序列表
            if (line.matches("^\\s*\\d+\\.\\s+.*")) {
                String trimmed = line.trim();
                int indent = line.indexOf(trimmed.charAt(0));
                int dotIndex = trimmed.indexOf('.');
                String number = trimmed.substring(0, dotIndex);
                String content = dotIndex + 1 < trimmed.length() ? trimmed.substring(dotIndex + 1).trim() : "";
                output.append(" ".repeat(indent))
                        .append(BRIGHT_CYAN).append("  ").append(number).append(". ").append(RESET)
                        .append(renderInline(content)).append("\n");
                continue;
            }

            // 引用
            if (line.startsWith("> ")) {
                output.append(DIM).append(CYAN).append("  ▎ ").append(RESET)
                        .append(ITALIC).append(line.substring(2)).append(RESET).append("\n");
                continue;
            }

            // 普通段落
            output.append("  ").append(renderInline(line)).append("\n");
        }

        // 如果代码块未关闭，补上
        if (inCodeBlock) {
            output.append(DIM).append("  └─")
                    .append("─".repeat(Math.max(1, terminalWidth - 6)))
                    .append(RESET).append("\n");
        }

        return output.toString();
    }

    /**
     * 渲染行内 Markdown 元素（粗体、斜体、行内代码、链接）
     */
    private String renderInline(String text) {
        // 行内代码 `code`
        text = text.replaceAll("`([^`]+)`", BRIGHT_YELLOW + "$1" + RESET);

        // 粗体 **text**
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", BOLD + "$1" + RESET);

        // 斜体 *text*（避免匹配已处理的粗体）
        text = text.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", ITALIC + "$1" + RESET);

        // 链接 [text](url) → text (url)
        text = text.replaceAll("\\[([^]]+)]\\(([^)]+)\\)", UNDERLINE + BRIGHT_BLUE + "$1" + RESET + DIM + " ($2)" + RESET);

        return text;
    }
}
