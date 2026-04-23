package io.leavesfly.jharness.core.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 图片内容块（F-P1-3）。
 *
 * 支持两种图片来源：
 * - base64：通过 {@code data} 字段传入 base64 编码的图片数据，{@code mediaType} 标注 MIME 类型；
 * - url：通过 {@code url} 字段传入可访问的图片 URL，模型侧通过 HTTP 拉取。
 *
 * 二者互斥，优先使用 url（节省 token），只有用户传入本地文件时才 base64 编码。
 *
 * 在 OpenAI 协议中对应 content 数组里的 {@code {"type":"image_url","image_url":{"url":"..."}} }。
 */
public class ImageBlock extends ContentBlock {

    /** base64 编码的图片数据，仅在无 url 时使用。 */
    private final String data;
    /** MIME 类型，如 "image/png"、"image/jpeg"。 */
    private final String mediaType;
    /** 图片 URL，与 data 互斥。 */
    private final String url;
    /** 可选的 detail 参数（OpenAI 支持 "auto"/"low"/"high"）。 */
    private final String detail;

    /**
     * 从 URL 创建图片块。
     */
    public static ImageBlock fromUrl(String url) {
        return new ImageBlock(null, null, url, "auto");
    }

    /**
     * 从 URL 创建图片块（指定 detail）。
     */
    public static ImageBlock fromUrl(String url, String detail) {
        return new ImageBlock(null, null, url, detail);
    }

    /**
     * 从 base64 数据创建图片块。
     */
    public static ImageBlock fromBase64(String data, String mediaType) {
        return new ImageBlock(data, mediaType, null, "auto");
    }

    @JsonCreator
    public ImageBlock(
            @JsonProperty("data") String data,
            @JsonProperty("media_type") String mediaType,
            @JsonProperty("url") String url,
            @JsonProperty("detail") String detail) {
        this.data = data;
        this.mediaType = mediaType;
        this.url = url;
        this.detail = detail != null ? detail : "auto";
    }

    @Override
    public String getType() {
        return "image";
    }

    public String getData() { return data; }
    public String getMediaType() { return mediaType; }
    public String getUrl() { return url; }
    public String getDetail() { return detail; }

    /**
     * 生成 OpenAI 格式的 image_url 值。
     * - 有 url 时直接返回 url；
     * - 有 base64 data 时返回 data URI：{@code data:<mediaType>;base64,<data>}。
     */
    public String toImageUrlValue() {
        if (url != null && !url.isBlank()) {
            return url;
        }
        if (data != null && mediaType != null) {
            return "data:" + mediaType + ";base64," + data;
        }
        return "";
    }

    @Override
    public String toString() {
        if (url != null) {
            return "ImageBlock{url='" + url + "'}";
        }
        return "ImageBlock{base64, mediaType='" + mediaType + "', length=" + (data != null ? data.length() : 0) + "}";
    }
}
