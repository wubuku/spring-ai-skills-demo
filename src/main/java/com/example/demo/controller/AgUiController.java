package com.example.demo.controller;

import com.agui.server.spring.AgUiParameters;
import com.agui.server.spring.AgUiService;
import com.agui.spring.ai.SpringAIAgent;
import com.example.demo.agent.SkillTools;
import com.example.demo.auth.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * AG-UI 协议控制器
 * 提供 SSE (Server-Sent Events) 端点，供 CopilotKit Runtime 调用
 *
 * JWT 透传：依赖 SecurityConfig 的 MODE_INHERITABLETHREADLOCAL 机制
 * 自动将 SecurityContext 传递到子线程（Spring AI 工具执行）
 */
@RestController
@RequestMapping("/api/agui")
@CrossOrigin(origins = "*")
public class AgUiController {

    private static final Logger log = LoggerFactory.getLogger(AgUiController.class);

    private final AgUiService agUiService;
    private final SpringAIAgent enterpriseAgent;
    private final SkillTools skillTools;

    public AgUiController(AgUiService agUiService, SpringAIAgent enterpriseAgent, SkillTools skillTools) {
        this.agUiService = agUiService;
        this.enterpriseAgent = enterpriseAgent;
        this.skillTools = skillTools;
    }

    /**
     * AG-UI 协议端点
     * CopilotKit Runtime (Node.js BFF) 会 POST 到这里
     * 返回 SSE 事件流
     *
     * @param agUiParameters AG-UI 请求参数（包含消息、工具、线程ID等）
     * @param authHeader Authorization 请求头（Bearer token）
     * @return SSE Emitter
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> run(
            @RequestBody AgUiParameters agUiParameters,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        log.info("收到 AG-UI 请求: threadId={}, runId={}, messages={}, authHeader={}",
                agUiParameters.getThreadId(),
                agUiParameters.getRunId(),
                agUiParameters.getMessages() != null ? agUiParameters.getMessages().size() : 0,
                authHeader != null ? (authHeader.substring(0, Math.min(20, authHeader.length())) + "...") : "null");

        // 提取 JWT Token 并设置到 SecurityContext（依赖 INHERITABLETHREADLOCAL 自动传递到子线程）
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String jwt = authHeader.substring(7);

            // 设置到 SecurityContextHolder，INHERITABLETHREADLOCAL 会自动传递到子线程
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    "user",  // principal
                    jwt,     // credentials - 存储原始 JWT
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("已设置 JWT 到 SecurityContext，依赖 INHERITABLETHREADLOCAL 机制传递到子线程");
        } else {
            log.warn("未收到 Authorization header! authHeader={}", authHeader);
        }

        // 通过 forwardedProps 传递 JWT（备用方案）
        Map<String, Object> toolContext = new HashMap<>();
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            toolContext.put("jwt", authHeader.substring(7));
        }
        agUiParameters.setForwardedProps(toolContext);

        skillTools.reset();

        // 注意：UserContextHolder 现在由 AuthFilter 在 HTTP 入口处设置
        // Reactor hook 会在 boundedElastic 线程中自动捕获并设置用户上下文

        SseEmitter emitter = agUiService.runAgent(enterpriseAgent, agUiParameters);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no") // 禁止 Nginx 缓冲，确保 SSE 实时
                .body(emitter);
    }

    /**
     * 健康检查端点
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("AG-UI Service is running");
    }

    /**
     * Agent 信息端点（供 CopilotKit Runtime 发现 Agent）
     */
    @GetMapping("/info")
    public ResponseEntity<AgentInfo> info() {
        return ResponseEntity.ok(new AgentInfo(
                "enterprise-agent",
                "企业智能助手",
                "帮助企业员工解答业务问题、查询数据、执行操作"
        ));
    }

    /**
     * Agent 信息模型
     */
    public record AgentInfo(
            String agentId,
            String name,
            String description
    ) {}
}
