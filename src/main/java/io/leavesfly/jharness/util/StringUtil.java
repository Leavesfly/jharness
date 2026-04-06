package io.leavesfly.jharness.util;

/**
 * 字符串工具类
 */
public class StringUtil {
    
    private StringUtil() {}
    
    /**
     * 截断字符串到指定长度
     *
     * @param str       输入字符串，null 返回 null
     * @param maxLength 最大长度，必须大于 0
     * @return 截断后的字符串
     */
    public static String truncate(String str, int maxLength) {
        if (str == null) return null;
        if (maxLength <= 0) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength);
    }
    
    /**
     * 安全地获取字符串的最后 N 个字符
     *
     * @param str      输入字符串，null 返回空字符串
     * @param maxChars 最大字符数，必须大于 0
     * @return 截取后的字符串
     */
    public static String tail(String str, int maxChars) {
        if (str == null || str.isEmpty()) return "";
        if (maxChars <= 0) return "";
        if (str.length() <= maxChars) return str;
        return str.substring(str.length() - maxChars);
    }
    
    /**
     * 检查字符串是否为空或空白
     */
    public static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
    
    /**
     * 安全地去除 trailing slashes
     */
    public static String stripTrailingSlashes(String str) {
        if (str == null) return null;
        return str.replaceAll("/+$", "");
    }
}
