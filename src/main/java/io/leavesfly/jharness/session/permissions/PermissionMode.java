package io.leavesfly.jharness.session.permissions;

/**
 * 权限模式枚举
 *
 * 定义三种权限模式：
 * - DEFAULT: 默认模式，写入/执行前询问
 * - FULL_AUTO: 全自动模式，允许所有操作
 * - PLAN: 计划模式，阻止所有写入操作
 */
public enum PermissionMode {
    /** 默认模式：写入/执行前询问 */
    DEFAULT,
    /** 全自动模式：允许所有操作 */
    FULL_AUTO,
    /** 计划模式：阻止所有写入操作 */
    PLAN
}
