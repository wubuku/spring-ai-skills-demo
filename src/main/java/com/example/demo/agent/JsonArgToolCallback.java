package com.example.demo.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * 自定义 ToolCallback 包装器，用于修复 Spring AI 1.1.2 的 JSON 参数反序列化问题。
 *
 * 问题：Spring AI 的 MethodToolCallback 在反序列化 JSON 参数时，
 * 即使使用了 -parameters 编译选项，也无法正确将 JSON 键映射到方法参数。
 *
 * 解决方案：手动解析 JSON 参数，然后通过反射调用目标方法。
 */
@Slf4j
public class JsonArgToolCallback implements ToolCallback {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ToolDefinition toolDefinition;
    private final Object targetObject;
    private final Method targetMethod;
    private final String[] parameterNames;

    public JsonArgToolCallback(ToolDefinition toolDefinition, Object targetObject, Method targetMethod, String... parameterNames) {
        this.toolDefinition = toolDefinition;
        this.targetObject = targetObject;
        this.targetMethod = targetMethod;
        this.parameterNames = parameterNames;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        try {
            log.debug("Executing AG-UI tool callback: tool={}", toolDefinition.name());

            // 解析 JSON 参数
            JsonNode rootNode = objectMapper.readTree(toolInput);

            // 提取参数值
            Object[] args = new Object[parameterNames.length];
            for (int i = 0; i < parameterNames.length; i++) {
                String paramName = parameterNames[i];
                JsonNode paramNode = rootNode.get(paramName);
                if (paramNode != null && !paramNode.isNull()) {
                    args[i] = paramNode.asText();
                } else {
                    args[i] = null;
                }
            }

            // 调用目标方法
            Object result = targetMethod.invoke(targetObject, args);
            return result != null ? result.toString() : "";

        } catch (Exception e) {
            log.warn("AG-UI tool callback failed: tool={}, error={}",
                toolDefinition.name(), e.getClass().getSimpleName());
            return "工具执行失败：工具参数或执行过程异常";
        }
    }
}
