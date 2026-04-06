package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.input.ToolSearchToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 工具搜索工具
 * 
 * 按名称或描述搜索可用的工具。
 */
public class ToolSearchTool extends BaseTool<ToolSearchToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(ToolSearchTool.class);
    private final ToolRegistry toolRegistry;

    public ToolSearchTool(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String getName() {
        return "search_tools";
    }

    @Override
    public String getDescription() {
        return "按名称或描述搜索可用的工具";
    }

    @Override
    public Class<ToolSearchToolInput> getInputClass() {
        return ToolSearchToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolSearchToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String query = input.getQuery().toLowerCase();
                StringBuilder sb = new StringBuilder();
                int matchCount = 0;

                for (BaseTool<?> tool : toolRegistry.getAllTools()) {
                    boolean matches = tool.getName().toLowerCase().contains(query) ||
                                     tool.getDescription().toLowerCase().contains(query);
                    
                    if (matches) {
                        matchCount++;
                        sb.append("- ").append(tool.getName()).append("\n");
                        sb.append("  ").append(tool.getDescription()).append("\n\n");
                    }
                }

                if (matchCount == 0) {
                    return ToolResult.success("未找到匹配的工具: " + input.getQuery());
                }

                sb.insert(0, "找到 " + matchCount + " 个匹配工具:\n\n");
                return ToolResult.success(sb.toString().trim());
            } catch (Exception e) {
                logger.error("搜索工具失败", e);
                return ToolResult.error("搜索工具失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(ToolSearchToolInput input) {
        return true;
    }
}
