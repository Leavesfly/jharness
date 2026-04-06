package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.input.BriefToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 消息摘要工具
 * 
 * 将文本截断到指定长度，用于生成摘要。
 */
public class BriefTool extends BaseTool<BriefToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(BriefTool.class);

    @Override
    public String getName() {
        return "brief";
    }

    @Override
    public String getDescription() {
        return "将文本截断到指定长度，用于生成简短摘要";
    }

    @Override
    public Class<BriefToolInput> getInputClass() {
        return BriefToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(BriefToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String text = input.getText();
                int maxLength = input.getMaxLength() != null ? input.getMaxLength() : 200;

                if (text == null || text.isEmpty()) {
                    return ToolResult.success("(空文本)");
                }

                if (text.length() <= maxLength) {
                    return ToolResult.success(text);
                }

                String brief = text.substring(0, maxLength) + "...(已截断，原文 " + text.length() + " 字符)";
                return ToolResult.success(brief);
            } catch (Exception e) {
                logger.error("摘要生成失败", e);
                return ToolResult.error("摘要生成失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(BriefToolInput input) {
        return true;
    }
}
