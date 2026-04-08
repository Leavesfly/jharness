package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.GrepToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    /** 单文件大小上限（50MB），超过则跳过，防止 OOM */
    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;

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
                            // 跳过超大文件，防止一次性读入导致 OOM
                            if (attrs.size() > MAX_FILE_SIZE_BYTES) {
                                logger.debug("跳过超大文件 ({}MB): {}", attrs.size() / 1024 / 1024, file);
                                return FileVisitResult.CONTINUE;
                            }
                            // 使用 BufferedReader 流式逐行读取，避免大文件全量加载到内存
                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
                                String line;
                                int lineNumber = 0;
                                while ((line = reader.readLine()) != null) {
                                    lineNumber++;
                                    if (pattern.matcher(line).find()) {
                                        Path relativePath = basePath.relativize(file);
                                        results.add(relativePath + ":" + lineNumber + ":" + line);
                                        count[0]++;
                                        if (count[0] >= MAX_RESULTS) break;
                                    }
                                }
                            }
                        } catch (IOException e) {
                            // 跳过无法读取的文件（权限不足、二进制文件等）
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
