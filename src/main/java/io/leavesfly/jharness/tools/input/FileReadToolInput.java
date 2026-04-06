package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * 文件读取工具输入
 */
public class FileReadToolInput {
    @NotBlank(message = "file_path 不能为空")
    private String file_path;

    private Integer offset;
    private Integer limit;

    public String getFile_path() {
        return file_path;
    }

    public void setFile_path(String file_path) {
        this.file_path = file_path;
    }

    public Integer getOffset() {
        return offset;
    }

    public void setOffset(Integer offset) {
        this.offset = offset;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
