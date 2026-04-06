package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.LspToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * LSP 代码智能工具
 *
 * 提供代码符号提取、工作区符号搜索、定义跳转、引用查找和悬停信息功能。
 * 支持 Java 和 Python 文件的轻量级 AST 分析。
 */
public class LspTool extends BaseTool<LspToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(LspTool.class);

    // 预编译的正则表达式
    private static final Pattern JAVA_CLASS_PATTERN = Pattern.compile(
            "^\\s*(?:public|private|protected)?\\s*(?:static)?\\s*(?:abstract)?\\s*class\\s+(\\w+)");
    private static final Pattern JAVA_INTERFACE_PATTERN = Pattern.compile(
            "^\\s*(?:public|private|protected)?\\s*interface\\s+(\\w+)");
    private static final Pattern JAVA_METHOD_PATTERN = Pattern.compile(
            "^\\s*(?:public|private|protected)?\\s*(?:static)?\\s*(?:abstract)?\\s*(?:[\\w<>\\[\\],\\s]+)\\s+(\\w+)\\s*\\(");
    private static final Pattern JAVA_FIELD_PATTERN = Pattern.compile(
            "^\\s*(?:public|private|protected)?\\s*(?:static)?\\s*(?:final)?\\s*([\\w<>\\[\\],\\s]+)\\s+(\\w+)\\s*[=;]");
    private static final Pattern PYTHON_CLASS_PATTERN = Pattern.compile("^class\\s+(\\w+)");
    private static final Pattern PYTHON_FUNCTION_PATTERN = Pattern.compile("^(?:async\\s+)?def\\s+(\\w+)\\s*\\(");
    private static final Pattern JS_FUNCTION_PATTERN = Pattern.compile(
            "(?:function\\s+(\\w+)|const\\s+(\\w+)\\s*=\\s*(?:async\\s+)?\\(|let\\s+(\\w+)\\s*=)");
    private static final Pattern JS_CLASS_PATTERN = Pattern.compile("class\\s+(\\w+)");
    private static final java.util.Set<String> JAVA_KEYWORDS = java.util.Set.of(
            "public", "private", "protected", "static", "final", "abstract", "class", "interface",
            "void", "int", "long", "double", "float", "boolean", "char", "byte", "short", "String",
            "new", "return", "if", "else", "for", "while", "do", "switch", "case", "break",
            "continue", "try", "catch", "finally", "throw", "throws", "extends", "implements",
            "import", "package", "this", "super", "instanceof", "enum", "const", "goto", "assert",
            "transient", "volatile", "strictfp", "native", "synchronized");

    @Override
    public String getName() {
        return "lsp";
    }

    @Override
    public String getDescription() {
        return "检查代码符号、定义、引用和悬停信息跨当前工作区。支持 document_symbol, workspace_symbol, go_to_definition, find_references, hover 操作。";
    }

    @Override
    public Class<LspToolInput> getInputClass() {
        return LspToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(LspToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path root = context.getCwd();

                switch (input.getOperation()) {
                    case "document_symbol":
                        return handleDocumentSymbol(input, root);
                    case "workspace_symbol":
                        return handleWorkspaceSymbol(input, root);
                    case "go_to_definition":
                        return handleGoToDefinition(input, root);
                    case "find_references":
                        return handleFindReferences(input, root);
                    case "hover":
                        return handleHover(input, root);
                    default:
                        return ToolResult.error("未知的操作: " + input.getOperation() + 
                            "。支持的操作: document_symbol, workspace_symbol, go_to_definition, find_references, hover");
                }
            } catch (Exception e) {
                logger.error("LSP 操作失败", e);
                return ToolResult.error("LSP 操作失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(LspToolInput input) {
        return true;
    }

    private ToolResult handleDocumentSymbol(LspToolInput input, Path root) {
        if (input.getFile_path() == null || input.getFile_path().isEmpty()) {
            return ToolResult.error("document_symbol 需要提供 file_path 参数");
        }

        try {
            Path filePath = root.resolve(input.getFile_path()).normalize();
            if (!Files.exists(filePath)) {
                return ToolResult.error("文件不存在: " + filePath);
            }

            List<Map<String, Object>> symbols = extractDocumentSymbols(filePath);
            return ToolResult.success(formatSymbols(symbols, "document_symbol", filePath.toString()));
        } catch (IOException e) {
            logger.error("提取文档符号失败", e);
            return ToolResult.error("提取文档符号失败: " + e.getMessage());
        }
    }

    private ToolResult handleWorkspaceSymbol(LspToolInput input, Path root) {
        if (input.getQuery() == null || input.getQuery().isEmpty()) {
            return ToolResult.error("workspace_symbol 需要提供 query 参数");
        }

        try {
            List<Map<String, Object>> symbols = searchWorkspaceSymbols(root, input.getQuery());
            return ToolResult.success(formatSymbols(symbols, "workspace_symbol", root.toString()));
        } catch (IOException e) {
            logger.error("工作区符号搜索失败", e);
            return ToolResult.error("工作区符号搜索失败: " + e.getMessage());
        }
    }

    private ToolResult handleGoToDefinition(LspToolInput input, Path root) {
        if (input.getFile_path() == null || input.getFile_path().isEmpty()) {
            return ToolResult.error("go_to_definition 需要提供 file_path 参数");
        }

        try {
            Path filePath = root.resolve(input.getFile_path()).normalize();
            if (!Files.exists(filePath)) {
                return ToolResult.error("文件不存在: " + filePath);
            }

            String symbol = input.getSymbol();
            if (symbol == null || symbol.isEmpty()) {
                return ToolResult.error("go_to_definition 需要提供 symbol 参数");
            }

            List<Map<String, Object>> definitions = findSymbolDefinitions(filePath, symbol);
            if (definitions.isEmpty()) {
                return ToolResult.success("未找到符号 '" + symbol + "' 的定义");
            }

            return ToolResult.success(formatDefinitions(definitions, filePath.toString()));
        } catch (IOException e) {
            logger.error("查找定义失败", e);
            return ToolResult.error("查找定义失败: " + e.getMessage());
        }
    }

    private ToolResult handleFindReferences(LspToolInput input, Path root) {
        if (input.getFile_path() == null || input.getFile_path().isEmpty()) {
            return ToolResult.error("find_references 需要提供 file_path 参数");
        }

        try {
            Path filePath = root.resolve(input.getFile_path()).normalize();
            if (!Files.exists(filePath)) {
                return ToolResult.error("文件不存在: " + filePath);
            }

            String symbol = input.getSymbol();
            if (symbol == null || symbol.isEmpty()) {
                return ToolResult.error("find_references 需要提供 symbol 参数");
            }

            List<Map<String, Object>> references = findAllReferences(root, symbol);
            return ToolResult.success(formatReferences(references, symbol));
        } catch (IOException e) {
            logger.error("查找引用失败", e);
            return ToolResult.error("查找引用失败: " + e.getMessage());
        }
    }

    private ToolResult handleHover(LspToolInput input, Path root) {
        if (input.getFile_path() == null || input.getFile_path().isEmpty()) {
            return ToolResult.error("hover 需要提供 file_path 参数");
        }

        try {
            Path filePath = root.resolve(input.getFile_path()).normalize();
            if (!Files.exists(filePath)) {
                return ToolResult.error("文件不存在: " + filePath);
            }

            String symbol = input.getSymbol();
            if (symbol == null || symbol.isEmpty()) {
                return ToolResult.error("hover 需要提供 symbol 参数");
            }

            Map<String, Object> hoverInfo = getSymbolHover(filePath, symbol);
            return ToolResult.success(formatHover(hoverInfo));
        } catch (IOException e) {
            logger.error("获取悬停信息失败", e);
            return ToolResult.error("获取悬停信息失败: " + e.getMessage());
        }
    }

    // ====== 符号提取逻辑 ======

    private List<Map<String, Object>> extractDocumentSymbols(Path filePath) throws IOException {
        List<Map<String, Object>> symbols = new ArrayList<>();
        List<String> lines = Files.readAllLines(filePath);
        String fileName = filePath.getFileName().toString();

        if (fileName.endsWith(".java")) {
            extractJavaSymbols(lines, symbols);
        } else if (fileName.endsWith(".py")) {
            extractPythonSymbols(lines, symbols);
        } else if (fileName.endsWith(".js") || fileName.endsWith(".ts")) {
            extractJavaScriptSymbols(lines, symbols);
        }

        return symbols;
    }

    private void extractJavaSymbols(List<String> lines, List<Map<String, Object>> symbols) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            Matcher classMatcher = JAVA_CLASS_PATTERN.matcher(line);
            if (classMatcher.find()) {
                symbols.add(createSymbol(classMatcher.group(1), "class", i + 1, 1));
                continue;
            }

            Matcher interfaceMatcher = JAVA_INTERFACE_PATTERN.matcher(line);
            if (interfaceMatcher.find()) {
                symbols.add(createSymbol(interfaceMatcher.group(1), "interface", i + 1, 1));
                continue;
            }

            Matcher methodMatcher = JAVA_METHOD_PATTERN.matcher(line);
            if (methodMatcher.find()) {
                String methodName = methodMatcher.group(1);
                if (!isJavaKeyword(methodName)) {
                    symbols.add(createSymbol(methodName, "method", i + 1, 1));
                }
                continue;
            }

            Matcher fieldMatcher = JAVA_FIELD_PATTERN.matcher(line);
            if (fieldMatcher.find()) {
                String fieldName = fieldMatcher.group(2);
                if (!isJavaKeyword(fieldName) && !fieldName.equals("class") && !fieldName.equals("interface")) {
                    symbols.add(createSymbol(fieldName, "field", i + 1, 1));
                }
            }
        }
    }

    private void extractPythonSymbols(List<String> lines, List<Map<String, Object>> symbols) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            Matcher classMatcher = PYTHON_CLASS_PATTERN.matcher(line);
            if (classMatcher.find()) {
                symbols.add(createSymbol(classMatcher.group(1), "class", i + 1, 1));
                continue;
            }

            Matcher functionMatcher = PYTHON_FUNCTION_PATTERN.matcher(line);
            if (functionMatcher.find()) {
                symbols.add(createSymbol(functionMatcher.group(1), "function", i + 1, 1));
            }
        }
    }

    private void extractJavaScriptSymbols(List<String> lines, List<Map<String, Object>> symbols) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            Matcher classMatcher = JS_CLASS_PATTERN.matcher(line);
            if (classMatcher.find()) {
                symbols.add(createSymbol(classMatcher.group(1), "class", i + 1, 1));
                continue;
            }

            Matcher functionMatcher = JS_FUNCTION_PATTERN.matcher(line);
            if (functionMatcher.find()) {
                String name = functionMatcher.group(1) != null ? functionMatcher.group(1) :
                             functionMatcher.group(2) != null ? functionMatcher.group(2) :
                             functionMatcher.group(3);
                if (name != null) {
                    symbols.add(createSymbol(name, "function", i + 1, 1));
                }
            }
        }
    }

    private Map<String, Object> createSymbol(String name, String kind, int line, int character) {
        Map<String, Object> symbol = new HashMap<>();
        symbol.put("name", name);
        symbol.put("kind", kind);
        symbol.put("line", line);
        symbol.put("character", character);
        return symbol;
    }

    private List<Map<String, Object>> searchWorkspaceSymbols(Path root, String query) throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();
        Pattern queryPattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> isSourceFile(p))
                 .limit(1000)
                 .forEach(filePath -> {
                     try {
                         List<String> lines = Files.readAllLines(filePath);
                         for (int i = 0; i < lines.size(); i++) {
                             String line = lines.get(i);
                             Matcher matcher = queryPattern.matcher(line);
                             if (matcher.find()) {
                                 Map<String, Object> symbol = new HashMap<>();
                                 symbol.put("name", extractSymbolName(line, query));
                                 symbol.put("kind", detectSymbolKind(line));
                                 symbol.put("path", root.relativize(filePath).toString());
                                 symbol.put("line", i + 1);
                                 symbol.put("preview", line.trim());
                                 results.add(symbol);
                             }
                         }
                     } catch (IOException e) {
                         logger.warn("读取文件失败: {}", filePath, e);
                     }
                 });
        }

        return results;
    }

    private List<Map<String, Object>> findSymbolDefinitions(Path filePath, String symbol) throws IOException {
        List<Map<String, Object>> definitions = new ArrayList<>();
        List<String> lines = Files.readAllLines(filePath);
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher matcher = pattern.matcher(line);
            if (matcher.find() && isDefinitionLine(line, symbol)) {
                Map<String, Object> definition = new HashMap<>();
                definition.put("name", symbol);
                definition.put("line", i + 1);
                definition.put("character", matcher.start() + 1);
                definition.put("preview", line.trim());
                definitions.add(definition);
            }
        }

        return definitions;
    }

    private List<Map<String, Object>> findAllReferences(Path root, String symbol) throws IOException {
        List<Map<String, Object>> references = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> isSourceFile(p))
                 .limit(1000)
                 .forEach(filePath -> {
                     try {
                         List<String> lines = Files.readAllLines(filePath);
                         for (int i = 0; i < lines.size(); i++) {
                             String line = lines.get(i);
                             Matcher matcher = pattern.matcher(line);
                             if (matcher.find()) {
                                 Map<String, Object> ref = new HashMap<>();
                                 ref.put("path", root.relativize(filePath).toString());
                                 ref.put("line", i + 1);
                                 ref.put("character", matcher.start() + 1);
                                 ref.put("preview", line.trim());
                                 references.add(ref);
                             }
                         }
                     } catch (IOException e) {
                         logger.warn("读取文件失败: {}", filePath, e);
                     }
                 });
        }

        return references;
    }

    private Map<String, Object> getSymbolHover(Path filePath, String symbol) throws IOException {
        List<String> lines = Files.readAllLines(filePath);
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher matcher = pattern.matcher(line);
            if (matcher.find() && isDefinitionLine(line, symbol)) {
                Map<String, Object> hover = new HashMap<>();
                hover.put("name", symbol);
                hover.put("line", i + 1);
                hover.put("signature", extractSignature(line, symbol));
                hover.put("preview", line.trim());
                return hover;
            }
        }

        return new HashMap<>();
    }

    // ====== 辅助方法 ======

    private boolean isSourceFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".py") || 
               name.endsWith(".js") || name.endsWith(".ts") ||
               name.endsWith(".jsx") || name.endsWith(".tsx");
    }

    private boolean isJavaKeyword(String word) {
        return JAVA_KEYWORDS.contains(word);
    }

    private boolean isDefinitionLine(String line, String symbol) {
        String trimmed = line.trim();
        return trimmed.contains("class " + symbol) ||
               trimmed.contains("interface " + symbol) ||
               trimmed.contains("def " + symbol) ||
               trimmed.contains("function " + symbol) ||
               trimmed.matches(".*\\b(?:public|private|protected)?\\s*(?:static\\s+)?(?:final\\s+)?\\w+\\s+" + Pattern.quote(symbol) + "\\s*[=(;].*");
    }

    private String extractSymbolName(String line, String query) {
        Pattern pattern = Pattern.compile("\\b(\\w*" + Pattern.quote(query) + "\\w*)\\b");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return query;
    }

    private String detectSymbolKind(String line) {
        if (line.contains("class ")) return "class";
        if (line.contains("interface ")) return "interface";
        if (line.contains("def ") || line.contains("function ")) return "function";
        if (line.contains("const ") || line.contains("let ") || line.contains("var ")) return "variable";
        return "unknown";
    }

    private String extractSignature(String line, String symbol) {
        int index = line.indexOf(symbol);
        if (index == -1) return "";
        String rest = line.substring(index);
        int parenIndex = rest.indexOf('(');
        if (parenIndex == -1) return rest.trim();
        int endParenIndex = rest.indexOf(')');
        if (endParenIndex == -1) return rest.trim();
        return rest.substring(0, endParenIndex + 1).trim();
    }

    // ====== 格式化方法 ======

    private String formatSymbols(List<Map<String, Object>> symbols, String operation, String path) {
        if (symbols.isEmpty()) {
            return "未找到符号";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("在 ").append(path).append(" 中找到 ").append(symbols.size()).append(" 个符号:\n\n");

        for (Map<String, Object> symbol : symbols) {
            sb.append(String.format("  %s (%s) - 行 %d\n",
                symbol.get("name"), symbol.get("kind"), symbol.get("line")));
        }

        return sb.toString();
    }

    private String formatDefinitions(List<Map<String, Object>> definitions, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(definitions.size()).append(" 个定义:\n\n");

        for (Map<String, Object> def : definitions) {
            sb.append(String.format("  行 %d: %s\n", def.get("line"), def.get("preview")));
        }

        return sb.toString();
    }

    private String formatReferences(List<Map<String, Object>> references, String symbol) {
        if (references.isEmpty()) {
            return "未找到符号 '" + symbol + "' 的引用";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(references.size()).append(" 个引用:\n\n");

        // 按文件分组
        Map<String, List<Map<String, Object>>> byFile = references.stream()
            .collect(Collectors.groupingBy(r -> (String) r.get("path")));

        for (Map.Entry<String, List<Map<String, Object>>> entry : byFile.entrySet()) {
            sb.append(entry.getKey()).append(":\n");
            for (Map<String, Object> ref : entry.getValue()) {
                sb.append(String.format("  行 %d: %s\n", ref.get("line"), ref.get("preview")));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String formatHover(Map<String, Object> hoverInfo) {
        if (hoverInfo.isEmpty()) {
            return "未找到符号信息";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("符号: ").append(hoverInfo.get("name")).append("\n");
        sb.append("行: ").append(hoverInfo.get("line")).append("\n");
        if (hoverInfo.containsKey("signature")) {
            sb.append("签名: ").append(hoverInfo.get("signature")).append("\n");
        }
        sb.append("预览: ").append(hoverInfo.get("preview"));

        return sb.toString();
    }
}
