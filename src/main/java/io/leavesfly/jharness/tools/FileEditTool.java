package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.core.edit.DiffUtils;
import io.leavesfly.jharness.core.edit.EditHistoryManager;
import io.leavesfly.jharness.tools.input.FileEditToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/**
 * 文件编辑工具
 *
 * 在文件中查找并替换文本。支持精确匹配。
 */
public class FileEditTool extends BaseTool<FileEditToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(FileEditTool.class);

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "在文件中查找并替换文本。需要精确匹配 old_string。";
    }

    @Override
    public Class<FileEditToolInput> getInputClass() {
        return FileEditToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(FileEditToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path filePath = context.getCwd().resolve(input.getFile_path()).normalize();
                
                // 安全检查：防止路径遍历攻击
                if (!filePath.startsWith(context.getCwd().toAbsolutePath().normalize())) {
                    return ToolResult.error("安全限制: 不允许编辑工作目录之外的文件");
                }

                if (!Files.exists(filePath)) {
                    return ToolResult.error("文件不存在: " + filePath);
                }

                String content = Files.readString(filePath, StandardCharsets.UTF_8);

                // 输入验证
                if (input.getOld_string() == null || input.getNew_string() == null) {
                    return ToolResult.error("old_string 和 new_string 不能为空");
                }
                if (input.getOld_string().equals(input.getNew_string())) {
                    return ToolResult.error("old_string 与 new_string 相同，无需编辑");
                }

                // 检查 old_string 是否存在
                int firstIndex = content.indexOf(input.getOld_string());
                if (firstIndex < 0) {
                    return ToolResult.error("在文件中未找到指定的文本: " + input.getOld_string());
                }

                // 严格唯一性校验（与 Claude Code edit 工具一致的语义）：
                // old_string 必须在文件中仅出现一次，否则要求调用者扩展上下文后重试，避免误替换。
                int secondIndex = content.indexOf(input.getOld_string(), firstIndex + 1);
                if (secondIndex >= 0) {
                    long count = countOccurrences(content, input.getOld_string());
                    return ToolResult.error(String.format(
                            "old_string 在文件中出现 %d 次，无法确定唯一替换位置；请扩展 old_string 使其包含更多上下文后重试。",
                            count));
                }

                int lineNum = (int) content.substring(0, firstIndex).lines().count() + 1;

                // 精确单次替换：通过 substring 拼接，避免 regex 转义的边界问题
                String newContent = content.substring(0, firstIndex)
                        + input.getNew_string()
                        + content.substring(firstIndex + input.getOld_string().length());

                // 原子写：写入临时文件后 ATOMIC_MOVE 替换，避免写入过程中崩溃留下半成品
                atomicWrite(filePath, newContent);

                // F-P0-4：写入编辑历史，供 undo_edit 使用
                long editId = EditHistoryManager.getInstance().record(
                        filePath.toString(), content, newContent, getName());

                // 附带轻量 diff 预览，帮助用户/LLM 快速确认修改范围
                String diffPreview = DiffUtils.diff(content, newContent);
                String result = String.format(
                        "成功编辑文件 %s (第 %d 行), edit_id=%d\n--- diff ---\n%s",
                        filePath, lineNum, editId, diffPreview);

                return ToolResult.success(result);

            } catch (IOException e) {
                logger.error("编辑文件失败", e);
                return ToolResult.error("编辑文件失败: " + e.getMessage());
            }
        });
    }

    /**
     * 统计子串在文本中出现的次数（不重叠）。
     */
    private static long countOccurrences(String text, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * 原子写：先写临时文件再 ATOMIC_MOVE 替换目标。
     *
     * 若文件系统不支持 ATOMIC_MOVE（极少数场景如 Windows 跨卷），回退到 REPLACE_EXISTING
     * 保证操作能完成；整个过程中若任何异常都会在写临时文件阶段被捕获，原文件保持不变。
     */
    private static void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.getParent() != null ? target.getParent() : target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(parent, ".jh-edit-", ".tmp");
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
