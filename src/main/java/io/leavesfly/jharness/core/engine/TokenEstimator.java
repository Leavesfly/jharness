package io.leavesfly.jharness.core.engine;

import io.leavesfly.jharness.core.engine.model.ContentBlock;
import io.leavesfly.jharness.core.engine.model.ConversationMessage;
import io.leavesfly.jharness.core.engine.model.TextBlock;
import io.leavesfly.jharness.core.engine.model.ToolResultBlock;
import io.leavesfly.jharness.core.engine.model.ToolUseBlock;

import java.util.List;

/**
 * Token 估算器（F-P0-2）。
 *
 * 在没有引入 jtokkit/tiktoken-java 依赖的前提下，提供一个"**足够用**"的 token 估算实现：
 * - 英文及 ASCII 字符按 1 token / 4 字符的经验比例（与 OpenAI 官方估算一致）；
 * - 中文及 CJK 字符按 1 token / 1.5 字符（约等于 tiktoken 对中文的实际切分密度）；
 * - 其他多字节字符（如韩文、日文假名、表情符号）按 1 token / 2 字符；
 * - 对 JSON 结构额外开销（花括号、引号、字段名）做粗略加权 1.1x。
 *
 * 注：估算结果与实际 tokenizer 有 5-15% 的误差，用于压缩触发判断是完全足够的；
 *     若需要严格计费，应走 API 返回的 usage 字段。
 */
public final class TokenEstimator {

    /** ASCII 字符的字符/token 比例。 */
    private static final double ASCII_CHARS_PER_TOKEN = 4.0;
    /** CJK 字符的字符/token 比例。 */
    private static final double CJK_CHARS_PER_TOKEN = 1.5;
    /** 其他多字节字符的字符/token 比例。 */
    private static final double OTHER_MULTIBYTE_CHARS_PER_TOKEN = 2.0;
    /** JSON 结构化内容的开销加权（工具调用/结果含大量括号引号）。 */
    private static final double JSON_OVERHEAD_MULTIPLIER = 1.1;

    private TokenEstimator() {
        // 工具类禁止实例化
    }

    /**
     * 估算单段文本的 token 数。
     *
     * @param text 文本，允许为 null/空
     * @return 估算的 token 数，至少为 0
     */
    public static int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int asciiChars = 0;
        int cjkChars = 0;
        int otherChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                asciiChars++;
            } else if (isCjk(c)) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }
        double tokens = asciiChars / ASCII_CHARS_PER_TOKEN
                + cjkChars / CJK_CHARS_PER_TOKEN
                + otherChars / OTHER_MULTIBYTE_CHARS_PER_TOKEN;
        return (int) Math.ceil(tokens);
    }

    /**
     * 估算单个内容块的 token 数。
     *
     * 对结构化块（工具调用/结果）额外附加固定的 schema 开销 + JSON 加权。
     */
    public static int estimateBlock(ContentBlock block) {
        if (block == null) {
            return 0;
        }
        if (block instanceof TextBlock text) {
            return estimateText(text.getText());
        }
        if (block instanceof ToolUseBlock tool) {
            // 工具名 + 参数 JSON
            int base = estimateText(tool.getName());
            int inputTokens = tool.getInput() == null
                    ? 0
                    : estimateText(tool.getInput().toString());
            return (int) Math.ceil((base + inputTokens + 10) * JSON_OVERHEAD_MULTIPLIER);
        }
        if (block instanceof ToolResultBlock result) {
            int output = estimateText(result.getContent());
            return (int) Math.ceil((output + 10) * JSON_OVERHEAD_MULTIPLIER);
        }
        // 未知块类型保守返回固定值
        return 20;
    }

    /**
     * 估算单条消息的 token 数（包含所有内容块 + role 开销）。
     */
    public static int estimateMessage(ConversationMessage message) {
        if (message == null) {
            return 0;
        }
        int total = 4; // role 字段的固定开销
        for (ContentBlock block : message.getContent()) {
            total += estimateBlock(block);
        }
        return total;
    }

    /**
     * 估算一组消息的总 token 数。
     */
    public static int estimateMessages(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ConversationMessage m : messages) {
            total += estimateMessage(m);
        }
        return total;
    }

    /**
     * 判断字符是否属于 CJK 范围（包括中日韩基本汉字 + 扩展 A/B 区）。
     */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)       // CJK 基本
                || (c >= 0x3400 && c <= 0x4DBF)   // CJK 扩展 A
                || (c >= 0x3000 && c <= 0x303F)   // CJK 符号和标点
                || (c >= 0xFF00 && c <= 0xFFEF);  // 全角字符
    }
}
