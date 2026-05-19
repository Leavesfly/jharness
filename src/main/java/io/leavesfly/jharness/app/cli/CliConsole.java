package io.leavesfly.jharness.app.cli;

import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.kernel.engine.model.MessageRole;
import io.leavesfly.jharness.kernel.engine.model.TextBlock;
import io.leavesfly.jharness.kernel.engine.stream.AssistantTextDelta;
import io.leavesfly.jharness.kernel.engine.stream.AssistantTurnComplete;
import io.leavesfly.jharness.kernel.engine.stream.StreamEvent;
import io.leavesfly.jharness.kernel.engine.stream.ToolExecutionCompleted;
import io.leavesfly.jharness.kernel.engine.stream.ToolExecutionStarted;
import io.leavesfly.jharness.kernel.engine.stream.UsageReport;
import io.leavesfly.jharness.util.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CLI 控制台/输出工具集（4.8 拆分自 JHarnessApplication）。
 *
 * 提供：流式事件渲染（text / stream-json / json）、最终 JSON、成本摘要、fatal 错误展示。
 */
public final class CliConsole {

    private static final Logger logger = LoggerFactory.getLogger(CliConsole.class);

    private CliConsole() {}

    /**
     * 根据 --output-format 返回对应的事件消费者。
     */
    public static Consumer<StreamEvent> eventConsumerFor(String outputFormat) {
        if (outputFormat == null) return CliConsole::printConsole;
        return switch (outputFormat.toLowerCase()) {
            case "stream-json" -> CliConsole::printJsonLine;
            case "json" -> event -> { /* 静默，由 emitFinalJson 统一输出 */ };
            default -> CliConsole::printConsole;
        };
    }

    /** 控制台模式下处理流式事件。 */
    public static void printConsole(StreamEvent event) {
        if (event instanceof AssistantTextDelta textDelta) {
            System.out.print(textDelta.getText());
            System.out.flush();
        } else if (event instanceof ToolExecutionStarted toolStart) {
            System.out.println("\n🔧 执行工具: " + toolStart.getToolName());
        } else if (event instanceof ToolExecutionCompleted toolDone) {
            String prefix = toolDone.isError() ? "❌ 工具失败: " : "✅ 工具完成: ";
            System.out.println(prefix + toolDone.getToolName());
        } else if (event instanceof AssistantTurnComplete turn) {
            String msg = turn.getMessage();
            if (msg != null && !msg.isBlank()) {
                System.out.println();
                System.out.println(msg);
            } else {
                System.out.println();
            }
        } else if (event instanceof UsageReport report) {
            logger.debug("usage: {}", report);
        }
    }

    /** 把单个流式事件以 JSON Line 形式输出到 stdout。 */
    public static void printJsonLine(StreamEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", event.getClass().getSimpleName());
            if (event instanceof AssistantTextDelta d) {
                payload.put("text", d.getText());
            } else if (event instanceof ToolExecutionStarted s) {
                payload.put("tool", s.getToolName());
            } else if (event instanceof ToolExecutionCompleted c) {
                payload.put("tool", c.getToolName());
                payload.put("error", c.isError());
                payload.put("result", c.getResult());
            } else if (event instanceof AssistantTurnComplete t) {
                payload.put("message", t.getMessage());
            } else if (event instanceof UsageReport r) {
                payload.put("report", r.toString());
            }
            System.out.println(JacksonUtils.MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            logger.debug("stream-json 序列化失败（忽略）", e);
        }
    }

    /** --output-format=json 模式下输出最终结构化摘要。 */
    public static void emitFinalJson(QueryEngine queryEngine) {
        try {
            var tracker = queryEngine.getCostTracker();
            String answer = "";
            for (var m : queryEngine.getMessages()) {
                if (m.getRole() == MessageRole.ASSISTANT) {
                    StringBuilder sb = new StringBuilder();
                    for (var b : m.getContent()) {
                        if (b instanceof TextBlock t) {
                            sb.append(t.getText());
                        }
                    }
                    answer = sb.toString();
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "final");
            payload.put("output", answer);
            payload.put("requests", tracker.getRequestCount());
            payload.put("input_tokens", tracker.getTotalInputTokens());
            payload.put("output_tokens", tracker.getTotalOutputTokens());
            payload.put("session_cost_usd", tracker.getSessionCostUsd().doubleValue());
            System.out.println(JacksonUtils.MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            logger.debug("final JSON 序列化失败（忽略）", e);
        }
    }

    /** 打印本次会话的成本摘要。 */
    public static void printCostSummary(QueryEngine queryEngine) {
        try {
            var tracker = queryEngine.getCostTracker();
            if (tracker.getRequestCount() == 0) return;
            System.out.printf(
                    "📊 用量摘要: 请求=%d, 输入=%d tok, 输出=%d tok, 会话=$%.4f, 今日=$%.4f%n",
                    tracker.getRequestCount(),
                    tracker.getTotalInputTokens(),
                    tracker.getTotalOutputTokens(),
                    tracker.getSessionCostUsd().doubleValue(),
                    tracker.getDailyCostUsd().doubleValue());
        } catch (Exception e) {
            logger.debug("打印成本摘要失败（忽略）", e);
        }
    }

    /** 统一 fatal 错误呈现。 */
    public static int reportFatal(String prefix, Throwable e, boolean debug) {
        logger.error(prefix, e);
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String reason = (root.getMessage() == null || root.getMessage().isBlank())
                ? root.getClass().getSimpleName()
                : root.getMessage();
        System.err.println(prefix + ": " + reason);
        if (debug && root != e) {
            System.err.println("  根因类型: " + root.getClass().getName());
        }
        return 1;
    }
}
