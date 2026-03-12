# 认证信息透传方案

> **⚠️ 重要说明：本文档描述的是系统设计方案，当前项目尚未实现认证功能。以下代码均为示例实现，用于指导未来开发。**

## 问题背景

在 AI 助手架构中，用户登录后获得 JWT Token，当 AI 助手需要调用后端工具（FunctionCall）访问业务 API 时，如何使用用户的凭证代表用户进行 API 调用？这是一个经典的**身份上下文传递（Identity Context Propagation）**问题。

核心挑战：JWT 存在于 HTTP 请求上下文中，但 Spring AI 的 FunctionCall 执行发生在异步/回调链路里，默认情况下这个上下文会丢失。

---

## 方案对比

| 方案 | 优点 | 缺点 | 适用场景 |
|------|------|------|----------|
| **SecurityContextHolder** | Spring Security 标准，零额外代码 | 异步场景需配置 `MODE_INHERITABLETHREADLOCAL` | 已使用 Spring Security 的项目 |
| **ToolContext** | Spring AI 原生支持，显式传递，易于测试 | 需要 Spring AI 1.x+ | 新项目，推荐 |
| **Request-Scoped Bean** | 解耦良好，Filter 统一处理 | 异步跨线程需手动传递 | 复杂多 Tool 场景 |
| **自定义 ThreadLocal** | 完全控制 | 重复造轮子，不与 Spring 生态集成 | 不推荐 |

---

## 推荐方案：SecurityContextHolder + ToolContext 双保险

### 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: Spring Security Filter                              │
│   - JWT 验证                                                 │
│   - 写入 SecurityContextHolder                                │
│   - 设置 Authentication 对象                                │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│ Layer 2: Controller 层 (显式注入 ToolContext)               │
│   - 从 SecurityContext 读取 JWT                              │
│   - 注入到 ChatClient.toolContext()                             │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│ Layer 3: SkillTools (双重获取机制)                            │
│   - 优先从 ToolContext 获取（显式传递）                       │
│   - 降级从 SecurityContextHolder 获取（隐式传递）             │
│   - 用于 HTTP 请求的 Authorization header                    │
└─────────────────────────────────────────────────────────────┘
```

---

## 实现方案

### 1. Spring Security 配置

#### JWT 认证 Token

```java
package com.example.demo.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * JWT 认证 Token
 */
public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final UserPrincipal principal;
    private final String token;

    public JwtAuthenticationToken(UserPrincipal principal, String token) {
        super(
            Arrays.stream(principal.getRoles())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList())
        );
        this.principal = principal;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    public String getJwtToken() {
        return token;
    }
}
```

#### 用户主体

```java
package com.example.demo.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户主体信息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserPrincipal {
    private String userId;
    private String username;
    private String[] roles;
}
```

#### JWT 认证过滤器

```java
package com.example.demo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String authHeader = request.getHeader("Authorization");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);

                if (jwtService.validateToken(token)) {
                    // 解析用户信息
                    String userId = jwtService.extractUserId(token);
                    String username = jwtService.extractUsername(token);
                    String[] roles = jwtService.extractRoles(token);

                    // 创建 Authentication 对象
                    UserPrincipal principal = new UserPrincipal(userId, username, roles);
                    JwtAuthenticationToken authentication = new JwtAuthenticationToken(principal, token);

                    // 设置到 SecurityContext
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    log.debug("JWT 认证成功: userId={}, username={}", userId, username);
                } else {
                    log.warn("JWT Token 验证失败");
                }
            }

            filterChain.doFilter(request, response);

        } finally {
            // 清理 SecurityContext
            SecurityContextHolder.clearContext();
        }
    }
}
```

### 2. JWT Service

```java
package com.example.demo.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证 JWT Token
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 Token 中提取用户 ID
     */
    public String extractUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    /**
     * 从 Token 中提取用户名
     */
    public String extractUsername(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get("username", String.class);
    }

    /**
     * 从 Token 中提取角色
     */
    public String[] extractRoles(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get("roles", String[].class);
    }
}
```

### 3. Controller 层（注入 ToolContext）

```java
package com.example.demo.controller;

import com.agui.server.spring.AgUiParameters;
import com.agui.server.spring.AgUiService;
import com.agui.spring.ai.SpringAIAgent;
import com.example.demo.security.JwtAuthenticationToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/agui")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AgUiController {

    private final AgUiService agUiService;
    private final SpringAIAgent enterpriseAgent;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> run(
            @RequestBody AgUiParameters agUiParameters,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        log.info("收到 AG-UI 请求: threadId={}, runId={}",
                agUiParameters.getThreadId(),
                agUiParameters.getRunId());

        // 从 SecurityContext 获取 JWT（如果存在）
        Map<String, Object> toolContext = new HashMap<>();
        try {
            JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder
                    .getContext()
                    .getAuthentication();

            if (auth != null && auth.isAuthenticated()) {
                String jwt = auth.getJwtToken();
                toolContext.put("jwt", jwt);
                log.debug("注入用户 JWT 到 ToolContext: userId={}",
                        auth.getPrincipal().getUserId());
            }
        } catch (Exception e) {
            log.debug("未找到认证信息，使用匿名访问");
        }

        // 通过 forwardedProps 注入 ToolContext
        agUiParameters.setForwardedProps(toolContext);

        // 执行 Agent
        SseEmitter emitter = agUiService.runAgent(enterpriseAgent, agUiParameters);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
```

### 4. SkillTools（双重获取机制）

当前项目的 `SkillTools.httpRequest` 方法签名：

```java
@Tool(description = "发送 HTTP 请求调用 REST API。支持路径参数、查询参数、请求头和请求体。")
public String httpRequest(
    @ToolParam(description = "HTTP 方法：GET/POST/PUT/DELETE") String method,
    @ToolParam(description = "API 路径，可包含占位符如 /pet/{petId}") String url,
    @ToolParam(description = "路径参数") Map<String, String> pathParams,
    @ToolParam(description = "查询参数") Map<String, String> queryParams,
    @ToolParam(description = "请求头（如认证信息）") Map<String, String> headers,
    @ToolParam(description = "请求体（JSON 对象）") Map<String, Object> body
    // 添加 ToolContext 参数（Spring AI 自动注入）
    // ToolContext toolContext
) {
    // ...
}
```

**修改后的实现（支持认证透传）**：

```java
package com.example.demo.agent;

import com.example.demo.security.JwtAuthenticationToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SkillTools {

    private final SkillRegistry registry;
    private final RestTemplate restTemplate;
    private final String apiBaseUrl;
    private final boolean confirmBeforeMutate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> loadedSkills = new CopyOnWriteArrayList<>();

    public SkillTools(SkillRegistry registry, RestTemplate restTemplate,
                      @Value("${app.api.base-url}") String apiBaseUrl,
                      @Value("${app.confirm-before-mutate:false}") boolean confirmBeforeMutate) {
        this.registry = registry;
        this.restTemplate = restTemplate;
        this.apiBaseUrl = apiBaseUrl;
        this.confirmBeforeMutate = confirmBeforeMutate;
    }

    /**
     * 发送 HTTP 请求调用 REST API
     * 支持双重认证获取机制
     */
    @Tool(description = "发送 HTTP 请求调用 REST API。支持路径参数、查询参数、请求头和请求体。")
    public String httpRequest(
        @ToolParam(description = "HTTP 方法：GET/POST/PUT/DELETE") String method,
        @ToolParam(description = "API 路径，可包含占位符如 /pet/{petId}") String url,
        @ToolParam(description = "路径参数") Map<String, String> pathParams,
        @ToolParam(description = "查询参数") Map<String, String> queryParams,
        @ToolParam(description = "请求头（如认证信息）") Map<String, String> headers,
        @ToolParam(description = "请求体（JSON 对象）") Map<String, Object> body,
        ToolContext toolContext  // ← Spring AI 自动注入
    ) {
        try {
            // Step 0: 获取用户 JWT（双重机制）
            String jwt = extractJwt(toolContext);

            // Step 1: 替换路径参数
            String resolvedUrl = url;
            if (pathParams != null && !pathParams.isEmpty()) {
                for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                    resolvedUrl = resolvedUrl.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }

            // 确认模式
            if (confirmBeforeMutate && !"GET".equalsIgnoreCase(method)) {
                // ... 确认逻辑保持不变
            }

            // Step 2: 构建完整 URL
            String fullUrl = resolvedUrl.startsWith("http") ? resolvedUrl : apiBaseUrl + resolvedUrl;
            var uriBuilder = UriComponentsBuilder.fromHttpUrl(fullUrl);
            if (queryParams != null && !queryParams.isEmpty()) {
                queryParams.forEach(uriBuilder::queryParam);
            }

            // Step 3: 构建请求头
            var httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);

            // 添加用户传入的 headers
            if (headers != null && !headers.isEmpty()) {
                headers.forEach(httpHeaders::set);
            }

            // ⭐ 关键：自动添加用户认证头（如果用户未手动提供）
            if (jwt != null && !httpHeaders.containsKey(HttpHeaders.AUTHORIZATION)) {
                httpHeaders.setBearerAuth(jwt);
                log.debug("自动注入用户认证头到请求");
            }

            var entity = new HttpEntity<>(body, httpHeaders);

            // Step 4: 发送请求
            var response = restTemplate.exchange(
                uriBuilder.toUriString(),
                HttpMethod.valueOf(method.toUpperCase()),
                entity,
                String.class
            );

            String responseBody = response.getBody();
            return responseBody != null && responseBody.length() > 3000
                ? responseBody.substring(0, 3000) + "\n...[响应过长已截断]"
                : responseBody;
        } catch (Exception e) {
            return "HTTP 请求失败：" + e.getMessage();
        }
    }

    /**
     * 双重 JWT 获取机制
     */
    private String extractJwt(ToolContext toolContext) {
        // 方式 1：从 ToolContext 获取（优先）
        String jwt = extractJwtFromToolContext(toolContext);

        // 方式 2：从 SecurityContextHolder 获取（降级）
        if (jwt == null) {
            jwt = extractJwtFromSecurityContext();
        }

        return jwt;
    }

    /**
     * 从 ToolContext 提取 JWT
     */
    @SuppressWarnings("unchecked")
    private String extractJwtFromToolContext(ToolContext toolContext) {
        try {
            if (toolContext != null) {
                Object forwardedProps = toolContext.getContext();
                if (forwardedProps instanceof Map) {
                    Map<String, Object> context = (Map<String, Object>) forwardedProps;
                    if (context.containsKey("jwt")) {
                        return (String) context.get("jwt");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从 ToolContext 提取 JWT 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从 SecurityContextHolder 提取 JWT
     */
    private String extractJwtFromSecurityContext() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken) {
                JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) auth;
                if (jwtAuth.isAuthenticated()) {
                    return jwtAuth.getJwtToken();
                }
            }
        } catch (Exception e) {
            log.debug("从 SecurityContext 提取 JWT 失败: {}", e.getMessage());
        }
        return null;
    }

    // ... 其他方法保持不变（loadSkill、readSkillReference 等）
}
```

---

## 虚拟线程兼容性

### ✅ 完全兼容

Spring MVC 的**同步、每请求一线程**模型下，即使使用虚拟线程，`SecurityContextHolder` 方案完全可用：

```
HTTP 请求进来
  → 分配一个虚拟线程处理整个请求
    → JWT Filter 解析 → 写入 SecurityContextHolder
    → Controller 处理
    → Spring AI ChatClient 调用
    → FunctionCall 执行          ← 仍在同一个虚拟线程上！
    → 返回响应
  → 虚拟线程结束，ThreadLocal 自动清理
```

**关键点**：Spring AI 在 Spring MVC 同步模式下，FunctionCall 的执行和原始请求在**同一个线程**上完成，`ThreadLocal` 不会丢失。

### ⚠️ 需要注意的场景

**主动创建新线程**会导致上下文丢失：

```java
// ❌ 错误示例：在新线程中 SecurityContext 为空
public Response apply(Request req) {
    return CompletableFuture.supplyAsync(() -> {
        // 新线程！SecurityContextHolder 这里是空的
        return SecurityContextHolder.getContext().getAuthentication();
    }).join();
}
```

**解决方案**：使用 Spring Security 的安全包装器

```java
// ✅ 正确示例：使用安全包装器
import org.springframework.security.concurrent.DelegatingSecurityContextExecutor;

Executor secureExecutor = new DelegatingSecurityContextExecutor(
    Executors.newVirtualThreadPerTaskExecutor()
);

public Response apply(Request req) {
    return CompletableFuture.supplyAsync(() -> {
        // SecurityContext 已自动传递到新线程
        return SecurityContextHolder.getContext().getAuthentication();
    }, secureExecutor).join();
}
```

---

## 前端实现

### BFF 层转发

前端 BFF 层需要将用户的认证信息转发到 Java 后端。当前项目配置：

```typescript
// 当前实现（frontend/app/api/copilotkit/route.ts）
import { CopilotRuntime, copilotRuntimeNextJSAppRouterEndpoint } from "@copilotkit/runtime";
import { HttpAgent } from "@ag-ui/client";
import { NextRequest } from "next/server";

const JAVA_BACKEND_URL = process.env.JAVA_BACKEND_URL || "http://localhost:8080";

const runtime = new CopilotRuntime({
  agents: {
    default: new HttpAgent({
      url: `${JAVA_BACKEND_URL}/api/agui`,
    }),
  },
});

const { handleRequest } = copilotRuntimeNextJSAppRouterEndpoint({
  runtime,
  endpoint: "/api/copilotkit",
});

export async function POST(req: NextRequest) {
  return handleRequest(req);
}
```

**实现认证转发需要修改为**：

```typescript
// 修改后的实现（支持认证转发）
export async function POST(req: NextRequest) {
  // 场景 1：如果用户认证信息在请求头中（已存在）
  // 无需额外处理，handleRequest 会自动转发所有请求头

  // 场景 2：如果用户认证信息在 cookies 或 session 中
  // 需要手动提取并添加到请求头
  const authHeader = req.headers.get("authorization");

  if (!authHeader) {
    // 尝试从 cookies 或其他地方获取 token
    const token = req.cookies.get("auth_token")?.value;

    if (token) {
      // 创建新的 Headers 对象并添加认证信息
      const headers = new Headers(req.headers);
      headers.set("authorization", `Bearer ${token}`);

      // 创建新的请求对象
      const modifiedRequest = new Request(req.url, {
        method: req.method,
        headers: headers,
        // @ts-ignore - body 类型兼容性
        body: req.body,
        // @ts-ignore - duplex 类型兼容性
        duplex: "half",
      });

      return handleRequest(modifiedRequest as NextRequest);
    }
  }

  // 直接转发（已有认证信息或无需认证）
  return handleRequest(req);
}
```

> **注意**：`HttpAgent` 会自动将请求头转发到后端，包括 `Authorization` 头。因此：
> - 如果前端请求已经包含 `Authorization` 头，BFF 层无需额外处理
> - 如果前端请求没有 `Authorization` 头（如认证信息在 cookies 中），BFF 层需要手动提取并添加

---

## 安全考虑

### 1. 日志安全

**不要在日志中打印完整的 JWT Token**：

```java
// ❌ 错误
log.info("用户 Token: {}", jwt);

// ✅ 正确
log.info("用户已认证: userId={}", userId);
```

### 2. Token 过期处理

```java
@Tool(description = "发送 HTTP 请求调用 REST API")
public String httpRequest(
    String method,
    String url,
    Map<String, String> pathParams,
    Map<String, String> queryParams,
    Map<String, String> headers,
    Map<String, Object> body,
    ToolContext toolContext
) {
    String jwt = extractJwt(toolContext);

    // 检查 Token 是否即将过期
    if (jwt != null && jwtService.isTokenExpiringSoon(jwt)) {
        return "错误：Token 即将过期，请重新登录";
    }

    // ... 执行请求
}
```

### 3. 内部服务调用

对于内部服务调用，考虑使用**内部 Service Token**而不是用户 JWT：

```java
// 避免权限扩散
private String getInternalToken() {
    // 生成或获取内部服务的专用 Token
    return internalTokenService.getServiceToken();
}
```

---

## 配置文件

```yaml
# application.yml
jwt:
  secret: your-secret-key-here-must-be-at-least-256-bits-long

app:
  api:
    base-url: http://localhost:${server.port:8080}
  confirm-before-mutate: false

# 启用虚拟线程（Java 21+）
spring:
  threads:
    virtual:
      enabled: true
```

---

## 测试验证

### 单元测试

```java
@SpringBootTest
class SkillToolsTest {

    @Autowired
    private SkillTools skillTools;

    @Test
    void testHttpRequestWithJwt() {
        // 模拟 SecurityContext
        UserPrincipal principal = new UserPrincipal("user123", "testuser", new String[]{"USER"});
        JwtAuthenticationToken auth = new JwtAuthenticationToken(principal, "test-token");
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            String result = skillTools.httpRequest(
                "GET",           // method
                "/api/products", // url
                null,            // pathParams
                null,            // queryParams
                null,            // headers
                null,            // body
                null             // toolContext
            );
            assertNotNull(result);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
```

### 集成测试

```bash
# 1. 用户登录获取 Token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"pass"}'

# 2. 使用 Token 调用 AI 助手
curl -N -X POST http://localhost:3001/api/copilotkit \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <your-jwt-token>" \
  -d '{"messages":[{"role":"user","content":"查询我的订单"}]}'
```

---

## 总结

### 推荐方案

**SecurityContextHolder + ToolContext 双保险**：

- ✅ **Layer 1**: Spring Security 标准（SecurityContextHolder）
- ✅ **Layer 2**: Controller 层显式注入（ToolContext）
- ✅ **Layer 3**: SkillTools 双重获取（SecurityContext + ToolContext）

### 关键优势

1. **标准化**：使用 Spring Security 标准，与 Spring 生态集成
2. **可靠性**：双重获取机制，确保 Token 始终可用
3. **可测试性**：ToolContext 支持显式注入，易于测试
4. **兼容性**：虚拟线程完全兼容
5. **安全性**：统一的认证流程，支持匿名访问降级

### 适用场景

- ✅ Spring Boot 3.2+ / Java 17+
- ✅ Spring Security 集成（需要添加依赖）
- ✅ Spring AI 1.x+
- ✅ 同步请求处理

> **注意**：虚拟线程需要 Java 21+。当前项目使用 Java 17，虚拟线程支持为可选。

---

## 实施清单

### 1. 添加 Maven 依赖

在 `pom.xml` 中添加 Spring Security 和 JWT 依赖：

```xml
<dependencies>
    <!-- Spring Security -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- JWT 支持 -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.5</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.5</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.12.5</version>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

### 2. 需要创建的类

- `src/main/java/com/example/demo/security/JwtAuthenticationToken.java`
- `src/main/java/com/example/demo/security/UserPrincipal.java`
- `src/main/java/com/example/demo/security/JwtAuthenticationFilter.java`
- `src/main/java/com/example/demo/service/JwtService.java`
- `src/main/java/com/example/demo/config/SecurityConfig.java`

### 3. 需要修改的类

- `AgUiController.java` - 添加 ToolContext 注入逻辑
- `SkillTools.java` - 添加 ToolContext 参数和 JWT 提取逻辑

### 4. 前端修改

- `frontend/app/api/copilotkit/route.ts` - 添加认证头转发逻辑

### 5. 配置文件

在 `application.yml` 中添加：

```yaml
jwt:
  secret: your-secret-key-here-must-be-at-least-256-bits-long
```

> **生产环境提示**：JWT secret 应该使用环境变量或密钥管理服务，不要硬编码在配置文件中。
