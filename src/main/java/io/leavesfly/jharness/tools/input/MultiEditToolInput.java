package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * multi_edit 工具输入（F-P1-4）。
 *
 * 对同一文件按给定顺序应用多个 edit，全部成功才写回磁盘；
 * 任一 edit 失败则整体回滚，不对目标文件产生任何副作用。
 */
public class MultiEditToolInput {

    @NotBlank(message = "file_path 不能为空")
    private String file_path;

    @NotEmpty(message = "edits 不能为空")
    private List<Edit> edits;

    public String getFile_path() { return file_path; }
    public void setFile_path(String file_path) { this.file_path = file_path; }
    public List<Edit> getEdits() { return edits; }
    public void setEdits(List<Edit> edits) { this.edits = edits; }

    /**
     * 单个编辑项。
     * - old_string 必须在当前（中间态）文本中**唯一**出现，避免误替换；
     * - replace_all=true 时跳过唯一性校验，全文替换所有匹配。
     */
    public static class Edit {
        @NotBlank(message = "old_string 不能为空")
        private String old_string;

        @NotBlank(message = "new_string 不能为空")
        private String new_string;

        private boolean replace_all;

        public String getOld_string() { return old_string; }
        public void setOld_string(String old_string) { this.old_string = old_string; }
        public String getNew_string() { return new_string; }
        public void setNew_string(String new_string) { this.new_string = new_string; }
        public boolean isReplace_all() { return replace_all; }
        public void setReplace_all(boolean replace_all) { this.replace_all = replace_all; }
    }
}
