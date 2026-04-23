package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.core.edit.EditHistoryManager;
import io.leavesfly.jharness.core.edit.EditRecord;
import io.leavesfly.jharness.tools.input.UndoEditToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/**
 * 撤销编辑工具（F-P0-4）。
 *
 * 撤销由 {@link FileEditTool} / {@link FileWriteTool} 写入 {@link EditHistoryManager} 的最近一次编辑：
 * - 若记录的 {@code originalContent == null}，表示该编辑是"新建文件"，撤销时删除该文件；
 * - 否则将文件内容恢复为 {@code originalContent}；
 * - 撤销后记录被标记为 undone，无法重复撤销（避免 redo 语义歧义）。
 */
public class UndoEditTool extends BaseTool<UndoEditToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(UndoEditTool.class);

    @Override
    public String getName() {
        return "undo_edit";
    }

    @Override
    public String getDescription() {
        return "撤销上一次文件编辑（或指定 edit_id 的编辑），恢复文件到编辑前状态。";
    }

    @Override
    public Class<UndoEditToolInput> getInputClass() {
        return UndoEditToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(UndoEditToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            EditHistoryManager history = EditHistoryManager.getInstance();
            EditRecord record = input.getEdit_id() == null
                    ? history.peekLastActive()
                    : history.get(input.getEdit_id());

            if (record == null) {
                return ToolResult.error("未找到可撤销的编辑记录");
            }
            if (record.isUndone()) {
                return ToolResult.error("该编辑已被撤销，无法重复撤销 (id=" + record.getId() + ")");
            }

            try {
                Path filePath = Paths.get(record.getFilePath());

                // 安全校验：撤销前文件内容必须与 record.newContent 一致，
                // 否则说明该文件已被他人修改，盲目覆盖会丢失这些变更。
                if (record.isCreation()) {
                    // 新建记录：若文件已不存在，视为已处理成功；若内容与 newContent 不一致则拒绝
                    if (Files.exists(filePath)) {
                        String cur = Files.readString(filePath, StandardCharsets.UTF_8);
                        if (!cur.equals(record.getNewContent())) {
                            return ToolResult.error("撤销被拒绝：文件 " + filePath
                                    + " 自本次编辑后已被修改，删除将丢失这些变更，请手动处理。");
                        }
                        Files.delete(filePath);
                    }
                } else {
                    if (!Files.exists(filePath)) {
                        return ToolResult.error("撤销被拒绝：目标文件 " + filePath + " 已不存在");
                    }
                    String cur = Files.readString(filePath, StandardCharsets.UTF_8);
                    if (!cur.equals(record.getNewContent())) {
                        return ToolResult.error("撤销被拒绝：文件 " + filePath
                                + " 自本次编辑后已被修改，直接恢复将丢失这些变更，请手动处理。");
                    }
                    atomicWrite(filePath, record.getOriginalContent());
                }
                record.markUndone();
                logger.info("已撤销编辑 id={} file={} tool={}",
                        record.getId(), record.getFilePath(), record.getToolName());
                return ToolResult.success(String.format(
                        "已撤销编辑 id=%d, 文件=%s, 原工具=%s",
                        record.getId(), record.getFilePath(), record.getToolName()));
            } catch (Exception e) {
                logger.error("撤销编辑失败", e);
                return ToolResult.error("撤销编辑失败: " + e.getMessage());
            }
        });
    }

    /**
     * 原子写：先写临时文件再 ATOMIC_MOVE 替换目标。
     */
    private static void atomicWrite(Path target, String content) throws java.io.IOException {
        Path parent = target.getParent() != null ? target.getParent() : target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(parent, ".jh-undo-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
