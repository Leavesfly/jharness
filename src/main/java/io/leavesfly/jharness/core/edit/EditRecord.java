package io.leavesfly.jharness.core.edit;

import java.time.Instant;

/**
 * 单次文件编辑的历史记录（F-P0-4）。
 *
 * 保存文件编辑前的**完整**原始内容快照，用于支撑撤销操作（undo_edit 工具）。
 * 快照采用最简直白的"全文备份"策略，避免实现增量 diff 带来的额外复杂度；
 * 在常见单文件编辑场景下，内存占用完全可接受。
 */
public class EditRecord {
    /** 递增 ID（由 EditHistoryManager 分配）。 */
    private final long id;
    /** 文件绝对路径（字符串形式便于序列化）。 */
    private final String filePath;
    /** 编辑前的原始内容；若是新建文件则为 null。 */
    private final String originalContent;
    /** 编辑后的新内容。 */
    private final String newContent;
    /** 触发编辑的工具名（edit_file / write_file / multi_edit 等）。 */
    private final String toolName;
    /** 时间戳。 */
    private final Instant timestamp;
    /** 是否已被撤销，撤销后不可再次 undo。 */
    private volatile boolean undone;

    public EditRecord(long id, String filePath, String originalContent, String newContent,
                      String toolName, Instant timestamp) {
        this.id = id;
        this.filePath = filePath;
        this.originalContent = originalContent;
        this.newContent = newContent;
        this.toolName = toolName;
        this.timestamp = timestamp;
    }

    public long getId() { return id; }
    public String getFilePath() { return filePath; }
    public String getOriginalContent() { return originalContent; }
    public String getNewContent() { return newContent; }
    public String getToolName() { return toolName; }
    public Instant getTimestamp() { return timestamp; }
    public boolean isUndone() { return undone; }
    public void markUndone() { this.undone = true; }

    /** 是否为"新建文件"记录（即编辑前不存在此文件）。 */
    public boolean isCreation() {
        return originalContent == null;
    }
}
