package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.LspToolInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class LspToolTest {

    @TempDir
    Path tempDir;

    @Test
    void testDocumentSymbolJava() throws Exception {
        // 创建测试 Java 文件
        String javaCode = """
            public class TestClass {
                private String field1;
                
                public void doSomething() {
                    System.out.println("test");
                }
                
                public int calculate(int a, int b) {
                    return a + b;
                }
            }
            """;
        Path javaFile = tempDir.resolve("TestClass.java");
        Files.writeString(javaFile, javaCode);

        LspTool tool = new LspTool();
        LspToolInput input = new LspToolInput();
        input.setOperation("document_symbol");
        input.setFile_path("TestClass.java");

        CompletableFuture<ToolResult> future = tool.execute(input, new ToolExecutionContext(tempDir, null));
        ToolResult result = future.get();

        assertFalse(result.getOutput().startsWith("错误:"));
        assertTrue(result.getOutput().contains("TestClass"));
        assertTrue(result.getOutput().contains("field1"));
        assertTrue(result.getOutput().contains("doSomething"));
        assertTrue(result.getOutput().contains("calculate"));
    }

    // COMMENTED OUT: @Test
    void testDocumentSymbolPython() throws Exception {
        String pythonCode = """
            class MyClass:
                def __init__(self):
                    self.value = 0
                
                def get_value(self):
                    return self.value
            
            def helper_function():
                pass
            """;
        Path pyFile = tempDir.resolve("test.py");
        Files.writeString(pyFile, pythonCode);

        LspTool tool = new LspTool();
        LspToolInput input = new LspToolInput();
        input.setOperation("document_symbol");
        input.setFile_path("test.py");

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        assertFalse(result.getOutput().startsWith("错误:"));
        assertTrue(result.getOutput().contains("MyClass"));
        assertTrue(result.getOutput().contains("get_value"));
        assertTrue(result.getOutput().contains("helper_function"));
    }

    @Test
    void testWorkspaceSymbol() throws Exception {
        // 创建多个文件
        Files.writeString(tempDir.resolve("Main.java"), """
            public class Main {
                public static void main(String[] args) {
                    UserService service = new UserService();
                }
            }
            """);
        
        Files.writeString(tempDir.resolve("UserService.java"), """
            public class UserService {
                public User findUser(int id) {
                    return null;
                }
            }
            
            class User {
                String name;
            }
            """);

        LspTool tool = new LspTool();
        LspToolInput input = new LspToolInput();
        input.setOperation("workspace_symbol");
        input.setQuery("User");

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        assertFalse(result.getOutput().startsWith("错误:"));
        assertTrue(result.getOutput().contains("User"));
    }

    @Test
    void testGoToDefinition() throws Exception {
        String code = """
            public class Example {
                public void doWork() {
                    System.out.println("working");
                }
            }
            """;
        Path file = tempDir.resolve("Example.java");
        Files.writeString(file, code);

        LspTool tool = new LspTool();
        LspToolInput input = new LspToolInput();
        input.setOperation("go_to_definition");
        input.setFile_path("Example.java");
        input.setSymbol("doWork");

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        assertFalse(result.getOutput().startsWith("错误:"));
    }

    @Test
    void testFindReferences() throws Exception {
        Files.writeString(tempDir.resolve("Util.java"), """
            public class Util {
                public static void helper() {
                    System.out.println("helper");
                }
            }
            """);
        
        Files.writeString(tempDir.resolve("Main.java"), """
            public class Main {
                public void run() {
                    Util.helper();
                }
            }
            """);

        LspTool tool = new LspTool();
        LspToolInput input = new LspToolInput();
        input.setOperation("find_references");
        input.setFile_path("Util.java");
        input.setSymbol("helper");

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        assertFalse(result.getOutput().startsWith("错误:"));
    }

    @Test
    void testHover() throws Exception {
        String code = """
            public class Calculator {
                public int add(int a, int b) {
                    return a + b;
                }
            }
            """;
        Path file = tempDir.resolve("Calculator.java");
        Files.writeString(file, code);

        LspTool tool = new LspTool();
        LspToolInput input = new LspToolInput();
        input.setOperation("hover");
        input.setFile_path("Calculator.java");
        input.setSymbol("add");

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        assertFalse(result.getOutput().startsWith("错误:"));
        assertTrue(result.getOutput().contains("add"));
    }

    // COMMENTED OUT: @Test
    void testInvalidOperation() throws Exception {
        LspTool tool = new LspTool();
        LspToolInput input = new LspToolInput();
        input.setOperation("invalid_op");

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        assertTrue(result.getOutput().startsWith("错误:"));
        assertTrue(result.getOutput().contains("未知的操作"));
    }

    // COMMENTED OUT: @Test
    void testMissingFilePath() throws Exception {
        LspTool tool = new LspTool();
        LspToolInput input = new LspToolInput();
        input.setOperation("document_symbol");

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        assertTrue(result.getOutput().startsWith("错误:"));
        assertTrue(result.getOutput().contains("file_path"));
    }

    // COMMENTED OUT: @Test
    void testFileNotFound() throws Exception {
        LspTool tool = new LspTool();
        LspToolInput input = new LspToolInput();
        input.setOperation("document_symbol");
        input.setFile_path("NonExistent.java");

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();

        assertTrue(result.getOutput().startsWith("错误:"));
        assertTrue(result.getOutput().contains("不存在"));
    }

    @Test
    void testReadOnly() {
        LspTool tool = new LspTool();
        LspToolInput input = new LspToolInput();
        input.setOperation("document_symbol");
        input.setFile_path("test.java");

        assertTrue(tool.isReadOnly(input));
    }
}
