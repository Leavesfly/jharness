package io.leavesfly.jharness.kernel.spi;

import io.leavesfly.jharness.capability.permission.PermissionDecision;
import io.leavesfly.jharness.capability.permission.PermissionMode;

/**
 * 权限闸门 SPI（B 路线 P0-2 引入）。
 *
 * <p>把内核及 integration 层对权限检查器的依赖统一收敛到这个**最小接口**，
 * 让 {@code integration.mcp.McpClientManager}、{@code kernel.engine.tools.ToolCallDispatcher}
 * 等下层组件只依赖契约，不依赖 {@code capability.permission.PermissionChecker} 这个具体实现，
 * 从而消除「integration → capability」和「kernel → capability」的反向依赖。
 *
 * <p>{@link io.leavesfly.jharness.capability.permission.PermissionChecker} 是该接口的官方实现。
 *
 * <p>注意：{@link PermissionDecision} / {@link PermissionMode} 是值对象 / 枚举，
 * 作为契约的一部分留在 {@code capability.permission} 包中即可，不必下沉。
 */
public interface PermissionGate {

    /**
     * 评估一次工具调用是否允许执行。
     *
     * @param toolName 工具名称
     * @param readOnly 是否只读操作
     * @param filePath 涉及的文件路径（可为 null）
     * @param command  涉及的 shell 命令（可为 null）
     * @return 权限决策，禁止返回 null
     */
    PermissionDecision evaluate(String toolName, boolean readOnly, String filePath, String command);

    /** 当前生效的权限模式。 */
    PermissionMode getMode();

    /**
     * 设置当前权限模式。供 EnterPlanModeTool / ExitPlanModeTool 在运行期热切换模式，
     * 避免 Settings 与真实鉴权状态漂移。
     */
    void setMode(PermissionMode mode);
}
