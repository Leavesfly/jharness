package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.FileWriteToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

                Files.writeString(filePath, input.getContent(), StandardCharsets.UTF_8);
                
                return ToolResult.success("成功写入文件: " + filePath);

            } catch (IOException e) {
                logger.error("写入文件失败", e);
                return ToolResult.error("写入文件失败: " + e.getMessage());
            }
        });
    }
}
