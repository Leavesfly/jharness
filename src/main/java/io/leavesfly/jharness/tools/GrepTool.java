package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.GrepToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Grep 内容搜索工具
 *
 * 在文件中搜索文本内容，支持正则表达式。
 */
public class GrepTool extends BaseTool<GrepToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(GrepTool.class);
    private static final int MAX_RESULTS = 100;

    @Override
    public String getName() {
        return "grep";
    }

    @Override
    public String getDescription() {
        return "在文件内容中搜索文本。支持正则表达式。";
    }

    @Override
    public Class<GrepToolInput> getInputClass() {
        return GrepToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(GrepToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path basePath = context.getCwd().resolve(input.getPath()).normalize();
                Pattern pattern = Pattern.compile(input.getPattern(), Pattern.CASE_INSENSITIVE);

                List<String> results = new ArrayList<>();
                int[] count = {0};

                Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (count[0] >= MAX_RESULTS) {
                            return FileVisitResult.TERMINATE;
                        }

                        // 跳过隐藏文件
                        if (!input.getInclude_hidden() && file.getFileName().toString().startsWith(".")) {
                            return FileVisitResult.CONTINUE;
                        }

                        try {
                            List<String> lines = Files.readAllLines(file);
                            for (int i = 0; i < lines.size(); i++) {
                                if (pattern.matcher(lines.get(i)).find()) {
                                    Path relativePath = basePath.relativize(file);
                                    results.add(relativePath + ":" + (i + 1) + ":" + lines.get(i));
                                    count[0]++;
                                    if (count[0] >= MAX_RESULTS) break;
                                }
                            }
                        } catch (IOException e) {
                            // 跳过无法读取的文件
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });

                if (results.isEmpty()) {
                    return ToolResult.success("未找到匹配的内容");
                }

                String output = String.join("\n", results);
                if (count[0] >= MAX_RESULTS) {
                    output += "\n...(已达到结果限制)";
                }

                return ToolResult.success(output);

            } catch (IOException e) {
                logger.error("Grep 搜索失败", e);
                return ToolResult.error("Grep 搜索失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(GrepToolInput input) {
        return true;
    }
}
