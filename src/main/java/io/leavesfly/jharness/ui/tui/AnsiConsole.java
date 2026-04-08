package io.leavesfly.jharness.ui.tui;

/**
 * ANSI 终端颜色和样式工具类
 *
 * 封装 ANSI 转义序列，提供终端彩色输出能力。
 */
public final class AnsiConsole {

    private AnsiConsole() {}

    // ── 重置 ──
    public static final String RESET = "\033[0m";

    // ── 常规前景色 ──
    public static final String BLACK = "\033[30m";
    public static final String RED = "\033[31m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String BLUE = "\033[34m";
    public static final String MAGENTA = "\033[35m";
    public static final String CYAN = "\033[36m";
    public static final String WHITE = "\033[37m";

    // ── 亮色前景 ──
    public static final String BRIGHT_BLACK = "\033[90m";
    public static final String BRIGHT_RED = "\033[91m";
    public static final String BRIGHT_GREEN = "\033[92m";
    public static final String BRIGHT_YELLOW = "\033[93m";
    public static final String BRIGHT_BLUE = "\033[94m";
    public static final String BRIGHT_MAGENTA = "\033[95m";
    public static final String BRIGHT_CYAN = "\033[96m";
    public static final String BRIGHT_WHITE = "\033[97m";

    // ── 背景色 ──
    public static final String BG_BLACK = "\033[40m";
    public static final String BG_RED = "\033[41m";
    public static final String BG_GREEN = "\033[42m";
    public static final String BG_YELLOW = "\033[43m";
    public static final String BG_BLUE = "\033[44m";
    public static final String BG_MAGENTA = "\033[45m";
    public static final String BG_CYAN = "\033[46m";
    public static final String BG_WHITE = "\033[47m";

    // ── 样式 ──
    public static final String BOLD = "\033[1m";
    public static final String DIM = "\033[2m";
    public static final String ITALIC = "\033[3m";
    public static final String UNDERLINE = "\033[4m";
    public static final String STRIKETHROUGH = "\033[9m";

    // ── 光标控制 ──
    public static final String HIDE_CURSOR = "\033[?25l";
    public static final String SHOW_CURSOR = "\033[?25h";
    public static final String CLEAR_LINE = "\033[2K";
    public static final String CLEAR_SCREEN = "\033[2J\033[H";
    public static final String SAVE_CURSOR = "\033[s";
    public static final String RESTORE_CURSOR = "\033[u";

    /**
     * 用指定颜色包裹文本
     */
    public static String colored(String text, String color) {
        return color + text + RESET;
    }

    /**
     * 粗体文本
     */
    public static String bold(String text) {
        return BOLD + text + RESET;
    }

    /**
     * 暗色文本
     */
    public static String dim(String text) {
        return DIM + text + RESET;
    }

    /**
     * 斜体文本
     */
    public static String italic(String text) {
        return ITALIC + text + RESET;
    }

    /**
     * 生成水平分隔线
     */
    public static String horizontalRule(int width) {
        return DIM + "─".repeat(Math.max(1, width)) + RESET;
    }

    private static volatile int cachedTerminalWidth = -1;

    /**
     * 获取终端宽度（缓存结果，避免每次启动进程）
     */
    public static int getTerminalWidth() {
        if (cachedTerminalWidth > 0) {
            return cachedTerminalWidth;
        }
        try {
            Process process = new ProcessBuilder("tput", "cols")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            cachedTerminalWidth = Integer.parseInt(output);
            return cachedTerminalWidth;
        } catch (Exception ignored) {
            cachedTerminalWidth = 80;
            return 80;
        }
    }

    /**
     * 强制刷新终端宽度缓存（窗口大小变化时调用）
     */
    public static void invalidateTerminalWidthCache() {
        cachedTerminalWidth = -1;
    }

    /**
     * 将光标移动到指定行（从 1 开始）
     */
    public static String moveTo(int row, int col) {
        return "\033[" + row + ";" + col + "H";
    }

    /**
     * 向上移动 N 行
     */
    public static String moveUp(int lines) {
        return "\033[" + lines + "A";
    }
}
