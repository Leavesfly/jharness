package io.leavesfly.jharness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jharness.tools.input.NotebookEditToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Notebook 编辑工具
 *
 * 创建或编辑 Jupyter 笔记本单元格。支持代码和 Markdown 单元格，
 * 支持替换和追加两种模式。不依赖外部库，直接操作 JSON。
 */
public class NotebookEditTool extends BaseTool<NotebookEditToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(NotebookEditTool.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "notebook_edit";
    }

    @Override
    public String getDescription() {
        return "创建或编辑 Jupyter 笔记本单元格。支持代码和 Markdown 单元格，支持替换和追加模式。";
    }

    @Override
    public Class<NotebookEditToolInput> getInputClass() {
        return NotebookEditToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(NotebookEditToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path notebookPath = resolvePath(context.getCwd(), input.getPath());

                // 加载或创建笔记本
                ObjectNode notebook = loadNotebook(notebookPath, input.isCreate_if_missing());
                if (notebook == null) {
                    return ToolResult.error("Notebook 不存在: " + notebookPath);
                }

                // 获取或创建 cells 数组
                ArrayNode cells = getOrCreateCells(notebook);

                // 自动填充缺失的单元格
                while (cells.size() <= input.getCell_index()) {
                    cells.add(createEmptyCell(input.getCell_type()));
                }

                // 获取目标单元格
                JsonNode targetCell = cells.get(input.getCell_index());

                // 更新单元格类型
                if (targetCell instanceof ObjectNode) {
                    ((ObjectNode) targetCell).put("cell_type", input.getCell_type());

                    // 确保 metadata 存在
                    if (!targetCell.has("metadata")) {
                        ((ObjectNode) targetCell).putObject("metadata");
                    }

                    // 代码单元格需要 outputs 和 execution_count
                    if ("code".equals(input.getCell_type())) {
                        if (!targetCell.has("outputs")) {
                            ((ObjectNode) targetCell).putArray("outputs");
                        }
                        if (!targetCell.has("execution_count")) {
                            ((ObjectNode) targetCell).putNull("execution_count");
                        }
                    }

                    // 更新 source
                    String existingSource = normalizeSource(targetCell.get("source"));
                    String updatedSource = "replace".equals(input.getMode())
                        ? input.getNew_source()
                        : existingSource + input.getNew_source();

                    ((ObjectNode) targetCell).put("source", updatedSource);
                }

                // 确保父目录存在并保存
                Files.createDirectories(notebookPath.getParent());
                String jsonOutput = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(notebook);
                Files.writeString(notebookPath, jsonOutput + "\n", StandardCharsets.UTF_8);

                return ToolResult.success(
                    String.format("已更新 notebook 单元格 %d 在 %s (%s 模式)",
                        input.getCell_index(),
                        notebookPath.getFileName().toString(),
                        input.getMode()));

            } catch (IOException e) {
                logger.error("Notebook 编辑失败", e);
                return ToolResult.error("Notebook 编辑失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(NotebookEditToolInput input) {
        return false;
    }

    /**
     * 解析路径（相对或绝对）
     */
    private Path resolvePath(Path base, String path) {
        Path resolved = Path.of(path);
        if (!resolved.isAbsolute()) {
            resolved = base.resolve(resolved).normalize();
        }
        return resolved;
    }

    /**
     * 加载笔记本 JSON，如果不存在则创建新笔记本
     */
    private ObjectNode loadNotebook(Path path, boolean createIfMissing) throws IOException {
        if (Files.exists(path)) {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return (ObjectNode) OBJECT_MAPPER.readTree(content);
        }

        if (!createIfMissing) {
            return null;
        }

        // 创建新笔记本
        ObjectNode notebook = OBJECT_MAPPER.createObjectNode();
        notebook.putArray("cells");
        notebook.put("nbformat", 4);
        notebook.put("nbformat_minor", 5);
        ObjectNode metadata = notebook.putObject("metadata");
        ObjectNode languageInfo = metadata.putObject("language_info");
        languageInfo.put("name", "python");

        return notebook;
    }

    /**
     * 获取或创建 cells 数组
     */
    private ArrayNode getOrCreateCells(ObjectNode notebook) {
        JsonNode cellsNode = notebook.get("cells");
        if (cellsNode == null || !cellsNode.isArray()) {
            return notebook.putArray("cells");
        }
        return (ArrayNode) cellsNode;
    }

    /**
     * 创建空单元格
     */
    private ObjectNode createEmptyCell(String cellType) {
        ObjectNode cell = OBJECT_MAPPER.createObjectNode();
        cell.put("cell_type", cellType);
        cell.putObject("metadata");
        cell.put("source", "");

        if ("code".equals(cellType)) {
            cell.putArray("outputs");
            cell.putNull("execution_count");
        }

        return cell;
    }

    /**
     * 规范化 source（处理字符串或数组格式）
     */
    private String normalizeSource(JsonNode sourceNode) {
        if (sourceNode == null || sourceNode.isNull()) {
            return "";
        }
        if (sourceNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode element : sourceNode) {
                sb.append(element.asText());
            }
            return sb.toString();
        }
        return sourceNode.asText();
    }
}
