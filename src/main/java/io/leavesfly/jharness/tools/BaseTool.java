package io.leavesfly.jharness.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 工具抽象基类
 *
 * 所有工具必须继承此类并实现 execute 方法。
 * 提供工具名称、描述、输入模型和只读判断等基础功能。
 *
 * @param <T> 工具输入类型
 */
public abstract class BaseTool<T> {
    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 获取工具名称
     *
     * @return 工具名称
     */
    public abstract String getName();

    /**
     * 获取工具描述
     *
     * @return 工具描述（用于 LLM 理解）
     */
    public abstract String getDescription();

    /**
     * 获取工具输入模型类
     *
     * @return 输入模型类
     */
    public abstract Class<T> getInputClass();

    /**
     * 执行工具
     *
     * @param input   工具输入
     * @param context 执行上下文
     * @return 工具执行结果的 CompletableFuture
     */
    public abstract CompletableFuture<ToolResult> execute(T input, ToolExecutionContext context);

    /**
     * 判断该工具是否为只读操作
     *
     * @param input 工具输入
     * @return 如果是只读操作返回 true
     */
    public boolean isReadOnly(T input) {
        return false;
    }

    /**
     * 生成工具的 JSON Schema（用于 API 调用）
     *
     * 基于 InputClass 的字段定义和 Jakarta Validation 注解反射生成完整的 JSON Schema。
     *
     * @return JSON Schema 映射
     */
    public java.util.Map<String, Object> toApiSchema() {
        java.util.Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");

        java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();
        java.util.List<String> required = new java.util.ArrayList<>();

        Class<T> inputClass = getInputClass();
        if (inputClass != null) {
            for (Field field : inputClass.getDeclaredFields()) {
                String fieldName = field.getName();
                java.util.Map<String, Object> fieldSchema = new java.util.LinkedHashMap<>();
                fieldSchema.put("type", mapJavaTypeToJsonType(field.getType()));

                // 对数组/List 类型添加 items 定义
                if (field.getType().isArray()) {
                    fieldSchema.put("items", java.util.Map.of("type",
                            mapJavaTypeToJsonType(field.getType().getComponentType())));
                } else if (List.class.isAssignableFrom(field.getType())) {
                    fieldSchema.put("items", java.util.Map.of("type", "object"));
                }

                properties.put(fieldName, fieldSchema);

                // 检查 Jakarta Validation 注解判断是否必填
                if (isRequiredField(field)) {
                    required.add(fieldName);
                }
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    /**
     * 判断字段是否为必填（通过 Jakarta Validation 注解）
     */
    private boolean isRequiredField(Field field) {
        for (java.lang.annotation.Annotation annotation : field.getAnnotations()) {
            String annotationName = annotation.annotationType().getSimpleName();
            if ("NotBlank".equals(annotationName)
                    || "NotNull".equals(annotationName)
                    || "NotEmpty".equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 Java 类型映射为 JSON Schema 类型
     */
    private String mapJavaTypeToJsonType(Class<?> type) {
        if (type == String.class) {
            return "string";
        } else if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class) {
            return "integer";
        } else if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) {
            return "number";
        } else if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        } else if (type.isArray() || List.class.isAssignableFrom(type)) {
            return "array";
        }
        return "object";
    }

    @Override
    public String toString() {
        return getName() + " - " + getDescription();
    }
}
