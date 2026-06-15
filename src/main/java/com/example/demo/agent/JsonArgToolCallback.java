package com.example.demo.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            System.out.println("[JsonArgToolCallback] call() 被调用，toolInput=" + toolInput);

            // 解析 JSON 参数
            JsonNode rootNode = objectMapper.readTree(toolInput);

            // 提取参数值
            Object[] args = new Object[parameterNames.length];
            for (int i = 0; i < parameterNames.length; i++) {
                String paramName = parameterNames[i];
                JsonNode paramNode = rootNode.get(paramName);
                if (paramNode != null && !paramNode.isNull()) {
                    args[i] = paramNode.asText();
                    System.out.println("[JsonArgToolCallback] 参数 " + paramName + "=" + args[i]);
                } else {
                    args[i] = null;
                    System.out.println("[JsonArgToolCallback] 参数 " + paramName + " 为 null");
                }
            }

            // 调用目标方法
            Object result = targetMethod.invoke(targetObject, args);
            return result != null ? result.toString() : "";

        } catch (Exception e) {
            System.err.println("[JsonArgToolCallback] 执行失败: " + e.getMessage());
            e.printStackTrace();
            return "工具执行失败: " + e.getMessage();
        }
    }
}
