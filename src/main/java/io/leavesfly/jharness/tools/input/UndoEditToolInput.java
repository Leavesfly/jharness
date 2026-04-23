package io.leavesfly.jharness.tools.input;

/**
 * undo_edit 工具输入。
 *
 * - 若 edit_id 为 null，则撤销最近一次未撤销的编辑；
 * - 否则撤销指定 ID 的编辑。
 */
public class UndoEditToolInput {
    private Long edit_id;

    public Long getEdit_id() {
        return edit_id;
    }

    public void setEdit_id(Long edit_id) {
        this.edit_id = edit_id;
    }
}
