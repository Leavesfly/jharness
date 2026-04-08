package io.leavesfly.jharness.core.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 文本内容块
 *
 * 表示纯文本消息内容。
 */
public class TextBlock extends ContentBlock {
    private final String text;

    /**
     * 构造文本块
     *
     * @param text 文本内容
     */
    @JsonCreator
    public TextBlock(@JsonProperty("text") String text) {
        this.text = text;
    }

    @Override
    public String getType() {
        return "text";
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return "TextBlock{text='" + text + "'}";
    }
}
