package io.leavesfly.jharness.ui.tui;

import static io.leavesfly.jharness.ui.tui.AnsiConsole.*;

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

        // 预处理：修复流式传输导致的跨行 markdown 标记
        markdown = normalizeMarkdown(markdown);

        // 预处理：压缩连续空行为单个空行（代码块外）
        markdown = collapseBlankLines(markdown);

        StringBuilder output = new StringBuilder();
        String[] lines = markdown.split("\n", -1);
        boolean inCodeBlock = false;
        String codeLanguage = "";
        boolean lastLineWasBlank = false;

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

            // 空行处理：压缩连续空行，并跳过列表项之间的空行
            if (line.trim().isEmpty()) {
                if (!lastLineWasBlank) {
                    // 检查前后行是否都是列表项，如果是则跳过空行
                    boolean betweenListItems = isListLine(i > 0 ? lines[i - 1] : "")
                            && isListLine(findNextNonBlankLine(lines, i + 1));
                    if (!betweenListItems) {
                        output.append("\n");
                    }
                    lastLineWasBlank = true;
                }
                continue;
            }
            lastLineWasBlank = false;

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
     * 压缩连续空行为单个空行（保留代码块内的空行）
     */
    private String collapseBlankLines(String markdown) {
        String[] lines = markdown.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean inCodeBlock = false;
        boolean lastWasBlank = false;

        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                lastWasBlank = false;
                result.append(line).append("\n");
                continue;
            }

            if (inCodeBlock) {
                result.append(line).append("\n");
                continue;
            }

            if (line.trim().isEmpty()) {
                if (!lastWasBlank) {
                    result.append("\n");
                    lastWasBlank = true;
                }
            } else {
                lastWasBlank = false;
                result.append(line).append("\n");
            }
        }

        // 去掉末尾多余换行
        String text = result.toString();
        while (text.endsWith("\n\n")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * 预处理 markdown 文本，修复流式传输导致的跨行标记问题。
     *
     * 处理以下情况：
     * 1. 跨行粗体：**text\n** → **text**
     * 2. 跨行斜体：*text\n* → *text*
     * 3. 拆分的有序列表：数字\n. 内容 → 数字. 内容
     * 4. 列表项续行：将紧跟在列表项后的非列表非空行合并到上一行
     */
    private String normalizeMarkdown(String markdown) {
        // 修复跨行粗体：**text\n** 或 **text \n** → **text**
        markdown = markdown.replaceAll("(?s)(\\*\\*[^*]+?)\\s*\\n\\s*(\\*\\*)", "$1$2");

        // 修复跨行斜体（单星号）：*text\n* → *text*
        markdown = markdown.replaceAll("(?s)(?<!\\*)(\\*[^*\\n]+?)\\s*\\n\\s*(\\*)(?!\\*)", "$1$2");

        // 修复拆分的有序列表数字：数字\n. 内容 → 数字. 内容
        markdown = markdown.replaceAll("(\\d+)\\s*\\n\\s*(\\.\\s+)", "$1$2");

        // 合并列表项续行：列表项后紧跟的缩进非列表行合并到上一行
        String[] lines = markdown.split("\n", -1);
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String currentLine = lines[i];
            normalized.append(currentLine);

            // 检查是否需要将下一行合并到当前行
            if (i + 1 < lines.length) {
                String nextLine = lines[i + 1];
                String nextTrimmed = nextLine.trim();

                boolean currentIsList = currentLine.trim().matches("^[-*+]\\s+.*|^\\d+\\.\\s+.*");
                boolean nextIsContinuation = !nextTrimmed.isEmpty()
                        && !nextTrimmed.matches("^[-*+]\\s+.*")
                        && !nextTrimmed.matches("^\\d+\\.\\s+.*")
                        && !nextTrimmed.startsWith("#")
                        && !nextTrimmed.startsWith("```")
                        && !nextTrimmed.startsWith(">")
                        && !nextTrimmed.matches("^---+$");

                // 如果当前行是列表项且下一行看起来是续行内容，合并
                if (currentIsList && nextIsIndentedContinuation(currentLine, nextLine) && nextIsContinuation) {
                    normalized.append(" ");
                    i++; // 跳过下一行，因为已合并
                    normalized.append(nextTrimmed);
                }
            }

            normalized.append("\n");
        }

        // 去掉末尾多余的换行
        String result = normalized.toString();
        if (result.endsWith("\n") && !markdown.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }

    /**
     * 判断下一行是否是当前列表项的缩进续行
     */
    private boolean nextIsIndentedContinuation(String currentLine, String nextLine) {
        if (nextLine.trim().isEmpty()) {
            return false;
        }
        int currentIndent = getIndentLevel(currentLine);
        int nextIndent = getIndentLevel(nextLine);
        // 续行的缩进应该大于列表项标记的缩进
        return nextIndent > currentIndent;
    }

    /**
     * 获取行的缩进级别
     */
    private int getIndentLevel(String line) {
        int indent = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') {
                indent++;
            } else if (c == '\t') {
                indent += 4;
            } else {
                break;
            }
        }
        return indent;
    }

    /**
     * 判断一行是否是列表项（有序或无序）
     */
    private boolean isListLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.matches("^[-*+]\\s+.*") || trimmed.matches("^\\d+\\.\\s+.*");
    }

    /**
     * 从指定位置开始查找下一个非空行
     */
    private String findNextNonBlankLine(String[] lines, int startIndex) {
        for (int i = startIndex; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) {
                return lines[i];
            }
        }
        return "";
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

        // 清理残留的未匹配粗体标记
        text = text.replace("**", "");

        return text;
    }
}
