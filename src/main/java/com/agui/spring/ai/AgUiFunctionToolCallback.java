package com.agui.spring.ai;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class AgUiFunctionToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(AgUiFunctionToolCallback.class);

    private final ToolCallback toolCallback;

    private final Consumer<AgUiToolCallbackParams> callback;

    public AgUiFunctionToolCallback(final ToolCallback toolCallback, final Consumer<AgUiToolCallbackParams> callback) {
        this.toolCallback = toolCallback;

        this.callback = callback;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolCallback.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    public String call(String toolInput, @Nullable ToolContext toolContext) {
        log.info("[AgUiFunctionToolCallback] Executing tool: {} with input: {}",
                toolCallback.getToolDefinition().name(), toolInput);
        var result = this.toolCallback.call(toolInput, toolContext);
        log.info("[AgUiFunctionToolCallback] Tool {} returned result ({} chars)",
                toolCallback.getToolDefinition().name(), result != null ? result.length() : 0);

        this.callback.accept(new AgUiToolCallbackParams(result, toolInput));
        log.info("[AgUiFunctionToolCallback] AG-UI events emitted for tool: {}",
                toolCallback.getToolDefinition().name());

        return result;
    }

}