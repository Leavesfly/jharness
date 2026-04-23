package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.core.edit.DiffUtils;
import io.leavesfly.jharness.core.edit.EditHistoryManager;
import io.leavesfly.jharness.tools.input.MultiEditToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 多编辑原子操作工具（F-P1-4）。
 *
 * 对同一文件按给定顺序应用多个 edit 操作，全部成功才原子写回磁盘；
 * 任一 edit 校验失败（old_string 未找到 / 不唯一 / 与 new_string 相同）则整体回滚，
 * 保持目标文件零变更 —— 避免"前几个 edit 已写入、后面 edit 失败"的半成品状态。
 *
 * 相比连续调用单个 FileEditTool：
 * - 减少 N 次磁盘往返到 1 次；
 * - 保持原子性（任何失败都不落盘）；
 * - 在 EditHistoryManager 中仅产生 1 条记录，undo 一次性回滚所有 edit。
 */
public class MultiEditTool extends BaseTool<MultiEditToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(MultiEditTool.class);

    @Override
    public String getName() {
        return "multi_edit";
    }

    @Override
    public String getDescription() {
        return "对同一文件按顺序应用多个 edit，全部成功才原子写回；任一失败则整体回滚。";
    }

    @Override
    public Class<MultiEditToolInput> getInputClass() {
        return MultiEditToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(MultiEditToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path filePath = context.getCwd().resolve(input.getFile_path()).normalize();

                // 路径穿越防护
                if (!filePath.startsWith(context.getCwd().toAbsolutePath().normalize())) {
                    return ToolResult.error("安全限制: 不允许编辑工作目录之外的文件");
                }
                if (!Files.exists(filePath)) {
                    return ToolResult.error("文件不存在: " + filePath);
                }

                List<MultiEditToolInput.Edit> edits = input.getEdits();
                if (edits == null || edits.isEmpty()) {
                    return ToolResult.error("edits 列表不能为空");
                }

                String originalContent = Files.readString(filePath, StandardCharsets.UTF_8);
                String current = originalContent;

                // 逐个应用 edit，使用中间态字符串保证原子性（任何失败都不落盘）
                for (int i = 0; i < edits.size(); i++) {
                    MultiEditToolInput.Edit edit = edits.get(i);
                    if (edit.getOld_string() == null || edit.getNew_string() == null) {
                        return ToolResult.error(String.format(
                                "第 %d 个 edit 的 old_string/new_string 不能为空", i + 1));
                    }
                    if (edit.getOld_string().equals(edit.getNew_string())) {
                        return ToolResult.error(String.format(
                                "第 %d 个 edit 的 old_string 与 new_string 相同，无需编辑", i + 1));
                    }

                    int firstIndex = current.indexOf(edit.getOld_string());
                    if (firstIndex < 0) {
                        return ToolResult.error(String.format(
                                "第 %d 个 edit 的 old_string 未在当前文件中找到：%s",
                                i + 1, truncate(edit.getOld_string())));
                    }

                    if (edit.isReplace_all()) {
                        // 全文替换：使用 substring 循环替换，避免正则转义问题
                        current = replaceAll(current, edit.getOld_string(), edit.getNew_string());
                    } else {
                        int secondIndex = current.indexOf(edit.getOld_string(), firstIndex + 1);
                        if (secondIndex >= 0) {
                            long count = countOccurrences(current, edit.getOld_string());
                            return ToolResult.error(String.format(
                                    "第 %d 个 edit 的 old_string 在当前文件中出现 %d 次，无法确定唯一替换位置；请扩展上下文或设置 replace_all=true。",
                                    i + 1, count));
                        }
                        current = current.substring(0, firstIndex)
                                + edit.getNew_string()
                                + current.substring(firstIndex + edit.getOld_string().length());
                    }
                }

                // 所有 edit 均校验并应用成功，原子写回磁盘
                atomicWrite(filePath, current);

                // 写入历史（仅 1 条，undo 一次性回滚所有 edit）
                long editId = EditHistoryManager.getInstance().record(
                        filePath.toString(), originalContent, current, getName());

                String diffPreview = DiffUtils.diff(originalContent, current);
                return ToolResult.success(String.format(
                        "成功应用 %d 个 edit 到 %s, edit_id=%d\n--- diff ---\n%s",
                        edits.size(), filePath, editId, diffPreview));

            } catch (IOException e) {
                logger.error("多编辑失败", e);
                return ToolResult.error("多编辑失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(MultiEditToolInput input) {
        return false;
    }

    /**
     * 不重叠地统计子串出现次数。
     */
    private static long countOccurrences(String text, String needle) {
        if (needle.isEmpty()) return 0;
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * 安全地全文替换（不使用正则，避免特殊字符问题）。
     */
    private static String replaceAll(String text, String needle, String replacement) {
        if (needle.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int start = 0;
        int idx;
        while ((idx = text.indexOf(needle, start)) >= 0) {
            sb.append(text, start, idx).append(replacement);
            start = idx + needle.length();
        }
        sb.append(text, start, text.length());
        return sb.toString();
    }

    /**
     * 截断过长字符串供日志展示。
     */
    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    /**
     * 原子写：先写临时文件再 ATOMIC_MOVE 替换目标。
     */
    private static void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.getParent() != null ? target.getParent() : target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(parent, ".jh-multi-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}