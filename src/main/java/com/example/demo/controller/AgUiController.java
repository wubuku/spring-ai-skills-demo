package com.example.demo.controller;

import com.agui.server.spring.AgUiParameters;
import com.agui.server.spring.AgUiService;
import com.agui.spring.ai.SpringAIAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AG-UI 协议控制器
 * 提供 SSE (Server-Sent Events) 端点，供 CopilotKit Runtime 调用
 */
@RestController
@RequestMapping("/api/agui")
@CrossOrigin(origins = "*")
public class AgUiController {

    private static final Logger log = LoggerFactory.getLogger(AgUiController.class);

    private final AgUiService agUiService;
    private final SpringAIAgent enterpriseAgent;

    public AgUiController(AgUiService agUiService, SpringAIAgent enterpriseAgent) {
        this.agUiService = agUiService;
        this.enterpriseAgent = enterpriseAgent;
    }

    /**
     * AG-UI 协议端点
     * CopilotKit Runtime (Node.js BFF) 会 POST 到这里
     * 返回 SSE 事件流
     *
     * @param agUiParameters AG-UI 请求参数（包含消息、工具、线程ID等）
     * @return SSE Emitter
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> run(
            @RequestBody AgUiParameters agUiParameters,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        log.info("收到 AG-UI 请求: threadId={}, runId={}, messages={}",
                agUiParameters.getThreadId(),
                agUiParameters.getRunId(),
                agUiParameters.getMessages() != null ? agUiParameters.getMessages().size() : 0);

        // 生产环境：在此验证 JWT
        // jwtService.validate(authHeader);

        // 重置工具状态（如 loadedSkills）
        // 注意：这里通过 advisor 机制处理，无需手动重置

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
