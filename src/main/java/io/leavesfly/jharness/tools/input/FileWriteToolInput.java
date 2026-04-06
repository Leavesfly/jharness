package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * 文件写入工具输入
 */
public class FileWriteToolInput {
    @NotBlank(message = "file_path 不能为空")
    private String file_path;

    @NotBlank(message = "content 不能为空")
    private String content;

    public String getFile_path() {
        return file_path;
    }

    public void setFile_path(String file_path) {
        this.file_path = file_path;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
