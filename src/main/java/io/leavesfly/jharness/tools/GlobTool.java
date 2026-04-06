package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.GlobToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 文件模式匹配工具
 *
 * 使用 glob 模式查找文件。
 */
public class GlobTool extends BaseTool<GlobToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(GlobTool.class);

    @Override
    public String getName() {
        return "glob";
    }

    @Override
    public String getDescription() {
        return "使用 glob 模式查找文件。例如: **/*.java, src/**/*.py";
    }

    @Override
    public Class<GlobToolInput> getInputClass() {
        return GlobToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(GlobToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path basePath = context.getCwd().resolve(input.getPath()).normalize();
                
                if (!Files.exists(basePath)) {
                    return ToolResult.error("路径不存在: " + basePath);
                }

                List<String> matchedFiles = new ArrayList<>();
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + input.getPattern());

                Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Path relativePath = basePath.relativize(file);
                        if (matcher.matches(relativePath)) {
                            matchedFiles.add(relativePath.toString());
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                });

                if (matchedFiles.isEmpty()) {
                    return ToolResult.success("未找到匹配的文件");
                }

                String result = matchedFiles.stream()
                        .limit(100)
                        .collect(Collectors.joining("\n"));

                if (matchedFiles.size() > 100) {
                    result += "\n...(共找到 " + matchedFiles.size() + " 个文件，仅显示前 100 个)";
                }

                return ToolResult.success(result);

            } catch (IOException e) {
                logger.error("文件搜索失败", e);
                return ToolResult.error("文件搜索失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(GlobToolInput input) {
        return true;
    }
}
