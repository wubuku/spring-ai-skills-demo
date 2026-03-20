# RAG 知识库问答功能规划

**目标**: 用最小改动实现基于知识库的客服问答

---

## 一、方案概述

复用 Spring AI 内置的 `QuestionAnswerAdvisor`：
- 启动时加载 Markdown 文档到 VectorStore
- `QuestionAnswerAdvisor` 自动处理检索和 Prompt 注入

---

## 二、实现

### 2.1 新增文件

**1. 知识库初始化组件** `src/main/java/com/example/demo/knowledge/KnowledgeBaseInitializer.java`

**2. 知识库文档**：
- `src/main/resources/knowledge-base/return-policy.md`
- `src/main/resources/knowledge-base/delivery-info.md`
- `src/main/resources/knowledge-base/payment-methods.md`

### 2.2 修改 AgentService

在 `AgentService` 构造函数中添加 `QuestionAnswerAdvisor`：

```java
QuestionAnswerAdvisor questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
    .build();

this.chatClient = builder
    .defaultAdvisors(
        skillsAdvisor,
        MessageChatMemoryAdvisor.builder(chatMemory).build(),
        vectorStoreChatMemoryAdvisor,
        questionAnswerAdvisor
    )
    .defaultTools(skillTools)
    .build();
```

---

## 三、测试报告

### 3.1 测试结果（2026-03-20）

| 测试项 | 结果 | 说明 |
|--------|------|------|
| 退货政策问答 | ✅ 通过 | 正确回答 7 天退货期限、条件等 |
| 配送说明问答 | ✅ 通过 | 正确回答 24 小时发货、快递方式等 |
| 支付方式问答 | ✅ 通过 | 正确回答支付宝、微信支付等 |
| 商品搜索功能 | ✅ 通过 | Skills 系统仍正常工作 |
| 会话记忆 | ✅ 通过 | 能记住用户名字 |

### 3.2 测试命令

```bash
# 退货政策
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"你们的退货政策是什么？"}'

# 配送
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"下单后多久能收到货？"}'

# 支付方式
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"支持哪些支付方式？"}'
```

---

## 四、文件清单

| 操作 | 文件 |
|------|------|
| 新增 | `src/main/java/com/example/demo/knowledge/KnowledgeBaseInitializer.java` |
| 新增 | `src/main/resources/knowledge-base/return-policy.md` |
| 新增 | `src/main/resources/knowledge-base/delivery-info.md` |
| 新增 | `src/main/resources/knowledge-base/payment-methods.md` |
| 新增 | `test-rag-knowledge-base.sh` |
| 修改 | `src/main/java/com/example/demo/service/AgentService.java` |

---

## 五、修改历史

| 日期 | 版本 | 修改内容 |
|------|------|---------|
| 2026-03-20 | v1.0 | 初始规划，使用 `SearchRequest.defaults()` |
| 2026-03-20 | v2.0 | 修复：移除 `SearchRequest.defaults()`，直接使用 `QuestionAnswerAdvisor.builder(vectorStore).build()` |
| 2026-03-20 | v2.1 | 完成测试，添加测试报告 |
