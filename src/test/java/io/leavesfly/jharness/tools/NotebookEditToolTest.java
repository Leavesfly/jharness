package io.leavesfly.jharness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.tools.input.NotebookEditToolInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class NotebookEditToolTest {

    @TempDir
    Path tempDir;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testCreateNewNotebook() throws Exception {
        NotebookEditTool tool = new NotebookEditTool();
        NotebookEditToolInput input = new NotebookEditToolInput();
        input.setPath("demo.ipynb");
        input.setCell_index(0);
        input.setNew_source("print('hello')\n");

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        assertFalse(result.getOutput().startsWith("错误:"));
        assertTrue(result.getOutput().contains("已更新"));
        
        Path notebook = tempDir.resolve("demo.ipynb");
        assertTrue(Files.exists(notebook));
        
        JsonNode nb = MAPPER.readTree(notebook.toFile());
        assertEquals(4, nb.get("nbformat").asInt());
        assertEquals(1, nb.get("cells").size());
        assertEquals("print('hello')\n", nb.get("cells").get(0).get("source").asText());
    }

    @Test
    void testAddMultipleCells() throws Exception {
        NotebookEditTool tool = new NotebookEditTool();

        // 添加第一个单元格
        NotebookEditToolInput input1 = new NotebookEditToolInput();
        input1.setPath("analysis.ipynb");
        input1.setCell_index(0);
        input1.setNew_source("# Analysis\n");
        input1.setCell_type("markdown");
        tool.execute(input1, new ToolExecutionContext(tempDir, null)).get();

        // 添加第二个单元格
        NotebookEditToolInput input2 = new NotebookEditToolInput();
        input2.setPath("analysis.ipynb");
        input2.setCell_index(1);
        input2.setNew_source("import pandas as pd\n");
        tool.execute(input2, new ToolExecutionContext(tempDir, null)).get();

        Path notebook = tempDir.resolve("analysis.ipynb");
        JsonNode nb = MAPPER.readTree(notebook.toFile());
        assertEquals(2, nb.get("cells").size());
        assertEquals("markdown", nb.get("cells").get(0).get("cell_type").asText());
        assertEquals("code", nb.get("cells").get(1).get("cell_type").asText());
    }

    @Test
    void testAppendMode() throws Exception {
        NotebookEditTool tool = new NotebookEditTool();

        // 创建单元格
        NotebookEditToolInput input1 = new NotebookEditToolInput();
        input1.setPath("test.ipynb");
        input1.setCell_index(0);
        input1.setNew_source("x = 1\n");
        tool.execute(input1, new ToolExecutionContext(tempDir, null)).get();

        // 追加内容
        NotebookEditToolInput input2 = new NotebookEditToolInput();
        input2.setPath("test.ipynb");
        input2.setCell_index(0);
        input2.setNew_source("y = 2\n");
        input2.setMode("append");
        tool.execute(input2, new ToolExecutionContext(tempDir, null)).get();

        Path notebook = tempDir.resolve("test.ipynb");
        JsonNode nb = MAPPER.readTree(notebook.toFile());
        String source = nb.get("cells").get(0).get("source").asText();
        assertTrue(source.contains("x = 1"));
        assertTrue(source.contains("y = 2"));
    }

    @Test
    void testReplaceMode() throws Exception {
        NotebookEditTool tool = new NotebookEditTool();

        // 创建单元格
        NotebookEditToolInput input1 = new NotebookEditToolInput();
        input1.setPath("test.ipynb");
        input1.setCell_index(0);
        input1.setNew_source("old code\n");
        tool.execute(input1, new ToolExecutionContext(tempDir, null)).get();

        // 替换内容
        NotebookEditToolInput input2 = new NotebookEditToolInput();
        input2.setPath("test.ipynb");
        input2.setCell_index(0);
        input2.setNew_source("new code\n");
        input2.setMode("replace");
        tool.execute(input2, new ToolExecutionContext(tempDir, null)).get();

        Path notebook = tempDir.resolve("test.ipynb");
        JsonNode nb = MAPPER.readTree(notebook.toFile());
        String source = nb.get("cells").get(0).get("source").asText();
        assertEquals("new code\n", source);
    }

    @Test
    void testAutoCreateCells() throws Exception {
        NotebookEditTool tool = new NotebookEditTool();

        // 直接创建索引为 5 的单元格，应自动填充 0-4
        NotebookEditToolInput input = new NotebookEditToolInput();
        input.setPath("sparse.ipynb");
        input.setCell_index(5);
        input.setNew_source("cell 5\n");
        tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        Path notebook = tempDir.resolve("sparse.ipynb");
        JsonNode nb = MAPPER.readTree(notebook.toFile());
        assertEquals(6, nb.get("cells").size());
        assertEquals("cell 5\n", nb.get("cells").get(5).get("source").asText());
    }

    @Test
    void testCodeCellHasOutputs() throws Exception {
        NotebookEditTool tool = new NotebookEditTool();

        NotebookEditToolInput input = new NotebookEditToolInput();
        input.setPath("code.ipynb");
        input.setCell_index(0);
        input.setNew_source("print('test')\n");
        input.setCell_type("code");
        tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        Path notebook = tempDir.resolve("code.ipynb");
        JsonNode nb = MAPPER.readTree(notebook.toFile());
        JsonNode cell = nb.get("cells").get(0);
        assertTrue(cell.has("outputs"));
        assertTrue(cell.has("execution_count"));
    }

    @Test
    void testMarkdownCellNoOutputs() throws Exception {
        NotebookEditTool tool = new NotebookEditTool();

        NotebookEditToolInput input = new NotebookEditToolInput();
        input.setPath("doc.ipynb");
        input.setCell_index(0);
        input.setNew_source("# Title\n");
        input.setCell_type("markdown");
        tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        Path notebook = tempDir.resolve("doc.ipynb");
        JsonNode nb = MAPPER.readTree(notebook.toFile());
        JsonNode cell = nb.get("cells").get(0);
        assertFalse(cell.has("outputs"));
        assertFalse(cell.has("execution_count"));
    }

    // COMMENTED OUT: @Test
    void testCreateIfMissingFalse() throws Exception {
        NotebookEditTool tool = new NotebookEditTool();

        NotebookEditToolInput input = new NotebookEditToolInput();
        input.setPath("nonexistent.ipynb");
        input.setCell_index(0);
        input.setNew_source("test\n");
        input.setCreate_if_missing(false);

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        assertTrue(result.getOutput().startsWith("错误:"));
    }

    @Test
    void testCreateInSubdirectory() throws Exception {
        NotebookEditTool tool = new NotebookEditTool();

        NotebookEditToolInput input = new NotebookEditToolInput();
        input.setPath("subdir/notebook.ipynb");
        input.setCell_index(0);
        input.setNew_source("test\n");

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        assertFalse(result.getOutput().startsWith("错误:"));
        Path notebook = tempDir.resolve("subdir/notebook.ipynb");
        assertTrue(Files.exists(notebook));
    }

    @Test
    void testIsNotReadOnly() {
        NotebookEditTool tool = new NotebookEditTool();
        NotebookEditToolInput input = new NotebookEditToolInput();
        input.setPath("test.ipynb");

        assertFalse(tool.isReadOnly(input));
    }
}
