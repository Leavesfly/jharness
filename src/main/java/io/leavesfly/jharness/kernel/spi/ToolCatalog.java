package io.leavesfly.jharness.kernel.spi;

import io.leavesfly.jharness.tools.BaseTool;

import java.util.Collection;

/**
 * 工具目录 SPI（B 路线 P0-2 引入）。
 *
 * <p>把内核层（{@code kernel.engine.QueryEngine} / {@code ToolCallDispatcher}）和
 * 编排层（{@code capability.coordination.AgentOrchestrator}）对工具注册表的依赖
 * 收敛到这个**只读视图**接口，让它们不再直接依赖 {@code tools.ToolRegistry} 这个
 * 具体实现，从而消除「kernel → tools」和「capability → tools」的反向依赖。
 *
 * <p>{@link io.leavesfly.jharness.tools.ToolRegistry} 是该接口的官方实现。
 *
 * <p>注意：{@link BaseTool} 是 tools 包的核心抽象，作为契约一部分被 SPI 引用是
 * 「允许的方向」（kernel/capability → tools 通过 BaseTool 抽象），等价于面向接口编程。
 */
public interface ToolCatalog {

    /** 按名称查找工具，找不到返回 null。 */
    BaseTool<?> get(String name);

    /** 是否包含指定名称的工具。 */
    boolean has(String name);

    /** 当前可用的所有工具（只读视图）。 */
    Collection<BaseTool<?>> all();

    /** 工具总数。 */
    int size();
}
