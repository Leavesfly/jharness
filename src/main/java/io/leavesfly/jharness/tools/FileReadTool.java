package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.FileReadToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 文件读取工具
 *
 * 读取文件内容并返回。支持按行范围读取。
 */
public class FileReadTool extends BaseTool<FileReadToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(FileReadTool.class);
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int MAX_LINES = 500;

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "读取文件内容。支持指定起始行和行数限制。";
    }

    @Override
    public Class<FileReadToolInput> getInputClass() {
        return FileReadToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(FileReadToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path filePath = context.getCwd().resolve(input.getFile_path()).normalize();
                
                // 安全检查：防止路径遍历攻击
                if (!filePath.startsWith(context.getCwd().toAbsolutePath().normalize())) {
                    return ToolResult.error("安全限制: 不允许访问工作目录之外的文件");
                }

                // 安全检查：禁止通过符号链接逃逸
                if (Files.exists(filePath) && Files.isSymbolicLink(filePath)) {
                    Path realPath = filePath.toRealPath();
                    if (!realPath.startsWith(context.getCwd().toAbsolutePath().normalize())) {
                        return ToolResult.error("安全限制: 符号链接指向工作目录之外");
                    }
                }

                if (!Files.exists(filePath)) {
                    return ToolResult.error("文件不存在: " + filePath);
                }

                if (!Files.isRegularFile(filePath)) {
                    return ToolResult.error("不是普通文件: " + filePath);
                }

                int offset = input.getOffset() != null ? input.getOffset() : 1;
                int limit = input.getLimit() != null ? input.getLimit() : MAX_LINES;

                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                    String line;
                    int lineNum = 0;
                    while ((line = reader.readLine()) != null) {
                        lineNum++;
                        
                        if (lineNum < offset) continue;
                        if (lineNum >= offset + limit) {
                            content.append("...(已达到行数限制，共").append(limit).append("行)\n");
                            break;
                        }

                        // 截断过长的行
                        if (line.length() > MAX_LINE_LENGTH) {
                            line = line.substring(0, MAX_LINE_LENGTH) + "...(行已截断)";
                        }

                        content.append(lineNum).append("\t").append(line).append("\n");
                    }
                }

                return ToolResult.success(content.toString());

            } catch (IOException e) {
                logger.error("读取文件失败", e);
                return ToolResult.error("读取文件失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(FileReadToolInput input) {
        return true;
    }
}
