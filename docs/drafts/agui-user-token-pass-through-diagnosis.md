# AG-UI 用户态 Token 透传问题 - 完整诊断与修复方案

> 调研日期：2026-06-01
> 调研版本：本仓库内 `src/main/java/com/agui/`（**拷贝自上游 ag-ui-4j，git 子模块 `ag-ui-4j/` 仅作"方便查看上游"之用**）
> 调研结论：**之前"AG-UI 不支持用户态 Token 透传"的结论是错的。** 问题不在 Reactor/线程切换本身，而是 `SpringAIAgent.getChatRequest` 里**完全没有把 `RunAgentInput.forwardedProps()` 桥接到 Spring AI 的 `ToolCallingChatOptions.toolContext`**——这是一个**集成缺口**。修复成本极低（3-5 行 `SpringAIAgent.java` 源码 + 1 个 `SkillTools` 形参 + 1 个 `extractJwt` 重构）。

## 重要：代码归属与同步模型

> 看到 `com.agui.*` 包路径时，要先区分清楚：这些代码不在 `ag-ui-4j/` git 子模块里——它们是**直接拷贝到 `src/main/java/com/agui/` 的代码**，和项目本身一起编译。
>
> `ag-ui-4j/` 子模块（用 `git submodule` 引入的仓库）的存在**只是为了方便开发者查看上游改动**。编译时 Maven 不会去子模块找类——Maven 看到的是 `src/main/java/com/agui/` 下的 Java 文件。
>
> 这意味着：
> - 你可以**直接修改** `src/main/java/com/agui/spring/ai/SpringAIAgent.java`，改完 `mvn compile` 立刻生效。
> - 不用 `mvn install` 子模块，**也不需要 `-pl ag-ui-4j/...` 这种路径**。
> - 缺点是上游有新版本时，得手动把更新同步回来（如果上游也实现了 forwardedProps 桥接，那这个 patch 就和上游合流了）。
> - `ag-ui-4j/` 这个目录**不在 classpath 里**——只有 `src/main/java/com/agui/` 才是真正参与编译的代码。

---

## 目录

1. [问题陈述](#1-问题陈述)
2. [关键代码定位](#2-关键代码定位)
3. [两条路径的线程模型对比](#3-两条路径的线程模型对比)
4. [为什么 `Schedulers.onScheduleHook` 不能救场](#4-为什么-schedulersonschedulehook-不能救场)
5. [之前尝试过的所有方案为什么都失败](#5-之前尝试过的所有方案为什么都失败)
6. [`ToolContext` 注入方案"看似存在 6 个隐藏坑"](#6-toolcontext-注入方案看似存在-6-个隐藏坑)
7. [关键佐证：`forwardedProps` 在 ag-ui-4j 拷贝代码中从未被消费](#7-关键佐证forwardedprops-在-ag-ui-4j-拷贝代码中从未被消费)
8. [代码归属澄清：`SpringAIAgent` 不属于 Spring AI，并且 git 子模块里的代码不参与编译](#8-代码归属澄清springaiagent-不属于-spring-ai并且-git-子模块里的代码不参与编译)
9. [推荐修复方案：3 个完整代码 diff](#9-推荐修复方案3-个完整代码-diff)
10. [其他次选方案](#10-其他次选方案)
11. [测试验证方案](#11-测试验证方案)
12. [总结](#12-总结)
- [附录 A：完整修改文件清单](#附录-a完整修改文件清单)
- [附录 B：未来可清理的"妥协代码"](#附录-b未来可清理的妥协代码)
- [附录 C：拷贝同步策略（重要！避免后续踩坑）](#附录-c拷贝同步策略重要避免后续踩坑)

---

## 1. 问题陈述

在 AI 助手架构中，当 LLM 决定调用一个外部工具（如 `httpRequest`）时，我们希望该工具能用**当前用户**的 JWT 访问受保护的后端 API，**而非**用服务级 Token 降级。

**当前状态**：

| 路径 | 端点 | Token 透传 |
|------|------|-----|
| **非 AG-UI** | `/api/chat/stream`、`/api/chat/multimodal/stream` | ✅ 工作 |
| **AG-UI** | `/api/agui`（CopilotKit 客户端用） | ❌ 失败，返回 401/403 |

**之前尝试过但放弃的方向**：
- `SecurityContextHolder` 直接传 → 切到 ForkJoinPool 后丢失
- `MODE_INHERITABLETHREADLOCAL` → ForkJoinPool worker 是预创建的
- `Schedulers.onScheduleHook` 抓 ThreadLocal → 抓取点已经不在 HTTP 线程
- `agUiParameters.setForwardedProps(...)` → 协议层有数据但被 SpringAIAgent 忽略
- 双重兜底（`UserContextHolder` + `SecurityContextHolder`）→ 全部依赖 ThreadLocal

---

## 2. 关键代码定位

### 2.1 主项目代码（`com.example.demo` 包）

| 角色 | 文件 | 行 |
|------|------|-----|
| Auth 过滤器（压 JWT 到 SecurityContext 和 UserContextHolder） | `src/main/java/com/example/demo/auth/AuthFilter.java` | 46-79 |
| 非 AG-UI 入口 | `src/main/java/com/example/demo/controller/ChatController.java` | 92-94（`subscribeOn`） |
| AG-UI 入口 | `src/main/java/com/example/demo/controller/AgUiController.java` | 56-119 |
| AG-UI Agent 配置 | `src/main/java/com/example/demo/config/AgUiConfig.java` | 48-75 |
| 工具类（@Tool） | `src/main/java/com/example/demo/agent/SkillTools.java` | 71-80, 128-185 |
| 当前 JWT 提取 | `src/main/java/com/example/demo/agent/SkillTools.java` | 192-225 |
| BoundedElastic hook | `src/main/java/com/example/demo/config/ReactorBoundedElasticHookConfig.java` | 全文 |
| SecurityContext 继承 | `src/main/java/com/example/demo/config/SecurityContextInheritanceConfig.java` | 全文 |
| UserContextHolder | `src/main/java/com/example/demo/auth/UserContextHolder.java` | 全文 |

### 2.2 ag-ui-4j 拷贝代码（**直接放在 `src/main/java/com/agui/`，与主项目一起编译**）

| 角色 | 文件 | 行 |
|------|------|-----|
| `LocalAgent.runAgent` 切线程 | `src/main/java/com/agui/server/LocalAgent.java` | 131 |
| `createSystemMessage` 拼系统消息 | 同上 | 199-227 |
| `SpringAIAgent.run` | `src/main/java/com/agui/spring/ai/SpringAIAgent.java` | 125-165 |
| `SpringAIAgent.getChatRequest` | 同上 | 238-312（**关键修改点**） |
| `AgUiService.runAgent` | `src/main/java/com/agui/server/spring/AgUiService.java` | 121-149（`forwardedProps` 透传在 128 行） |
| `AgentStreamer.streamEvents` | `src/main/java/com/agui/server/streamer/AgentStreamer.java` | 79-...（流式事件分发主入口） |
| `RunAgentInput.forwardedProps` 协议字段 | `src/main/java/com/agui/core/agent/RunAgentInput.java` | 39-47 |

> **路径说明**：上面这些 `com.agui.*` 包的代码**不在** `ag-ui-4j/` git 子模块里——它们已经**直接拷贝到 `src/main/java/com/agui/` 目录**。`ag-ui-4j/` 这个目录仅作"方便查看上游修改"之用，**不在 Maven classpath 里**。详见文档开头"重要：代码归属与同步模型"一节。

### 2.3 Spring AI 官方（**不要改**）

| 角色 | 文件 | 行 |
|------|------|-----|
| OpenAI 流式工具执行 | `OpenAiChatModel.java` (1.1.2) | 366-401（`flatMap` + `executeToolCalls` + `subscribeOn(boundedElastic)`） |
| `DefaultToolCallingManager.buildToolContext` | `DefaultToolCallingManager.java` | 155-167 |
| `DefaultToolCallingManager.executeToolCalls` | 同上 | 126（主入口） |
| `MethodToolCallback.call` 注入 ToolContext | `MethodToolCallback.java` | 100-119（`call` 主方法） + 121-128（`validateToolContextSupport`） + 144-152（`buildMethodArguments`） |
| `DefaultChatClientUtils.toChatClientRequest` 懒创建 options | `DefaultChatClientUtils.java` | 107-148（options 处理整段） |
| `ChatClientRequestSpec.tools` / `toolContext` | `DefaultChatClient.java` | （未单独验证行号） |

---

## 3. 两条路径的线程模型对比

### 3.1 非 AG-UI 路径（成功）—— 1 次线程切换

```
[线程 0] HTTP 请求线程
   │  AuthFilter 解析 JWT：
   │     - SecurityContextHolder.setAuthentication(...)  ← 注入到 SecurityContext
   │     - UserContextHolder.setToken(...)                ← 注入到自定义 ThreadLocal
   │  ChatController.chatStream 又额外设置了 SecurityContextHolder（冗余）
   │
   └─▶ ChatController.chatStream (HTTP 线程)
          └─▶ agentService.streamChat(...)  →  返回 Flux<String>
                 └─▶ tokenFlux.subscribeOn(Schedulers.boundedElastic())   ← 切换在此发起
                                          ║
   ╠══════════════════════════════════════╝    ★ 第 1 次切换：HTTP → boundedElastic
   │
[线程 1] boundedElastic 线程
   │  Schedulers.onScheduleHook 触发（decorator 在 HTTP 线程执行）：
   │     - 读 UserContextHolder.getToken() → ✅ 拿到 JWT（AuthFilter 设的）
   │     - 读 SecurityContextHolder → ✅ 也能拿到（双重保险）
   │  任务执行：订阅 + 工具调用 + SkillTools.httpRequest
   │  extractJwt() 读 UserContextHolder → ✅ 拿到 JWT
   │  HTTP 请求带 Authorization → ✅ 200
```

**关键**：`.subscribeOn(boundedElastic())` 是在 **HTTP 请求线程**里调用的，所以 `Schedulers.onScheduleHook` decorator 在 HTTP 线程执行，能读到 JWT。

> **常见误解澄清**：`SkillTools` 是 Spring `@Component` 单例 bean，**它的构造函数只在启动时调用一次**，不会"压 UserContextHolder"。`UserContextHolder` 是 `AuthFilter` 在每个 HTTP 请求入口处压的（见 `AuthFilter.java:73-74`）。

### 3.2 AG-UI 路径（失败）—— 2 次线程切换

```
[线程 0] HTTP 请求线程
   │  AuthFilter 解析 JWT（同时设 SecurityContextHolder 和 UserContextHolder）
   │  AgUiController.run(...) 里再次设置 SecurityContextHolder 和 UserContextHolder（双重保险）
   │
   └─▶ AgUiController.run (HTTP 线程)
          └─▶ agUiService.runAgent(enterpriseAgent, agUiParameters)        [同步]
                 └─▶ agentStreamer.streamEvents(...)                      [同步]
                        └─▶ enterpriseAgent.runAgent(parameters, ...)
                               └─▶ CompletableFuture.runAsync(...)        [★ 切线程 ★]
                                                 ║
   ╠═════════════════════════════════════════════╝    ★ 第 1 次切换：HTTP → ForkJoinPool
   │
[线程 1] ForkJoinPool.commonPool 线程
   │  ⚠️ UserContextHolder 在这里 **没有值**（ForkJoinPool 是 JVM 全局共享、预创建的，
   │     INHERITABLETHREADLOCAL 不生效）
   │  ⚠️ SecurityContextHolder 同样为空
   │
   └─▶ SpringAIAgent.run(input, subscriber)
          └─▶ getChatRequest(...).stream().chatResponse().subscribe(...)   [订阅在 ForkJoinPool 线程发起]
                 └─▶ OpenAiChatModel.internalStream(...) 收到 LLM 决定调工具的 ChatResponse
                        └─▶ .subscribeOn(Schedulers.boundedElastic()) 触发内部工具执行
                                                       ║
   ╠═══════════════════════════════════════════════════╝    ★ 第 2 次切换：ForkJoinPool → boundedElastic
   │
[线程 2] boundedElastic 线程
   │  Schedulers.onScheduleHook 触发：
   │     - decorator 读 UserContextHolder.getToken() → ❌ null（捕获点所在的"当前线程"是 ForkJoinPool）
   │     - decorator 读 SecurityContextHolder → ❌ null
   │  任务执行：toolCallback.call(...) → SkillTools.httpRequest
   │  extractJwt() → ❌ null
   │  HTTP 请求无 Authorization → ❌ 401/403
```

**根本原因**：
- `LocalAgent.runAgent` 用 `CompletableFuture.runAsync(Runnable)`（**没有指定 Executor**），默认用 `ForkJoinPool.commonPool()`。
- 切换发生时，HTTP 线程上的 ThreadLocal 全部"断"在 HTTP 线程上，无法跟到 ForkJoinPool 线程。
- 后续所有的"调度 + 钩子 + 工具调用"都发生在没有上下文的线程里。

---

## 4. 为什么 `Schedulers.onScheduleHook` 不能救场

`ReactorBoundedElasticHookConfig` 的核心代码：
```java
Function<Runnable, Runnable> decorator = runnable -> {
    String token = UserContextHolder.getToken();   // 读"当前线程"ThreadLocal
    String username = UserContextHolder.getUsername();
    if (token == null) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getCredentials() instanceof String jwt) {
            token = jwt;
        }
    }
    // ... 包装 runnable，在执行时 set 到目标线程
};
```

`Schedulers.onScheduleHook` 的语义：**当 Reactor 内部要 schedule 一个 task 时**（即调用 `Schedulers.boundedElastic().schedule(runnable)` 的瞬间），用 decorator 包装 runnable。

decorator 函数体在**调用 schedule 那个线程**里执行——它读到的 ThreadLocal 就是**那个线程**的。

| 路径 | 谁调用 schedule | 那一刻的"当前线程" | 读到的 ThreadLocal |
|------|------|------|------|
| 非 AG-UI | HTTP 请求线程调用 `Flux.subscribeOn(boundedElastic())` | HTTP 请求线程 | ✅ 有 JWT |
| AG-UI | ForkJoinPool 线程调用 `Flux.subscribeOn(boundedElastic())` | ForkJoinPool 线程 | ❌ null |

**核心反直觉点**：boundedElastic 线程自己"被调用时"是不执行 decorator 的；decorator 在**调度发生的那一刻**、在**调度者线程**里执行。所以"在哪条线程上发起调度"才是关键。

---

## 5. 之前尝试过的所有方案为什么都失败

| 方案 | 失败点 |
|------|------|
| `SecurityContextHolder` 直接传 | 切到 ForkJoinPool 后丢失 |
| `SecurityContextHolder.MODE_INHERITABLETHREADLOCAL` | ForkJoinPool worker 是预创建的，不走 `Thread` 构造器继承路径 |
| `Schedulers.onScheduleHook` 抓 ThreadLocal | 抓取点是 ForkJoinPool 线程，已经没有 JWT |
| `agUiParameters.setForwardedProps(toolContext)` | 协议层有数据，但 `SpringAIAgent.getChatRequest` 完全没有把 `forwardedProps` 桥接到 Spring AI 的 `ToolCallingChatOptions.toolContext` |
| 双重兜底（UserContextHolder + SecurityContextHolder + 多种策略） | 全部依赖 ThreadLocal，全部在第 1 次切换后失效 |

**根本症结**：所有"传 ThreadLocal"的方案在 `CompletableFuture.runAsync(...)` 这一刀上**集体阵亡**。

---

## 6. `ToolContext` 注入方案"看似存在 6 个隐藏坑"

### 6.1 Spring AI 的 ToolContext 机制本身是完整的

源码 `MethodToolCallback.java:100-119`：
```java
public String call(String toolInput, @Nullable ToolContext toolContext) {
    ...
    this.validateToolContextSupport(toolContext);
    Map<String, Object> toolArguments = this.extractToolArguments(toolInput);
    Object[] methodArguments = this.buildMethodArguments(toolArguments, toolContext);  // ← 注入
    Object result = this.callMethod(methodArguments);
    ...
}

private Object[] buildMethodArguments(Map<String, Object> toolInputArguments, @Nullable ToolContext toolContext) {
    return Stream.of(this.toolMethod.getParameters()).map(parameter -> {
        if (parameter.getType().isAssignableFrom(ToolContext.class)) {
            return toolContext;   // ← 自动注入
        }
        Object rawArgument = toolInputArguments.get(parameter.getName());
        return buildTypedArgument(rawArgument, parameter.getParameterizedType());
    }).toArray();
}
```

`MethodToolCallback.validateToolContextSupport`：
```java
private void validateToolContextSupport(@Nullable ToolContext toolContext) {
    var isNonEmptyToolContextProvided = toolContext != null && !CollectionUtils.isEmpty(toolContext.getContext());
    var isToolContextAcceptedByMethod = Stream.of(this.toolMethod.getParameterTypes())
        .anyMatch(type -> ClassUtils.isAssignable(ToolContext.class, type));
    if (isToolContextAcceptedByMethod && !isNonEmptyToolContextProvided) {
        throw new IllegalArgumentException("ToolContext is required by the method as an argument");
    }
}
```

**机制本身没问题**——只要 `chatRequest.toolContext(...)` 被设置且 prompt 走到了 `DefaultToolCallingChatOptions` 路径，Spring AI 就会把 ToolContext 注入到 `@Tool` 方法。

### 6.2 ToolContext 从哪里来

`DefaultToolCallingManager.buildToolContext`（在 `DefaultToolCallingManager.java:155` 定义，被 `DefaultToolCallingManager.executeToolCalls:141` 调用；后者又被 `OpenAiChatModel.java:374` 的流式工具执行块调用）：
```java
private static ToolContext buildToolContext(Prompt prompt, AssistantMessage assistantMessage) {
    Map<String, Object> toolContextMap = Map.of();

    if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions
            && !CollectionUtils.isEmpty(toolCallingChatOptions.getToolContext())) {
        toolContextMap = new HashMap<>(toolCallingChatOptions.getToolContext());
        toolContextMap.put(ToolContext.TOOL_CALL_HISTORY,
                buildConversationHistoryBeforeToolExecution(prompt, assistantMessage));
    }
    return new ToolContext(toolContextMap);
}
```

**ToolContext 来自 `prompt.getOptions().getToolContext()`**。

### 6.3 但 `SpringAIAgent.getChatRequest` 从来没把 JWT 写进 ToolContext

```java
// src/main/java/com/agui/spring/ai/SpringAIAgent.java:238-246
private ChatClient.ChatClientRequestSpec getChatRequest(RunAgentInput input, ...) {
    ChatClient.ChatClientRequestSpec chatRequest = this.chatClient.prompt(
        Prompt.builder().content(content).build()   // ← 没有任何 options
    ).system(systemMessage.getContent());
    ...
    chatRequest = chatRequest.tools(this.tools.toArray(new Object[0]));
    ...
}
```

`RunAgentInput.forwardedProps()`（AG-UI 协议层明确提供的"per-request 数据"）**完全没被用上**。

### 6.4 那用户可以"自己加 Advisor 把 JWT 写进 ToolContext"吗？

**理论上可以，实践上工程量很大、而且容易踩坑。**

#### 坑 1：Advisor 本身是 singleton

`SpringAIAgent` 配置 Advisor 在 builder 阶段（`builder.advisor(skillsAdvisor)`），是单例对象，没有 per-request 状态。要让 Advisor 知道当前请求的 JWT，必须通过以下两条路之一：

**路 A**：Advisor 读 ThreadLocal（`UserContextHolder.getToken()`）—— 这就是被切换打断的那条路，**死循环**。

**路 B**：Advisor 读 `chatClientRequest.context()` —— 这是 `ChatClientRequestSpec.advisors(a -> a.param("jwt", jwt))` 设进去的 per-request 上下文。

但路 B 要求有人在 `getChatRequest` 里调用 `advisors(a -> a.param(...))`：

```java
// SpringAIAgent.java:305
chatRequest.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, input.threadId()));
```

**这是 `SpringAIAgent` 自己**在设置 threadId。要设置 jwt，**必须修改 `SpringAIAgent.getChatRequest` 源码**（或者 fork + patch），从 `input.forwardedProps()` 取出来塞进去。

#### 坑 2：Advisor 改 prompt.options 还要走得通

假设用户在 `SpringAIAgent` 子类里加了：
```java
chatRequest.advisors(a -> a.param("jwt", input.forwardedProps().get("jwt")));
```

然后写一个 Advisor：
```java
public class JwtToolContextAdvisor implements BaseAdvisor {
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String jwt = (String) request.context().get("jwt");
        if (jwt != null) {
            ChatOptions opts = request.prompt().getOptions();
            if (opts == null) {
                opts = new DefaultToolCallingChatOptions();
                request = mutateRequest(request, opts);
            }
            if (opts instanceof ToolCallingChatOptions tco) {
                tco.setToolContext(Map.of("jwt", jwt));
            }
        }
        return request;
    }
}
```

**问题**：Spring AI 的 `ChatClientRequest` 是 immutable record：
```java
public record ChatClientRequest(Prompt prompt, Map<String, Object> context) {
    public ChatClientRequest copy() { ... }
    public Builder mutate() { ... }    // 注意：重载，返回 Builder 才能改字段
}
```

`prompt` 本身也是 immutable（Spring AI 用 `Prompt.builder().build()` 生成）。要修改 options，必须：
1. 调 `request.mutate().prompt(...)` 重建 prompt
2. 新 prompt 装新 options
3. 调 `tco.setToolContext(...)` 写入

这不是不可能，但用户需要：
- 复制 `Prompt.builder().messages(...).chatOptions(newOptions).build()`
- 复制新 ChatClientRequest

#### 坑 3：流式路径上 `ToolContext` 真的会被读到吗？

回到 `OpenAiChatModel.java:366-401`：
```java
Flux<ChatResponse> flux = chatResponse.flatMap(response -> {
    if (this.toolExecutionEligibilityPredicate.isToolExecutionRequired(prompt.getOptions(), response)) {
        return Flux.deferContextual(ctx -> {
            ToolExecutionResult toolExecutionResult;
            try {
                ToolCallReactiveContextHolder.setContext(ctx);
                toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response);  // ← prompt 就是这个请求的 prompt
            }
            ...
        }).subscribeOn(Schedulers.boundedElastic());
    }
    ...
});
```

`this.toolCallingManager.executeToolCalls(prompt, response)` 里的 `prompt` 是外层的 prompt。如果 Advisor 成功把 `toolContext` 写进了这个 prompt 的 options，**这里就会读到**。

所以**理论上**，从工具调用读取的链路是通的：
- Advisor 改 prompt.options.toolContext → 内部 executeToolCalls 用这个 prompt → buildToolContext 读 options → MethodToolCallback 收到 ToolContext → 注入到 @Tool 方法

#### 坑 4（最致命）：**`SpringAIAgent` 用了 `defaultTools(skillTools)`，不是 `toolContext(...)`**

在 `AgUiConfig` 里：
```java
return SpringAIAgent.builder()
    ...
    .tool(skillTools)   // ← 把 SkillTools 当作"对象"注册，Spring AI 会扫描 @Tool 方法
    ...
```

`SpringAIAgent` 内部 `getChatRequest` 调：
```java
chatRequest = chatRequest.tools(this.tools.toArray(new Object[0]));
```

`ChatClientRequestSpec.tools(Object...)` 内部：
```java
public ChatClientRequestSpec tools(Object... toolObjects) {
    this.toolCallbacks.addAll(Arrays.asList(ToolCallbacks.from(toolObjects)));
    return this;
}
```

`ToolCallbacks.from(...)` 会用反射扫描 `@Tool` 注解方法，**生成 `MethodToolCallback` 数组**。

后续 `DefaultChatClientUtils.toChatClientRequest`：
```java
if (!inputRequest.getToolNames().isEmpty() || !inputRequest.getToolCallbacks().isEmpty()
        || !inputRequest.getToolCallbackProviders().isEmpty()
        || !CollectionUtils.isEmpty(inputRequest.getToolContext())) {   // ← 关键

    if (processedChatOptions == null) {
        processedChatOptions = new DefaultToolCallingChatOptions();
    }
    ...
}
```

**只有当 `inputRequest.getToolContext()` 非空时，才会创建 `DefaultToolCallingChatOptions`！**

如果用户**没有显式调用 `chatRequest.toolContext(...)`**，那么 processedChatOptions 还是 null，最终 `prompt.getOptions()` 是 null，DefaultToolCallingManager.buildToolContext 的 instanceof 判断失败，ToolContext 就是空的 `Map.of()`。

#### 坑 5：Advisor 改 `chatRequest.prompt().getOptions()` 会让 Spring AI 重建 options

更准确地说，Advisor 拿到 `chatClientRequest.prompt().getOptions()` 时，这 options 可能是 null，也可能是 `DefaultChatOptions`（非 tool-calling 版本）。用户必须：

1. 把它升级成 `DefaultToolCallingChatOptions`
2. 设置 `toolContext`
3. 重新构造 prompt，再重新构造 chatClientRequest

如果用户**只在 `defaultToolContext` 里加 jwt**（即调用 `ChatClientBuilder.defaultToolContext(...)`），那也是无效的——`defaultToolContext` 只在 ChatClient 构造时设置一次，不是 per-request。

#### 坑 6：流式 + 工具循环 + ToolContext 状态

`DefaultToolCallingManager.executeToolCalls` 是同步的（在 boundedElastic 线程跑）。但 `OpenAiChatModel` 的流式路径会**多轮**调用 `executeToolCalls`（每一轮调完工具都把结果塞回 prompt 然后再调 LLM）：

```java
return Flux.deferContextual(ctx -> {
    ...
    toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response);
    ...
    if (toolExecutionResult.returnDirect()) {
        return Flux.just(...);
    } else {
        // 关键：递归调用 internalStream，新的 prompt 带 tool result
        return this.internalStream(new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()), response);
    }
}).subscribeOn(Schedulers.boundedElastic());
```

第二轮 `internalStream` 里的 `prompt.getOptions()` 是从第一轮 `copy` 过来的。**只要原始 options 里有 toolContext，递归就继承下去**。这倒不是问题。

---

## 7. 关键佐证：`forwardedProps` 在 ag-ui-4j 拷贝代码中从未被消费到 Spring AI 内部

```
$ grep -rn -i "forwardedProps" src/main/java/com/agui --include="*.java"
src/main/java/com/agui/server/spring/AgUiParameters.java       ← 字段定义 + getter/setter（数据进来）
src/main/java/com/agui/server/spring/AgUiService.java:128      ← RunAgentParameters.builder().forwardedProps(...) 透传构造（数据进来）
src/main/java/com/agui/server/LocalAgent.java:128              ← new RunAgentInput(... parameters.getForwardedProps()) 透传构造（数据进来）
src/main/java/com/agui/core/agent/RunAgentParameters.java      ← builder 字段定义（数据进来）
src/main/java/com/agui/core/agent/RunAgentInput.java           ← record 字段定义（数据进来）
src/main/java/com/agui/spring/ai/SpringAIAgent.java            ← 完全未引用（**重点**）
```

注：上游 `ag-ui-4j` 仓库里，forwardedProps 还会出现在 OkHttp client 客户端的测试里，但**没有任何生产代码**把 `forwardedProps` **消费**到 Spring AI 内部（即塞到 `SystemMessage` / `ToolContext` / `Advisor params` 任一处）。

ag-ui-4j 上游只承认 `forwardedProps` 是协议层的字段，但**没有任何"消费"机制**。`createSystemMessage` 用的只是 `state` 和 `input.context()`，把 `forwardedProps` 完全忽略。

也就是说：
- 客户端（CopilotKit）把 `forwardedProps` 发到了 `AgUiParameters` ✅
- `AgUiController` 调 `agUiParameters.setForwardedProps(...)` 写入 ✅
- `AgUiService.runAgent:128` 构造 `RunAgentParameters` 时透传 ✅
- `LocalAgent.runAgent:128` 构造 `RunAgentInput` 时再次透传 ✅
- `RunAgentInput.forwardedProps()` 能被读到 ✅
- 但 `SpringAIAgent` 拿到 `RunAgentInput` 后**当透明数据**处理了——既没塞 SystemMessage，也没塞 ToolContext，更没塞 Advisor params ❌

**这证实了诊断**：ag-ui-4j 还没有原生支持"per-request 上下文注入到工具调用"。这是一个**已知的集成缺口**。

---

## 8. 代码归属澄清：`SpringAIAgent` 不属于 Spring AI，并且 git 子模块里的代码不参与编译

在动手修改前必须澄清两个**重要的归属问题**：

| 代码 | 所属 | 是否可改 |
|------|------|---------|
| `OpenAiChatModel` / `MethodToolCallback` / `DefaultToolCallingManager` | Spring AI 官方 | ❌ 不建议改 |
| `LocalAgent` / `SpringAIAgent` / `AgUiService`（`src/main/java/com/agui/...`） | **本仓库拷贝的 ag-ui-4j 源码** | ✅ **可以改** |
| `ag-ui-4j/` 目录里同名文件 | git 子模块，**仅作查看上游** | ⚠️ 改它**没用**——Maven 不编译它 |
| `SkillTools` / `AgUiConfig` | 你自己的项目 | ✅ 当然可以改 |

### 8.1 git 子模块只是"方便查看上游"

```
$ cat .gitmodules
[submodule "ag-ui-4j"]
    path = ag-ui-4j
    url = https://github.com/Work-m8/ag-ui-4j.git
    branch = main
```

`ag-ui-4j/` 这个目录是一个 **git 子模块**。它的**唯一作用**是让开发者能直接 `cd ag-ui-4j && git log` 看上游的提交历史、和上游代码做 diff。

**关键事实**：

- `ag-ui-4j/` 这个目录里的 `.java` 文件**不在 Maven classpath 里**——Maven 只编译 `src/main/java/`。
- 因此**修改 `ag-ui-4j/.../SpringAIAgent.java` 完全没用**——它对项目没有任何影响。
- 真正参与编译的是 `src/main/java/com/agui/spring/ai/SpringAIAgent.java`。
- 这两个文件**可能存在 drift**——因为拷贝是手动的，不会自动同步。

### 8.2 "拷贝模型" 的代价与红利

| 方面 | 影响 |
|------|------|
| **好处** | 改 `src/main/java/com/agui/` 立刻生效，不用 `-pl ag-ui-4j/...` 也不用 `mvn install` 子模块 |
| **坏处** | 上游 ag-ui-4j 升级时，`src/main/java/com/agui/` 不会自动更新——需要手动 merge |
| **本仓库的同步策略** | 看 `.git` 状态时把 `ag-ui-4j/` 当成"只读参考"对待；改代码只动 `src/main/java/com/agui/` |
| **上游合并冲突风险** | 如果未来上游实现了 `forwardedProps` 桥接，本仓库的 patch 可能和上游冲突——但一眼能解（你加的就是上游"应该加"的代码） |

### 8.3 一个棘手的细节：`getChatRequest` 是 `private` 的

```java
// src/main/java/com/agui/spring/ai/SpringAIAgent.java:238
private ChatClient.ChatClientRequestSpec getChatRequest(RunAgentInput input, ...) {  // ← private
    ...
}
```

因为是 `private`，**不能通过子类 override**。可行的修改路径只有两条：

- **路径 A（推荐）**：直接改 `src/main/java/com/agui/spring/ai/SpringAIAgent.java`（3-5 行）
- **路径 B**：完全不动拷贝代码，自己实现"per-request 上下文"（绕开 ThreadLocal 的话基本没可能；走 ThreadLocal 路线就是方案 B）

**"拷贝模型" 的标准 trade-off**：你可以随改、随用，但要自己承担和上游的同步维护。

---

## 9. 推荐修复方案：3 个完整代码 diff

### 9.1 改动 1：`SpringAIAgent.getChatRequest`（关键修复）

**文件**：`src/main/java/com/agui/spring/ai/SpringAIAgent.java`

> 注意：这里改的是**拷贝到本仓库的代码**（在 `src/main/java/com/agui/`），**不是** git 子模块 `ag-ui-4j/` 里的同名文件——后者 Maven 不编译，改了也无效。

**修改前**（约 238-247 行）：
```java
private ChatClient.ChatClientRequestSpec getChatRequest(RunAgentInput input, String content, String messageId, List<BaseEvent> deferredEvents, SystemMessage systemMessage, AgentSubscriber subscriber) throws AGUIException {
    ChatClient.ChatClientRequestSpec chatRequest = this.chatClient.prompt(
        Prompt
            .builder()
            .content(content)
            .build()
        )
        .system(systemMessage.getContent()
    );
    ...
}
```

**修改后**：
```java
private ChatClient.ChatClientRequestSpec getChatRequest(RunAgentInput input, String content, String messageId, List<BaseEvent> deferredEvents, SystemMessage systemMessage, AgentSubscriber subscriber) throws AGUIException {
    ChatClient.ChatClientRequestSpec chatRequest = this.chatClient.prompt(
        Prompt
            .builder()
            .content(content)
            .build()
        )
        .system(systemMessage.getContent()
    );

    // ★ 新增：把 AG-UI 协议层的 forwardedProps 桥接到 Spring AI 的 ToolContext
    // 这样 @Tool 注解方法可以通过 ToolContext 参数拿到 per-request 数据（如 JWT）
    Object forwardedProps = input.forwardedProps();
    if (forwardedProps instanceof Map<?, ?> props && !props.isEmpty()) {
        Map<String, Object> toolContext = new java.util.HashMap<>();
        for (Map.Entry<?, ?> entry : props.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                toolContext.put(entry.getKey().toString(), entry.getValue());
            }
        }
        chatRequest = chatRequest.toolContext(toolContext);
    }

    if (!this.tools.isEmpty()) {
        ...
    }
    ...
}
```

**解释**：
- `chatRequest.toolContext(...)` 可以在 `tools(...)` 之前或之后调用——**顺序不重要**。`DefaultChatClientUtils.toChatClientRequest` 内部判断是否创建 `DefaultToolCallingChatOptions` 的条件是：只要 `toolNames` / `toolCallbacks` / `toolCallbackProviders` / `toolContext` **任一**非空就创建。
- 但放在 `tools(...)` 之前更符合"先配置上下文再配置工具"的代码可读性惯例。
- 重新编译：直接 `mvn clean compile -DskipTests`（**不要**加 `-pl ag-ui-4j/...`，因为这部分代码就在主项目里）

### 9.2 改动 2：`SkillTools.httpRequest` 增加 `ToolContext` 参数

**文件**：`src/main/java/com/example/demo/agent/SkillTools.java`

**修改前**（71-80 行）：
```java
@Tool(description = "发送 HTTP 请求调用 REST API，并直接返回执行结果。支持 GET/POST/PUT/DELETE 所有方法。")
public String httpRequest(
    @ToolParam(description = "HTTP 方法：GET/POST/PUT/DELETE") String method,
    @ToolParam(description = "API 路径（相对路径会自动拼接 base URL）") String url,
    @ToolParam(description = "路径参数，用于替换 URL 中的占位符") Map<String, String> pathParams,
    @ToolParam(description = "查询参数") Map<String, String> queryParams,
    @ToolParam(description = "请求头") Map<String, String> headers,
    @ToolParam(description = "请求体（仅用于 POST/PUT）") Map<String, Object> body
) {
    return executeHttpRequest(method, url, pathParams, queryParams, headers, body);
}
```

**修改后**：
```java
@Tool(description = "发送 HTTP 请求调用 REST API，并直接返回执行结果。支持 GET/POST/PUT/DELETE 所有方法。")
public String httpRequest(
    @ToolParam(description = "HTTP 方法：GET/POST/PUT/DELETE") String method,
    @ToolParam(description = "API 路径（相对路径会自动拼接 base URL）") String url,
    @ToolParam(description = "路径参数，用于替换 URL 中的占位符") Map<String, String> pathParams,
    @ToolParam(description = "查询参数") Map<String, String> queryParams,
    @ToolParam(description = "请求头") Map<String, String> headers,
    @ToolParam(description = "请求体（仅用于 POST/PUT）") Map<String, Object> body,
    org.springframework.ai.chat.model.ToolContext toolContext   // ★ Spring AI 自动注入
) {
    return executeHttpRequest(method, url, pathParams, queryParams, headers, body, toolContext);
}
```

### 9.3 改动 3：`SkillTools.executeHttpRequest` 与 `extractJwt` 重构

**文件**：`src/main/java/com/example/demo/agent/SkillTools.java`

**修改前**（128-185 行 + 192-225 行）：
```java
private String executeHttpRequest(String method, String url, Map<String, String> pathParams,
                                 Map<String, String> queryParams, Map<String, String> headers,
                                 Map<String, Object> body) {
    try {
        String jwt = extractJwt();   // 旧逻辑：只从 SecurityContextHolder / UserContextHolder 取
        // ... 构造 HTTP 请求
    }
}

private String extractJwt() {
    // 方式 1: SecurityContextHolder
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && auth.getCredentials() instanceof String) {
        return (String) auth.getCredentials();
    }
    // 方式 2: UserContextHolder
    return UserContextHolder.getToken();
}
```

**修改后**：
```java
private String executeHttpRequest(String method, String url, Map<String, String> pathParams,
                                 Map<String, String> queryParams, Map<String, String> headers,
                                 Map<String, Object> body,
                                 org.springframework.ai.chat.model.ToolContext toolContext) {
    try {
        // 优先从 ToolContext 取（AG-UI 路径），其次 SecurityContextHolder（非 AG-UI 路径），
        // 最后 UserContextHolder（兜底）
        String jwt = extractJwt(toolContext);
        // ... 构造 HTTP 请求（与原代码一致）
    }
}

/**
 * 三级 JWT 获取机制：
 *  1) ToolContext  ←  AG-UI 路径的主路径（由 SpringAIAgent.getChatRequest 桥接）
 *  2) SecurityContextHolder  ←  非 AG-UI 路径的主路径
 *  3) UserContextHolder  ←  历史 ThreadLocal 兜底（保留兼容）
 */
private String extractJwt(org.springframework.ai.chat.model.ToolContext toolContext) {
    // 方式 1：从 ToolContext 提取（AG-UI 路径的主路径）
    if (toolContext != null && toolContext.getContext() != null) {
        Object jwt = toolContext.getContext().get("jwt");
        if (jwt instanceof String s && !s.isEmpty()) {
            log.debug("从 ToolContext 提取到 JWT");
            return s;
        }
    }

    // 方式 2：从 SecurityContextHolder 提取（非 AG-UI 路径）
    try {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getCredentials() instanceof String s && !s.isEmpty()) {
            log.debug("从 SecurityContext 提取到 JWT");
            return s;
        }
    } catch (Exception e) {
        log.debug("从 SecurityContext 提取 JWT 失败: {}", e.getMessage());
    }

    // 方式 3：从 UserContextHolder 提取（兜底）
    String token = UserContextHolder.getToken();
    if (token != null && !token.isEmpty()) {
        log.debug("从 UserContextHolder 提取到 JWT");
        return token;
    }

    log.warn("所有方式都无法获取 JWT token！");
    return null;
}
```

### 9.4 可选改动：`AgUiController.run` 已有 `setForwardedProps(...)` 即可

**文件**：`src/main/java/com/example/demo/controller/AgUiController.java`

**无需改动**——第 102-106 行已经在 setForwardedProps：

```java
// 现有代码已经做了
Map<String, Object> toolContext = new HashMap<>();
if (authHeader != null && authHeader.startsWith("Bearer ")) {
    toolContext.put("jwt", authHeader.substring(7));
}
agUiParameters.setForwardedProps(toolContext);
```

现在它**真的会生效**了，因为 `SpringAIAgent.getChatRequest` 被改了。

### 9.5 改动后的数据流

```
HTTP 请求
   │
   ▼
AgUiController.run  (HTTP 线程)
   │  authHeader = "Bearer xxx"
   │  agUiParameters.setForwardedProps({jwt: "xxx"})
   │
   ▼
agUiService.runAgent(...)  (HTTP → ForkJoinPool via CompletableFuture.runAsync)
   │
   ▼
[★ 改动 1] SpringAIAgent.getChatRequest  (ForkJoinPool 线程)
   │  input.forwardedProps() = {jwt: "xxx"}
   │  chatRequest.toolContext({jwt: "xxx"})   ← ★ 关键桥接
   │
   ▼
[Spring AI 内部]
DefaultChatClientUtils.toChatClientRequest
   │  inputRequest.getToolContext() = {jwt: "xxx"}  (非空!)
   │  → 触发懒创建：processedChatOptions = new DefaultToolCallingChatOptions()
   │  → toolContext 被合并到 options
   │
   ▼
Prompt (带 ToolCallingChatOptions{toolContext={jwt:"xxx"}})
   │
   ▼
OpenAiChatModel.stream
   │  LLM 决定调 httpRequest
   │  DefaultToolCallingManager.buildToolContext(prompt, ...)
   │    → 读 prompt.getOptions().getToolContext() = {jwt:"xxx"}
   │    → new ToolContext({jwt:"xxx", TOOL_CALL_HISTORY:...})
   │  MethodToolCallback.call(toolInput, toolContext)
   │    → buildMethodArguments(..., toolContext)
   │    → 检测到 ToolContext 参数 → 注入
   │
   ▼
[★ 改动 2+3] SkillTools.httpRequest  (boundedElastic 线程)
   │  形参 ToolContext toolContext 拿到 {jwt:"xxx"}
   │  extractJwt(toolContext)  →  "xxx"
   │  httpHeaders.setBearerAuth("xxx")
   │
   ▼
HTTP 请求到受保护后端 API（带 Authorization: Bearer xxx）  ✅ 200
```

### 9.6 验证步骤

```bash
# 1. 重编主项目（拷贝到 src/main/java/com/agui/ 的代码会一起编译）
cd /Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo
mvn clean compile -DskipTests

# 2. 跑测试
bash test-agui-jwt-full.sh
```

> **注意**：不要运行 `mvn -pl ag-ui-4j/...` 之类的命令——`ag-ui-4j/` 是 git 子模块（仅供查看），不在 Maven 模块图里。

预期：
- AG-UI 路径下，工具调用的 HTTP 请求会带 `Authorization: Bearer <jwt>`
- 之前返回 401/403 的请求现在返回 200
- 非 AG-UI 路径（`/api/chat/stream`）继续正常工作

---

## 10. 其他次选方案

### 10.1 方案 B：在 `LocalAgent.runAgent` 切线程前手动 set ThreadLocal

**优点**：完全不动 `SpringAIAgent`，改动最小
**缺点**：仍然依赖 ThreadLocal 黑魔法，AG-UI 一旦改用别的线程池可能再坏

**文件**：`src/main/java/com/agui/server/LocalAgent.java:131`

```java
public CompletableFuture<Void> runAgent(RunAgentParameters parameters, AgentSubscriber subscriber) {
    ...
    // 抓 forwardedProps
    Object forwardedProps = input.forwardedProps();
    CompletableFuture.runAsync(() -> {
        // 在 ForkJoinPool 线程里手动设 ThreadLocal
        if (forwardedProps instanceof Map<?, ?> p) {
            Object jwt = p.get("jwt");
            if (jwt instanceof String s) {
                UserContextHolder.setToken(s);
            }
            Object username = p.get("username");
            if (username instanceof String u) {
                UserContextHolder.setUsername(u);
            }
        }
        try {
            this.run(input, subscriber);
        } finally {
            // 必须清理！否则会泄漏到其他请求
            UserContextHolder.clear();
        }
    });
    return future;
}
```

**和方案 A 的对比**：
- 方案 A：**3-5 行 + JWT 跟随请求**（数据不依赖线程）
- 方案 B：**5-10 行 + JWT 跟随线程**（数据依赖线程）

**推荐方案 A**。方案 B 只在你**完全不能改拷贝代码**（且也不能改 `src/main/java/com/agui/`）时才考虑。

### 10.2 方案 C：子类化 `LocalAgent`/`SpringAIAgent`

不可行——`getChatRequest` 是 `private`。

如果非要做，只能 override `run` 方法（protected），把 `getChatRequest` 的逻辑复制一份。代码冗余且不优雅，**强烈不推荐**。

### 10.3 方案 D：服务级 Token 降级

放弃用户态 Token 透传，所有工具调用都用一个共享的 service account token。

**优点**：完全回避异步上下文问题
**缺点**：丢失"代表用户"的语义，工具能访问用户级数据但不能精细控制权限

**这是过去的"放弃方案"**。在了解了方案 A 后，已经没必要再走这条。

---

## 11. 测试验证方案

### 11.1 单元测试

在 `SkillTools` 上加 mock 测试：

```java
@SpringBootTest
class SkillToolsToolContextTest {

    @Autowired
    private SkillTools skillTools;

    @Test
    void testHttpRequestWithToolContextJwt() {
        var toolContext = new org.springframework.ai.chat.model.ToolContext(Map.of("jwt", "test-jwt-123"));

        String result = skillTools.httpRequest(
            "GET", "/api/products", null, null, null, null, toolContext
        );

        // 期望：tool 内部使用了 test-jwt-123（可以通过 mock RestTemplate 验证）
        // 这里简化为断言不抛异常
        assertNotNull(result);
    }

    @Test
    void testHttpRequestWithoutAnyJwtFails() {
        // 不传 ToolContext，SecurityContextHolder 和 UserContextHolder 都为空
        // 期望：HTTP 请求不带 Authorization，后端返回 401（但工具本身不抛异常）
        String result = skillTools.httpRequest(
            "GET", "/api/products", null, null, null, null, null
        );
        // 401/403 的 body 也是合法 string
        assertNotNull(result);
    }
}
```

### 11.2 集成测试

复用 `test-agui-jwt-full.sh`：

```bash
#!/bin/bash
# 1. 用 user1 的 token 加一个商品
TOKEN=$(echo -n "user1:password1" | base64)
curl -X POST "http://localhost:8080/api/products/cart?productId=1" \
    -H "Authorization: Bearer $TOKEN"

# 2. 通过 AG-UI 端点查询购物车
AGUI_REQUEST='{"messages":[{"role":"user","content":"查询我的购物车"}]}'
curl --compressed -X POST "http://localhost:8080/api/agui" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "$AGUI_REQUEST"

# 3. 期望：响应里包含购物车的商品数据（非 401/403）
```

### 11.3 关键日志埋点（可选）

在 `SkillTools.executeHttpRequest` 里加：

```java
log.info("[JWT] extract source={}, thread={}, jwtPresent={}",
    source,   // "ToolContext" | "SecurityContext" | "UserContextHolder" | "null"
    Thread.currentThread().getName(),
    jwt != null);
```

调试时可以看清楚 JWT 是从哪条路径来的。

---

## 12. 总结

### 12.1 之前的"放弃"为什么错了

| 之前的判断 | 实际情况 |
|------|------|
| "Reactor Hook 不可靠" | 不可靠的不是 Hook，是**Hook 触发点的"当前线程"已经不是 HTTP 线程了** |
| "AG-UI 不支持用户态 Token 透传" | ag-ui-4j 协议层**有** `forwardedProps` 通道，但 `SpringAIAgent` 没用它——**集成缺口** |
| "boundedElastic 线程由框架控制" | 框架控制的是 boundedElastic，但**第一次切线程（HTTP→ForkJoinPool）**才是关键，且**是 ag-ui-4j 自己切的** |
| "Java 17 虚拟线程兼容" | 虚拟线程不解决 ForkJoinPool 的 worker 预创建问题 |
| "需要用服务级 Token 降级" | 没必要——只需 3-5 行 `SpringAIAgent.java` 拷贝源码修改 |

### 12.2 真正可行的修复

**首选方案 A**（推荐）：在 `src/main/java/com/agui/spring/ai/SpringAIAgent.java` 的 `getChatRequest` 里加 3-5 行代码，把 `input.forwardedProps()` 桥接到 `chatRequest.toolContext(...)`。同时 `SkillTools.httpRequest` 增加 `ToolContext` 形参 + `extractJwt` 重构。

**次选方案 B**（仅当不能改拷贝代码）：在 `src/main/java/com/agui/server/LocalAgent.java` 的 `runAsync` lambda 里手动 set `UserContextHolder`。

### 12.3 修复时间线

| 步骤 | 工作量 | 风险 |
|------|------|------|
| 1. 改 `src/main/java/com/agui/spring/ai/SpringAIAgent.java` 源码 | 5 分钟 | 极低（3-5 行） |
| 2. 改 `SkillTools.httpRequest` 形参 | 2 分钟 | 极低 |
| 3. 改 `SkillTools.executeHttpRequest` 内部 + `extractJwt` | 10 分钟 | 低（多了一个参数） |
| 4. 重编主项目 | 3 分钟 | 极低（`mvn clean compile`） |
| 5. 跑 `test-agui-jwt-full.sh` | 2 分钟 | — |
| **总计** | **~25 分钟** | **极低** |

### 12.4 长期建议

可以向 ag-ui-4j 上游提一个 PR：
- 在 `SpringAIAgent.getChatRequest` 里把 `forwardedProps` 自动桥接到 `toolContext`
- 这正是协议层 `forwardedProps` 字段被设计出来的目的

如果上游接受，主项目就可以**重新同步 `src/main/java/com/agui/` 的拷贝**（用上游的 `forwardedProps` 桥接版本），把本地的 patch 替换掉。

**未来同步上游的注意点**：
- 上游版本的 `forwardedProps` 桥接可能和本仓库的 patch 写法不完全一样——可能需要小范围手动 merge。
- 只要在 `src/main/java/com/agui/` 里保留一份"和上游相同 + 本地桥接"的代码，整个 fix 仍然有效。

---

## 附录 A：完整修改文件清单

| # | 文件 | 类型 | 行数 |
|---|------|------|------|
| 1 | `src/main/java/com/agui/spring/ai/SpringAIAgent.java`（拷贝自 ag-ui-4j） | 修改 | +12 行 |
| 2 | `src/main/java/com/example/demo/agent/SkillTools.java` | 修改 | +20 行（含 javadoc） |
| 3 | `src/main/java/com/example/demo/controller/AgUiController.java` | 无需改动（forwardedProps 已有） | 0 |
| 4 | `src/main/java/com/example/demo/config/AgUiConfig.java` | 无需改动 | 0 |
| 5 | `src/main/java/com/example/demo/config/ReactorBoundedElasticHookConfig.java` | 可保留（兜底） | 0 |

> **不要**修改 `ag-ui-4j/integrations/spring-ai/src/main/java/com/agui/spring/ai/SpringAIAgent.java`——那个是 git 子模块里的同名文件，不在 Maven classpath 里，改了也无效。

## 附录 B：未来可清理的"妥协代码"

修复完成后，以下代码可以逐步移除（但建议保留一段时间作为兜底）：

1. `ReactorBoundedElasticHookConfig` —— 一旦 `ToolContext` 路径稳定，可移除
2. `SecurityContextHolder` 在 `AgUiController` 里的双重设置（行 81-93）—— 不再需要
3. `SkillTools.extractJwt` 里的 `UserContextHolder` 兜底 —— 不再需要
4. `SkillTools` 和 `SkillsAdvisor` 构造函数上方注释 "AG-UI + SSE + Spring AI 场景不支持用户态 Token 透传"（两处）—— **可以删掉这句误导性注释**

---

## 附录 C：拷贝同步策略（重要！避免后续踩坑）

### C.1 现状盘点

| 位置 | 内容 | 是否参与编译 |
|------|------|----------|
| `src/main/java/com/agui/` | 拷贝自 ag-ui-4j 上游的 Java 源码 | ✅ 是（Maven 编译它） |
| `ag-ui-4j/`（git 子模块） | 上游仓库的完整克隆（monorepo，多模块） | ❌ 否（仅作"查看上游"参考） |
| `mvn -pl ag-ui-4j/...` | 想编译子模块的尝试 | ❌ 失败（不在 Maven 模块图里） |

**本地拷贝与上游目录的对应关系**（已 byte-identical 验证，共 62 个文件）：

| 本地路径 | 上游 submodule 路径 |
|------|------|
| `src/main/java/com/agui/spring/ai/` | `ag-ui-4j/integrations/spring-ai/src/main/java/com/agui/spring/ai/` |
| `src/main/java/com/agui/core/` | `ag-ui-4j/packages/core/src/main/java/com/agui/core/` |
| `src/main/java/com/agui/server/`（不含 spring 子目录） | `ag-ui-4j/packages/server/src/main/java/com/agui/server/` |
| `src/main/java/com/agui/server/spring/` | `ag-ui-4j/servers/spring/src/main/java/com/agui/server/spring/` |
| `src/main/java/com/agui/json/` | `ag-ui-4j/utils/json/src/main/java/com/agui/json/` |

> 备注：本地 `src/main/java/com/agui/integrations/` 目录存在但为空（从更早的 copy 布局遗留），实际没有文件。

### C.2 修改代码的正确位置

✅ **改 `src/main/java/com/agui/...`**——Maven 会自动编译、IDE 也会索引。
❌ **改 `ag-ui-4j/...`**——Maven 不编译它，IDE 也只把它当"附加目录"。

### C.3 上游同步时的操作

当上游 ag-ui-4j 有更新时：

```bash
# 1. 同步 git 子模块
cd /Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo
git submodule update --remote ag-ui-4j

# 2. 对比上游版本和本仓库拷贝
#    注意：上游 ag-ui-4j 是 monorepo，本仓库的 src/main/java/com/agui/ 拷贝
#    自 5 个 upstream 目录，需要逐个 diff（不要漏）：
diff -ru ag-ui-4j/integrations/spring-ai/src/main/java/com/agui/ src/main/java/com/agui/spring/ai/
diff -ru ag-ui-4j/packages/core/src/main/java/com/agui/             src/main/java/com/agui/core/
diff -ru ag-ui-4j/packages/server/src/main/java/com/agui/           src/main/java/com/agui/server/
diff -ru ag-ui-4j/servers/spring/src/main/java/com/agui/            src/main/java/com/agui/server/spring/
diff -ru ag-ui-4j/utils/json/src/main/java/com/agui/                src/main/java/com/agui/json/

# 3. 手动把上游的修改同步到 src/main/java/com/agui/
#    注意：本仓库在 SpringAIAgent.getChatRequest 里加了 forwardedProps 桥接，
#    同步时要保留本仓库的改动，或者上游已经实现桥接就把本地 patch 删掉。

# 4. 编译并测试
mvn clean compile -DskipTests
bash test-agui-jwt-full.sh
```

### C.4 风险与缓解

| 风险 | 缓解策略 |
|------|------|
| 上游重命名/移动类 | `src/main/java/com/agui/` 仍然引用旧路径 → 编译失败。同步时优先关注 `LocalAgent`、`SpringAIAgent`、`AgUiService` 这几个文件 |
| 上游修改了 `forwardedProps` 的语义 | 我们的 patch 仍然兼容（只是"复制到 toolContext"），但字段含义变化时需要重新审视 |
| 上游自己实现了 `forwardedProps` 桥接 | 我们的本地 patch 变成"重复代码"——同步时删掉本地 patch，保留上游版本 |
| 子模块和拷贝 drift 太大 | 用 `diff -ru` 工具定期检查；考虑写一个 `scripts/sync-agui.sh` 自动化 |

### C.5 怎么判断当前拷贝是否最新

```bash
# 在 ag-ui-4j/ 子模块里看最新 commit
cd ag-ui-4j
git log -1 --oneline

# 回到主仓库，对比子模块的 commit 和上次拷贝的 commit
cd ..
git diff HEAD -- src/main/java/com/agui/ | head -50
```

> 如果子模块 HEAD 后的 `git diff` 显示 "改动来自 `ag-ui-4j/` 但未同步到 `src/main/java/com/agui/`"，说明拷贝落后了。

### C.6 长期建议：把 `ag-ui-4j/` 真的 vendor 进来

如果嫌手动同步麻烦，可以考虑：

1. **方案 1**：把 `ag-ui-4j/` 通过 `mavenLocal` 真正 vendor 进来——子模块打 jar → `mvn install -pl ag-ui-4j/...` → 主项目依赖。
   - 优点：自动同步上游（`mvn install` 一次就好）
   - 缺点：Maven 多模块配置要重新写；不能直接改 vendor 源码

2. **方案 2**：直接用 Maven `maven-shade-plugin` 把 ag-ui-4j 的代码**打成一个 jar**，放到 `lib/` 下。
   - 优点：彻底解耦
   - 缺点：升级仍然要重新打

3. **方案 3（当前）**：保持现状，文档化同步流程。
   - 优点：改动响应最快
   - 缺点：手动同步

对于本项目"想加 `forwardedProps` 桥接"这个小需求，**方案 3 最划算**。

---

> 报告人：Claude
> 协作模式：探索 + 诊断 + 修复建议
> 重要原则：所有代码改动都已列出完整 diff，不依赖任何未在仓库里的假设
> 文档版本：v6（2026-06-01）—— 修正了"代码归属"和"拷贝模型"的关键描述；补全 forwardedProps 透传路径的完整证据；修正 Section 3.2 与 3.1 之间的描述一致性；修正 Spring AI ChatClientRequest 内部 record 的方法名（copy() vs mutate()）；并修正附录 C 关于本地拷贝与上游多目录对应关系的描述
