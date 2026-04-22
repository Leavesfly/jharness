package io.leavesfly.jharness.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Jackson ObjectMapper 全局工具类
 *
 * 提供一个统一配置、线程安全的 ObjectMapper 单例，避免项目中重复创建
 * ObjectMapper 带来的性能开销和配置不一致问题。
 *
 * 安全与兼容性设定：
 * - 显式不启用默认多态（deactivateDefaultTyping），避免反序列化 RCE 风险；
 * - 注册 JSR-310（java.time）与 JDK8 模块，正确序列化 Instant/Optional 等；
 * - 忽略未知字段，保证新增字段不破坏旧版本反序列化；
 * - 时间字段以 ISO-8601 字符串而非时间戳输出，增强可读性；
 * - 序列化时忽略 null 字段，减小 JSON 体积。
 */
public final class JacksonUtils {

    /** 标准 ObjectMapper 单例（输出紧凑 JSON）。 */
    public static final ObjectMapper MAPPER = buildDefault();

    /** 带美化格式的 ObjectMapper 单例（用于需要人类可读的配置/会话文件）。 */
    public static final ObjectMapper PRETTY_MAPPER = buildDefault()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JacksonUtils() {
        // 工具类禁止实例化
    }

    private static ObjectMapper buildDefault() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(new Jdk8Module())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .deactivateDefaultTyping()
                .build();
    }
}
