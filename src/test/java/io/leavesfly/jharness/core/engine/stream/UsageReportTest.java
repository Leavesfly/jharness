package io.leavesfly.jharness.core.engine.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UsageReport} 事件单元测试。
 *
 * <p>该事件是本轮新增的观测点，确保字段透传和 eventType 稳定，避免后续反序列化或
 * UI 侧 switch 依赖被破坏。</p>
 */
class UsageReportTest {

    @Test
    void eventType_isUsageReport() {
        UsageReport report = new UsageReport(0, 0, 0, 0d, 0d, 0d);
        assertEquals("usage_report", report.getEventType());
    }

    @Test
    void gettersReturnConstructorValues() {
        UsageReport r = new UsageReport(100, 200, 500, 0.01d, 0.25d, 10d);

        assertEquals(100L, r.getInputTokens());
        assertEquals(200L, r.getOutputTokens());
        assertEquals(500L, r.getTotalTokens());
        assertEquals(0.01d, r.getSessionCostUsd(), 1e-9);
        assertEquals(0.25d, r.getDailyCostUsd(), 1e-9);
        assertEquals(10d, r.getDailyBudgetUsd(), 1e-9);
    }

    @Test
    void toString_containsKeyNumbers() {
        String s = new UsageReport(10, 20, 30, 0.1, 0.2, 1.0).toString();
        assertTrue(s.contains("in=10"));
        assertTrue(s.contains("out=20"));
        assertTrue(s.contains("total=30"));
        assertTrue(s.contains("$0.1000"));
    }
}
