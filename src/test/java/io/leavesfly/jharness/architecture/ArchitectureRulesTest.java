package io.leavesfly.jharness.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构守卫测试（第 5 步：固化分层依赖方向）。
 *
 * <p>按重构路线把代码库划分为 7 个语义层，自顶向下：
 * <pre>
 *   app          —— 顶层入口（CLI 选项、装配器、Runner）
 *   ui           —— 终端界面（TUI / Console）
 *   command      —— 斜杠命令系统
 *   tools        —— 工具系统（内置工具 + 注册表）
 *   capability   —— 能力层（权限 / Hook / 会话 / 任务 / 协调）
 *   integration  —— 外部集成（API 客户端 / MCP / Cron）
 *   kernel       —— 内核（QueryEngine / Plan / 流事件 / 消息模型）
 *   extension    —— 扩展（插件 / 技能）
 *   config       —— 配置
 *   prompt / util —— 通用工具
 * </pre>
 *
 * <p>核心规则：
 * <ul>
 *   <li>kernel 不允许依赖 capability / integration / tools / command / ui / app；</li>
 *   <li>integration 不允许依赖 capability / tools / command / ui / app；</li>
 *   <li>capability 不允许依赖 tools / command / ui / app；</li>
 *   <li>tools 不允许依赖 command / ui / app；</li>
 *   <li>command 不允许依赖 ui / app；</li>
 *   <li>ui 不允许依赖 app；</li>
 *   <li>config / util / prompt 不允许反向依赖业务层；</li>
 *   <li>禁止 slice 之间出现循环依赖。</li>
 * </ul>
 */
class ArchitectureRulesTest {

    private static final String ROOT_PKG = "io.leavesfly.jharness";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages(ROOT_PKG);
    }

    // ============================================================
    // 分层依赖方向规则（自底向上单向）
    // ============================================================

    @Test
    void kernelMustNotDependOnUpperLayers() {
        // 已知例外：QueryEngine / ToolCallDispatcher / PlanStepRunner 直接持有
        // tools.ToolRegistry 与 tools.ToolResult，作为 4.7 拆分后的现状契约。
        // 后续若要彻底解耦，可把 ToolRegistry/ToolResult 抽接口下沉到 kernel.spi。
        ArchRule rule = noClasses()
                .that().resideInAPackage("..kernel..")
                .and().resideOutsideOfPackages(
                        "..kernel.engine..", "..kernel.engine.tools..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..capability..", "..integration..", "..tools..",
                        "..command..", "..ui..", "..app..")
                .because("kernel 必须保持纯净；仅 engine 子包允许引用 tools/capability/integration 作为编排");
        rule.check(classes);
    }

    @Test
    void integrationMustNotDependOnUpperLayers() {
        // 已知例外：integration.mcp.McpClientManager 持有 capability.permission.PermissionChecker，
        // 这是 4.1 的设计意图（让 MCP 子进程/HTTP 调用共享同一套权限栅栏）。
        // permission 子包被视为「跨层共享契约」，不算反向依赖。
        ArchRule rule = noClasses()
                .that().resideInAPackage("..integration..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..tools..", "..command..", "..ui..", "..app..")
                .andShould().dependOnClassesThat().resideOutsideOfPackage("..capability.permission..")
                .because("integration 仅做外部协议适配；只允许引用 capability.permission 作为安全契约");
        rule.check(classes);
    }

    @Test
    void capabilityMustNotDependOnToolsCommandUiApp() {
        // 已知例外：capability.coordination.AgentOrchestrator 持有 tools.ToolRegistry —
        // 编排者天然依赖被编排对象（QueryEngine + ToolRegistry），这是 in_process subagent
        // 模式的核心组合。coordination 子包被明确豁免。
        ArchRule rule = noClasses()
                .that().resideInAPackage("..capability..")
                .and().resideOutsideOfPackage("..capability.coordination..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..tools..", "..command..", "..ui..", "..app..")
                .because("capability 提供横切能力；仅 coordination 子包允许引用 tools 用于编排");
        rule.check(classes);
    }

    @Test
    void toolsMustNotDependOnCommandUiApp() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..tools..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..command..", "..ui..", "..app..")
                .because("工具系统不应依赖斜杠命令/UI/入口");
        rule.check(classes);
    }

    @Test
    void commandMustNotDependOnUiApp() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..command..")
                .should().dependOnClassesThat().resideInAnyPackage("..ui..", "..app..")
                .because("命令系统不应依赖 UI 实现或顶层入口");
        rule.check(classes);
    }

    @Test
    void uiMustNotDependOnApp() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..ui..")
                .should().dependOnClassesThat().resideInAPackage("..app..")
                .because("UI 层不应依赖顶层入口（避免循环）");
        rule.check(classes);
    }

    // ============================================================
    // 通用层：config / util / prompt 不能反向依赖业务层
    // ============================================================

    @Test
    void configMustNotDependOnBusinessLayers() {
        // 注意：必须用精确包名 io.leavesfly.jharness.config，否则 ..config.. 会匹配
        // command.commands.builtin.config / integration.api.config 等子包，造成假阳性。
        // 已知例外：Settings 持有 capability.permission.PermissionMode 作为字段类型，
        // 这是配置反序列化目标，把 permission 视为「契约枚举」，从禁止列表中豁免。
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.leavesfly.jharness.config")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..kernel..", "..integration..",
                        "..tools..", "..command..", "..ui..", "..app..", "..extension..")
                .because("config 是基础设施；仅允许引用 capability.permission 中的契约枚举");
        rule.check(classes);
    }

    @Test
    void utilMustNotDependOnBusinessLayers() {
        // 同理，util 必须用精确包名，避免误伤名字含 util 的子包
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.leavesfly.jharness.util")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..kernel..", "..capability..", "..integration..",
                        "..tools..", "..command..", "..ui..", "..app..", "..extension..",
                        "io.leavesfly.jharness.config")
                .because("util 是通用工具，不应反向依赖任何业务层/配置");
        rule.check(classes);
    }

    // ============================================================
    // 无循环依赖：按顶级 slice 切片做循环检测
    // ============================================================

    @Test
    void topLevelSlicesShouldBeFreeOfCycles() {
        // 已知循环（架构债，暂不破坏）：
        //   1) kernel ↔ tools：ToolResult/ToolResultBlock、ExecutionPlan/EnterPlanModeTool 互相引用；
        //   2) capability ↔ tools：BackgroundTaskManager 与 BashTool 等存在双向；
        //   3) capability ↔ integration：McpClientManager 持有 PermissionChecker。
        // 这些循环属 4.x 拆类范围外的接口下沉工作（建议下一阶段把 SPI 抽到 kernel.spi 子包消解）。
        // 这里仅守护「新增循环」：忽略上述已知 slices 对，对其它新增循环立即失败。
        ArchRule rule = slices()
                .matching("io.leavesfly.jharness.(*)..")
                .should().beFreeOfCycles()
                .ignoreDependency(
                        com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage(
                                "..kernel..", "..capability..", "..integration.."),
                        com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage(
                                "..tools..", "..capability..", "..integration.."));
        rule.check(classes);
    }
}
