package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * 文件编辑工具输入
 */
public class FileEditToolInput {
    @NotBlank(message = "file_path 不能为空")
    private String file_path;

    @NotBlank(message = "old_string 不能为空")
    private String old_string;

    @NotBlank(message = "new_string 不能为空")
    private String new_string;

    public String getFile_path() {
        return file_path;
    }

    public void setFile_path(String file_path) {
        this.file_path = file_path;
    }

    public String getOld_string() {
        return old_string;
    }

    public void setOld_string(String old_string) {
        this.old_string = old_string;
    }

    public String getNew_string() {
        return new_string;
    }

    public void setNew_string(String new_string) {
        this.new_string = new_string;
    }
}
