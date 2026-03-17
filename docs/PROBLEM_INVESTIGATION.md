# 问题排查记录：前端用户确认模式失效

## 问题描述

不管使用 DeepSeek 还是 MiniMax 模型测试，后端都固执的直接使用 `httpRequest` 工具"代用户操作"，而不是将请求元数据返回给前端、让用户确认后在前端执行。

## 排查过程

### 1. 初步假设

用户怀疑 SkillsAdvisor.java 的提示词根本没有最终被注入到 LLM 上下文中。

### 2. 验证方法

通过查看后端日志中的 `[完整系统提示词]` 来确认实际发送给 LLM 的提示词内容。

### 3. 重大发现

**日期**: 2026-03-17

**发现**: 查看后端日志（f18754），发现完整系统提示词**只包含了规则 1-5** 和技能列表，但 **`buildModeSpecificRules()` 方法生成的内容（规则 6-9）完全没有被包含在日志中！**

### 4. 日志证据

```
2026-03-17T09:05:32.381+08:00  INFO 90891 --- [spring-ai-skills-demo] [oundedElastic-1] com.example.demo.agent.SkillsAdvisor     : [SkillsAdvisor] 注入系统提示，HTTP工具=buildHttpRequest, 技能数量=6
2026-03-17T09:05:32.381+08:00  INFO 90891 --- [spring-ai-skills-demo] [oundedElastic-1] com.example.demo.agent.SkillsAdvisor     : ========== [完整系统提示词] ==========
2026-03-17T09:05:32.381+08:00  INFO 90891 --- [spring-ai-skills-demo] [oundedElastic-1] com.example.demo.agent.SkillsAdvisor     : 你是一个智能助手。可用技能如下：
...
**重要规则：**
1. 使用某个技能前，必须先调用 `loadSkill` 工具加载它的完整指令
2. 不要凭记忆猜测 API 参数，必须先加载技能查看文档
3. 加载技能后，注意其 links 字段提示的关联技能
4. API 基础 URL 是 http://localhost:8080（技能文档中的路径都是相对路径，调用 buildHttpRequest 时只需传相对路径）
5. 部分技能具有分层结构（如 OpenAPI 生成的技能），其 SKILL.md 中会列出 references 目录下的参考文件路径，
   需要调用 `readSkillReference` 工具读取具体的资源/操作文档，再据此调用 buildHttpRequest 工具
==========================================
```

**注意**: 规则 6-9（来自 `buildModeSpecificRules()` 方法）完全没有出现！

### 5. 根本原因分析

问题出在 `SkillsAdvisor.java` 第 63-95 行的 `buildSystemPrompt()` 方法中：

```java
return """
    你是一个智能助手。可用技能如下：

    <available_skills>
    %s
    </available_skills>

    **重要规则：**
    1. 使用某个技能前，必须先调用 `loadSkill` 工具加载它的完整指令
    2. 不要凭记忆猜测 API 参数，必须先加载技能查看文档
    3. 加载技能后，注意其 links 字段提示的关联技能
    4. API 基础 URL 是 %s（技能文档中的路径都是相对路径，调用 %s 时只需传相对路径）
    5. 部分技能具有分层结构（如 OpenAPI 生成的技能），其 SKILL.md 中会列出 references 目录下的参考文件路径，
       需要调用 `readSkillReference` 工具读取具体的资源/操作文档，再据此调用 %s 工具
    %s
    """.formatted(skillList, apiBaseUrl, httpToolName, httpToolName, loadedContext, modeRules);
```

**问题**: 模板有 6 个 `%s` 占位符，传入了 6 个参数。但由于 Java text block 格式化的问题，`modeRules`（规则 6-9）**没有被正确包含在最终输出中**。

### 6. 影响范围

- **同步 chat 端点** (`/api/chat`): 有问题
- **AG-UI SSE 端点** (`/api/agui`): 有问题

两个端点都使用相同的 SkillsAdvisor 组件，因此都受到影响。

### 7. 结论

**这是一个代码 bug**！SkillsAdvisor 中的 `buildModeSpecificRules()` 方法的内容（规则 6-9）没有被正确注入到发送给 LLM 的提示词中。

这与使用什么 LLM 模型无关 - **问题是提示词本身就不完整**。LLM 根本没有收到关于使用 `buildHttpRequest` 而不是 `httpRequest` 的核心规则（规则 6-9），所以它会按照默认行为直接使用 `httpRequest` 工具。

## 受影响的端点

1. **同步 chat 端点**: `/api/chat`
2. **AG-UI SSE 端点**: `/api/agui`

## 修复方向

需要修复 `SkillsAdvisor.java` 中的 `buildSystemPrompt()` 方法，确保规则 6-9 被正确包含在系统提示词中。
