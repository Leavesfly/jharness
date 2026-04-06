package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.FileEditToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

                // 检查 old_string 是否存在
                if (!content.contains(input.getOld_string())) {
                    return ToolResult.error("在文件中未找到指定的文本: " + input.getOld_string());
                }

                // 计算替换位置
                int index = content.indexOf(input.getOld_string());
                int lineNum = (int) content.substring(0, index).lines().count() + 1;

                // 执行替换
                String newContent = content.replaceFirst(
                    java.util.regex.Pattern.quote(input.getOld_string()),
                    java.util.regex.Matcher.quoteReplacement(input.getNew_string())
                );

                Files.writeString(filePath, newContent, StandardCharsets.UTF_8);
                
                return ToolResult.success(
                    String.format("成功编辑文件 %s (第 %d 行)", filePath, lineNum)
                );

            } catch (IOException e) {
                logger.error("编辑文件失败", e);
                return ToolResult.error("编辑文件失败: " + e.getMessage());
            }
        });
    }
}
