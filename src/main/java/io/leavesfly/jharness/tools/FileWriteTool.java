package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.core.edit.DiffUtils;
import io.leavesfly.jharness.core.edit.EditHistoryManager;
import io.leavesfly.jharness.tools.input.FileWriteToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/**
 * 文件写入工具
 *
 * 创建新文件或覆盖现有文件内容。
 */
public class FileWriteTool extends BaseTool<FileWriteToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(FileWriteTool.class);

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "创建新文件或覆盖现有文件内容。";
    }

    @Override
    public Class<FileWriteToolInput> getInputClass() {
        return FileWriteToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(FileWriteToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path filePath = context.getCwd().resolve(input.getFile_path()).normalize();
                
                // 安全检查：防止路径遍历攻击
                if (!filePath.startsWith(context.getCwd().toAbsolutePath().normalize())) {
                    return ToolResult.error("安全限制: 不允许写入工作目录之外的文件");
                }

                // 确保父目录存在
                if (filePath.getParent() != null) {
                    Files.createDirectories(filePath.getParent());
                }

                // F-P0-4：读取原内容用于历史记录；不存在则记录为 null 表示新建
                String originalContent = Files.exists(filePath)
                        ? Files.readString(filePath, StandardCharsets.UTF_8)
                        : null;

                // 原子写：先写临时文件再 ATOMIC_MOVE 替换，避免写入中途崩溃留下半成品文件
                atomicWrite(filePath, input.getContent());

                long editId = EditHistoryManager.getInstance().record(
                        filePath.toString(), originalContent, input.getContent(), getName());

                String diffPreview = DiffUtils.diff(
                        originalContent == null ? "" : originalContent,
                        input.getContent());
                return ToolResult.success(String.format(
                        "成功写入文件: %s, edit_id=%d\n--- diff ---\n%s",
                        filePath, editId, diffPreview));

            } catch (IOException e) {
                logger.error("写入文件失败", e);
                return ToolResult.error("写入文件失败: " + e.getMessage());
            }
        });
    }

    /**
     * 原子写：先写临时文件再 ATOMIC_MOVE 替换目标。
     *
     * 若文件系统不支持 ATOMIC_MOVE（如 Windows 跨卷），回退到 REPLACE_EXISTING。
     */
    private static void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.getParent() != null ? target.getParent() : target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(parent, ".jh-write-", ".tmp");
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
