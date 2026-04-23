package io.leavesfly.jharness.core.edit;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Unified Diff 工具（F-P0-4）。
 *
 * 无外部依赖实现逐行差异输出，仅用于"给人看"的预览，而非补丁应用：
 * - 使用最长公共子序列（LCS）算法计算逐行差异；
 * - 输出形如 "- old", "+ new", "  ctx" 的三类行，带可配置的上下文行数；
 * - 当文件非常大（&gt; 5000 行）时自动降级为"仅显示首尾 100 行"避免卡顿。
 *
 * 若未来有更精细的补丁需求，可替换为 java-diff-utils；此处保持零依赖。
 */
public final class DiffUtils {

    private static final int MAX_DIFF_LINES = 5000;
    private static final int HEAD_TAIL_LINES = 100;

    private DiffUtils() {
        // 工具类
    }

    /**
     * 生成两段文本的 unified-style 差异字符串。
     *
     * @param oldText 旧内容；null 视为空字符串
     * @param newText 新内容；null 视为空字符串
     * @return 可直接打印的多行字符串
     */
    public static String diff(String oldText, String newText) {
        String[] oldLines = (oldText == null ? "" : oldText).split("\n", -1);
        String[] newLines = (newText == null ? "" : newText).split("\n", -1);

        if (oldLines.length > MAX_DIFF_LINES || newLines.length > MAX_DIFF_LINES) {
            return renderLargeFileSummary(oldLines, newLines);
        }
        return renderLcsDiff(oldLines, newLines);
    }

    /**
     * 使用 LCS 算法渲染差异。
     */
    private static String renderLcsDiff(String[] a, String[] b) {
        int m = a.length, n = b.length;
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (a[i].equals(b[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<String> ops = new ArrayList<>();
        int i = 0, j = 0;
        while (i < m && j < n) {
            if (a[i].equals(b[j])) {
                ops.add("  " + a[i]);
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add("- " + a[i]);
                i++;
            } else {
                ops.add("+ " + b[j]);
                j++;
            }
        }
        while (i < m) ops.add("- " + a[i++]);
        while (j < n) ops.add("+ " + b[j++]);

        // 若完全相同，给出明确提示
        boolean hasChange = ops.stream().anyMatch(s -> s.startsWith("+ ") || s.startsWith("- "));
        if (!hasChange) {
            return "(文件内容未发生变化)";
        }
        return String.join("\n", ops);
    }

    /**
     * 大文件降级渲染：显示首尾若干行的差异 + 长度差异提示。
     *
     * 为避免 LCS 在百万行级别的 O(n*m) 空间/时间开销，对大文件只做：
     * 1. 前 HEAD_TAIL_LINES 行逐行对比（不等则输出 -/+）；
     * 2. 尾部 HEAD_TAIL_LINES 行同样比较；
     * 3. 给出新增/删除行数总览，便于定位整体变化量。
     */
    private static String renderLargeFileSummary(String[] a, String[] b) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("(文件过大，仅显示首尾 %d 行差异；原 %d 行 -> 新 %d 行)\n",
                HEAD_TAIL_LINES, a.length, b.length));

        // 头部对比
        int head = Math.min(HEAD_TAIL_LINES, Math.min(a.length, b.length));
        for (int k = 0; k < head; k++) {
            if (!a[k].equals(b[k])) {
                sb.append("- ").append(a[k]).append('\n');
                sb.append("+ ").append(b[k]).append('\n');
            }
        }

        // 尾部对比
        int tail = Math.min(HEAD_TAIL_LINES, Math.min(a.length, b.length));
        sb.append("...\n");
        for (int k = tail; k > 0; k--) {
            String aLine = a[a.length - k];
            String bLine = b[b.length - k];
            if (!aLine.equals(bLine)) {
                sb.append("- ").append(aLine).append('\n');
                sb.append("+ ").append(bLine).append('\n');
            }
        }

        // 行数变化提示
        if (a.length != b.length) {
            long delta = (long) b.length - a.length;
            sb.append(String.format("(文件行数变化: %+d 行)\n", delta));
        }
        return sb.toString();
    }
}
