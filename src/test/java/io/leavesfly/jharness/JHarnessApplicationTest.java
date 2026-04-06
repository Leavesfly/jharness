package io.leavesfly.jharness;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * JHarness 应用程序基础测试
 */
class JHarnessApplicationTest {

    @Test
    void testApplicationInstantiation() {
        // 验证应用程序类可以正常实例化
        JHarnessApplication app = new JHarnessApplication();
        assertNotNull(app);
    }

    @Test
    void testPackageName() {
        // 验证包名正确
        String packageName = JHarnessApplication.class.getPackage().getName();
        assertEquals("io.leavesfly.jharness", packageName);
    }
}
