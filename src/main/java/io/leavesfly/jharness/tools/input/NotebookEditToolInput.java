package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;

/**
 * Notebook 编辑工具输入
 */
public class NotebookEditToolInput {
    @NotBlank(message = "path 不能为空")
    private String path;

    @Min(value = 0, message = "cell_index 必须 >= 0")
    private int cell_index;

    @NotBlank(message = "new_source 不能为空")
    private String new_source;

    private String cell_type = "code";
    private String mode = "replace";
    private boolean create_if_missing = true;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getCell_index() {
        return cell_index;
    }

    public void setCell_index(int cell_index) {
        this.cell_index = cell_index;
    }

    public String getNew_source() {
        return new_source;
    }

    public void setNew_source(String new_source) {
        this.new_source = new_source;
    }

    public String getCell_type() {
        return cell_type;
    }

    public void setCell_type(String cell_type) {
        this.cell_type = cell_type;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isCreate_if_missing() {
        return create_if_missing;
    }

    public void setCreate_if_missing(boolean create_if_missing) {
        this.create_if_missing = create_if_missing;
    }
}
