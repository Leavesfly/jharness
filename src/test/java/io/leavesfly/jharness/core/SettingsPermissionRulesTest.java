package io.leavesfly.jharness.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 FP-1：Settings 新增 pathRules / deniedCommandPatterns 字段的 getter/setter 行为。
 * 反序列化路径通过 Settings.load() 测试，但需要访问用户主目录，这里只做单元级字段测试。
 */
class SettingsPermissionRulesTest {

    @Test
    void pathRulesDefaultsToEmptyListAndIsSettable() {
        Settings s = new Settings();
        assertNotNull(s.getPathRules());
        assertTrue(s.getPathRules().isEmpty());

        s.setPathRules(List.of(
                Map.of("pattern", "src/**", "allow", true),
                Map.of("pattern", "/etc/**", "allow", false)
        ));
        assertEquals(2, s.getPathRules().size());
        assertEquals("src/**", s.getPathRules().get(0).get("pattern"));
        assertEquals(Boolean.TRUE, s.getPathRules().get(0).get("allow"));
    }

    @Test
    void pathRulesNullFallsBackToEmptyList() {
        Settings s = new Settings();
        s.setPathRules(null);
        assertNotNull(s.getPathRules());
        assertTrue(s.getPathRules().isEmpty());
    }

    @Test
    void deniedCommandPatternsDefaultsToEmptyAndIsSettable() {
        Settings s = new Settings();
        assertNotNull(s.getDeniedCommandPatterns());
        assertTrue(s.getDeniedCommandPatterns().isEmpty());

        s.setDeniedCommandPatterns(List.of("rm -rf *", "sudo *"));
        assertEquals(2, s.getDeniedCommandPatterns().size());
        assertTrue(s.getDeniedCommandPatterns().contains("sudo *"));
    }

    @Test
    void deniedCommandPatternsNullFallsBackToEmpty() {
        Settings s = new Settings();
        s.setDeniedCommandPatterns(null);
        assertNotNull(s.getDeniedCommandPatterns());
        assertTrue(s.getDeniedCommandPatterns().isEmpty());
    }
}
