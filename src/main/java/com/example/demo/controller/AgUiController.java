package com.example.demo.controller;

import com.agui.server.spring.AgUiParameters;
import com.agui.server.spring.AgUiService;
import com.agui.spring.ai.SpringAIAgent;
import com.example.demo.agent.SkillCoreTools;
import com.example.demo.auth.UserContextHolder;
import com.example.demo.dto.RuntimeSkillApiEntry;
import com.example.demo.service.RuntimeSkillCatalogService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final SkillCoreTools skillCoreTools;
    private final RuntimeSkillCatalogService skillCatalogService;

    public AgUiController(
            AgUiService agUiService,
            SpringAIAgent enterpriseAgent,
            SkillCoreTools skillCoreTools,
            RuntimeSkillCatalogService skillCatalogService
    ) {
        this.agUiService = agUiService;
        this.enterpriseAgent = enterpriseAgent;
        this.skillCoreTools = skillCoreTools;
        this.skillCatalogService = skillCatalogService;
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
    public SseEmitter run(
            @RequestBody AgUiParameters agUiParameters,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletResponse response
    ) {
        // ⚠️ 关键修复 (2026-06-14)：
        // 不要用 ResponseEntity<SseEmitter> 包装返回——Tomcat 10.1.34 在该组合下
        // 会触发 MimeHeaders NullPointerException（"this.headers[i] is null"），
        // 导致 undici/BFF 收到 bytesRead=0，CopilotKit 报 INCOMPLETE_STREAM 错误。
        // 改用 HttpServletResponse 注入 header，并直接返回 SseEmitter 即可。
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");  // 禁止 Nginx 缓冲，确保 SSE 实时
        response.setHeader("Connection", "keep-alive");
        log.info("[STEP2] 收到 AG-UI 请求: threadId={}, runId={}, messages={}, authHeader={}",
                agUiParameters.getThreadId(),
                agUiParameters.getRunId(),
                agUiParameters.getMessages() != null ? agUiParameters.getMessages().size() : 0,
                authHeader != null ? (authHeader.substring(0, Math.min(20, authHeader.length())) + "...") : "null");

        // [DIAG] Dump the tools array that CopilotKit/HTTP agent sent us
        try {
            var tools = agUiParameters.getTools();
            if (tools == null) {
                log.info("[DIAG-TOOLS] tools=null (前端没有传 tools 字段)");
            } else {
                log.info("[DIAG-TOOLS] CopilotKit/前端传过来的工具数量: {}", tools.size());
                for (int i = 0; i < tools.size(); i++) {
                    var t = tools.get(i);
                    String name = null;
                    String desc = null;
                    try {
                        var getName = t.getClass().getMethod("getName");
                        name = (String) getName.invoke(t);
                    } catch (Exception e) { name = t.toString(); }
                    try {
                        var getDesc = t.getClass().getMethod("getDescription");
                        desc = (String) getDesc.invoke(t);
                    } catch (Exception e) { desc = "?"; }
                    log.info("[DIAG-TOOLS]   [{}] name={}, desc={}", i, name, desc != null ? desc.substring(0, Math.min(80, desc.length())) : "null");
                }
            }
        } catch (Exception e) {
            log.warn("[DIAG-TOOLS] dump failed: {}", e.getMessage());
        }

        // [STEP2] 在调用 SpringAIAgent 之前，记录当前线程上下文
        var authObj = SecurityContextHolder.getContext().getAuthentication();
        String tokenInUserCtx = UserContextHolder.getToken();
        log.info("[STEP2] before SpringAIAgent, thread={}, auth={}, authenticated={}, tokenInUserCtx={}",
                Thread.currentThread().getName(),
                authObj != null ? authObj.getClass().getSimpleName() : "null",
                authObj != null && authObj.isAuthenticated(),
                tokenInUserCtx != null ? "present" : "null");

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

            // 关键修复：同时设置 UserContextHolder，确保异步 SSE 流中也能获取到 JWT
            // 因为 SSE 是异步返回的，Reactor hook 可能无法在正确的时机捕获 SecurityContext
            UserContextHolder.setToken(jwt);
            UserContextHolder.setUsername("user");
            log.debug("已设置 JWT 到 SecurityContext 和 UserContextHolder，确保异步 SSE 流中透传");

            // [STEP2] 设置后再次记录
            log.info("[STEP2] JWT 已设置, tokenInUserCtx={}", UserContextHolder.getToken() != null);
        } else {
            log.warn("未收到 Authorization header! authHeader={}", authHeader);
        }

        // 通过 forwardedProps 传递 JWT（备用方案）
        Map<String, Object> toolContext = new HashMap<>();
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            toolContext.put("jwt", authHeader.substring(7));
        }
        agUiParameters.setForwardedProps(toolContext);

        skillCoreTools.reset();

        // 注意：UserContextHolder 现在由 AuthFilter 在 HTTP 入口处设置
        // Reactor hook 会在 boundedElastic 线程中自动捕获并设置用户上下文

        SseEmitter emitter = agUiService.runAgent(enterpriseAgent, agUiParameters);

        return emitter;
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
    /**
     * API 端点索引（供前端 httpRequest URL 校验）
     * 返回所有已注册的 API 端点，格式: {"GET /api/products": {"skillName":"search-products", ...}}
     */
    @GetMapping("/skills/api-index")
    public ResponseEntity<Map<String, RuntimeSkillApiEntry>> apiIndex() {
        return ResponseEntity.ok(skillCatalogService.apiIndex());
    }

    public record AgentInfo(
            String agentId,
            String name,
            String description
    ) {}
}
