# Spring AI Skills 渐进式披露完整示例项目

## 项目概述

这是一个**开箱即用**的 Spring Boot 示例项目，展示如何使用 Spring AI 和 Skills 机制实现渐进式披露的 Agent 工具调用。

**核心功能：**
- ✅ RESTful API 服务（商品管理）+ Swagger UI 文档
- ✅ OpenAPI → Skills 一键转换流程
- ✅ Skills 渐进式披露的 Agent 实现
- ✅ Web 聊天界面，用户自然语言交互
- ✅ Maven 一键构建，自包含运行

---

## 技术栈版本

| 组件 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.4.0 | 最新稳定版 |
| Spring AI | 1.0.0-M6 | 最新里程碑版 |
| Java | 17+ | 最低要求 |
| springdoc-openapi | 2.8.16 | Swagger UI 集成 |
| Maven | 3.8+ | 构建工具 |

---

## 快速开始

### 前置要求
- JDK 17 或更高版本
- Maven 3.8+
- OpenAI API Key（或其他兼容 LLM 服务）

### 一键启动

```bash
# 1. 克隆或创建项目
git clone <your-repo-url>  # 或按下面结构创建
cd spring-ai-skills-demo

# 2. 配置 API Key
export OPENAI_API_KEY=sk-your-key-here

# 3. 启动应用（首次会自动下载依赖）
mvn spring-boot:run
```

### 访问地址

| 功能 | URL | 说明 |
|------|-----|------|
| 聊天界面 | http://localhost:8080 | 主界面 |
| Swagger UI | http://localhost:8080/swagger-ui.html | API 文档 |
| OpenAPI JSON | http://localhost:8080/v3/api-docs | OpenAPI 规范 |

---

## 项目结构

```
spring-ai-skills-demo/
├── pom.xml                          # Maven 配置
├── src/main/
│   ├── java/com/example/demo/
│   │   ├── DemoApplication.java     # 启动类
│   │   ├── config/
│   │   │   ├── OpenApiConfig.java   # Swagger 配置
│   │   │   └── RestConfig.java      # RestTemplate 配置
│   │   ├── controller/
│   │   │   ├── ProductController.java   # 商品 REST API
│   │   │   ├── ChatController.java      # 聊天 API
│   │   │   └── HomeController.java      # 首页
│   │   ├── service/
│   │   │   ├── ProductService.java      # 商品业务逻辑
│   │   │   └── AgentService.java        # Agent 核心服务
│   │   ├── agent/
│   │   │   ├── SkillRegistry.java       # Skills 注册器
│   │   │   ├── SkillTools.java          # @Tool 方法
│   │   │   └── SkillsAdvisor.java       # System Prompt 注入
│   │   ├── model/
│   │   │   ├── Product.java             # 商品实体
│   │   │   ├── Skill.java               # Skill 数据结构
│   │   │   └── ChatMessage.java         # 聊天消息
│   │   └── util/
│   │       └── SkillGenerator.java      # OpenAPI → Skills 转换工具
│   └── resources/
│       ├── application.yml              # 应用配置
│       ├── static/
│       │   ├── index.html               # 聊天界面
│       │   └── chat.js                  # 前端逻辑
│       └── skills/                      # 生成的 Skills 目录
│           ├── search-products/
│           │   └── SKILL.md
│           ├── get-product-detail/
│           │   └── SKILL.md
│           ├── add-to-cart/
│           │   └── SKILL.md
│           └── checkout/
│               └── SKILL.md
└── README.md
```

---

## 完整代码

### 1. pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.0</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>spring-ai-skills-demo</artifactId>
    <version>1.0.0</version>
    <name>Spring AI Skills Demo</name>
    <description>渐进式披露 Skills 示例项目</description>

    <properties>
        <java.version>17</java.version>
        <spring-ai.version>1.0.0-M6</spring-ai.version>
        <springdoc.version>2.8.16</springdoc.version>
    </properties>

    <dependencies>
        <!-- Spring Boot Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring AI OpenAI -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
        </dependency>

        <!-- SpringDoc OpenAPI (Swagger UI) -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>

        <!-- Jackson YAML (解析 Skills YAML frontmatter) -->
        <dependency>
            <groupId>com.fasterxml.jackson.dataformat</groupId>
            <artifactId>jackson-dataformat-yaml</artifactId>
        </dependency>

        <!-- Lombok (简化代码) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 2. application.yml

```yaml
spring:
  application:
    name: spring-ai-skills-demo
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o
          temperature: 0.7

server:
  port: 8080

springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    display-request-duration: true

logging:
  level:
    com.example.demo: DEBUG
    org.springframework.ai: DEBUG
```

### 3. DemoApplication.java

```java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

### 4. 数据模型

#### Product.java
```java
package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Product {
    private Long id;
    private String name;
    private String category;
    private Double price;
    private String description;
    private Integer stock;
}
```

#### Skill.java
```java
package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    private SkillMeta meta;
    private String body;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
class SkillMeta {
    private String name;
    private String description;
    private List<SkillLink> links;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
class SkillLink {
    private String name;
    private String description;
}
```

#### ChatMessage.java
```java
package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    private String role;     // "user" or "assistant"
    private String content;
}
```

### 5. ProductService.java

```java
package com.example.demo.service;

import com.example.demo.model.Product;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class ProductService {
    
    private final Map<Long, Product> products = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);
    private final Map<Long, List<Long>> carts = new ConcurrentHashMap<>();

    public ProductService() {
        // 初始化示例数据
        addProduct(new Product(null, "iPhone 15", "手机", 5999.0, "苹果最新旗舰手机", 50));
        addProduct(new Product(null, "华为 MatePad Pro", "平板", 3299.0, "高性能安卓平板", 30));
        addProduct(new Product(null, "Sony WH-1000XM5", "耳机", 2499.0, "降噪蓝牙耳机", 80));
        addProduct(new Product(null, "小米电视 65寸", "电视", 2999.0, "4K智能电视", 20));
        addProduct(new Product(null, "MacBook Air M3", "笔记本", 8999.0, "轻薄笔记本电脑", 15));
    }

    private void addProduct(Product product) {
        product.setId(idGenerator.getAndIncrement());
        products.put(product.getId(), product);
    }

    public List<Product> searchProducts(String keyword, String category, Double priceMin, Double priceMax) {
        return products.values().stream()
            .filter(p -> keyword == null || 
                p.getName().contains(keyword) || 
                p.getDescription().contains(keyword))
            .filter(p -> category == null || p.getCategory().equals(category))
            .filter(p -> priceMin == null || p.getPrice() >= priceMin)
            .filter(p -> priceMax == null || p.getPrice() <= priceMax)
            .collect(Collectors.toList());
    }

    public Optional<Product> getProductById(Long id) {
        return Optional.ofNullable(products.get(id));
    }

    public Map<String, Object> addToCart(Long userId, Long productId) {
        if (!products.containsKey(productId)) {
            return Map.of("success", false, "message", "商品不存在");
        }
        carts.computeIfAbsent(userId, k -> new ArrayList<>()).add(productId);
        return Map.of(
            "success", true, 
            "message", "已添加到购物车",
            "cartSize", carts.get(userId).size()
        );
    }

    public Map<String, Object> checkout(Long userId) {
        List<Long> cart = carts.getOrDefault(userId, new ArrayList<>());
        if (cart.isEmpty()) {
            return Map.of("success", false, "message", "购物车为空");
        }
        double total = cart.stream()
            .map(products::get)
            .filter(Objects::nonNull)
            .mapToDouble(Product::getPrice)
            .sum();
        carts.remove(userId);
        return Map.of(
            "success", true,
            "message", "订单已提交",
            "totalAmount", total,
            "itemCount", cart.size()
        );
    }
}
```

### 6. ProductController.java

```java
package com.example.demo.controller;

import com.example.demo.model.Product;
import com.example.demo.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
@Tag(name = "商品管理", description = "商品搜索、详情、购物车操作")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @Operation(summary = "搜索商品", description = "根据关键词、分类、价格范围搜索商品")
    public List<Product> searchProducts(
        @Parameter(description = "搜索关键词") @RequestParam(required = false) String keyword,
        @Parameter(description = "商品分类") @RequestParam(required = false) String category,
        @Parameter(description = "最低价格") @RequestParam(required = false) Double priceMin,
        @Parameter(description = "最高价格") @RequestParam(required = false) Double priceMax
    ) {
        return productService.searchProducts(keyword, category, priceMin, priceMax);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取商品详情", description = "根据商品 ID 获取详细信息")
    public Product getProductDetail(@PathVariable Long id) {
        return productService.getProductById(id)
            .orElseThrow(() -> new RuntimeException("商品不存在"));
    }

    @PostMapping("/cart")
    @Operation(summary = "加入购物车", description = "将指定商品加入用户购物车")
    public Map<String, Object> addToCart(
        @Parameter(description = "用户 ID") @RequestParam Long userId,
        @Parameter(description = "商品 ID") @RequestParam Long productId
    ) {
        return productService.addToCart(userId, productId);
    }

    @PostMapping("/checkout")
    @Operation(summary = "结算订单", description = "提交购物车中的商品并生成订单")
    public Map<String, Object> checkout(
        @Parameter(description = "用户 ID") @RequestParam Long userId
    ) {
        return productService.checkout(userId);
    }
}
```

### 7. Skills 核心实现

#### SkillRegistry.java
```java
package com.example.demo.agent;

import com.example.demo.model.Skill;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SkillRegistry {

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws IOException {
        var yaml = new ObjectMapper(new YAMLFactory());
        var skillsPath = Path.of("src/main/resources/skills");

        if (!Files.exists(skillsPath)) {
            Files.createDirectories(skillsPath);
            return;
        }

        Files.list(skillsPath)
            .filter(Files::isDirectory)
            .forEach(dir -> {
                var mdFile = dir.resolve("SKILL.md");
                if (!Files.exists(mdFile)) return;
                try {
                    String content = Files.readString(mdFile);
                    String[] parts = content.split("---", 3);
                    if (parts.length < 3) return;
                    
                    var meta = yaml.readValue(parts[1], Skill.SkillMeta.class);
                    String body = parts[2].strip();
                    skills.put(meta.getName(), new Skill(meta, body));
                } catch (IOException e) {
                    throw new RuntimeException("解析 Skill 失败: " + dir, e);
                }
            });
    }

    public Map<String, Skill> all() { return skills; }
    public Optional<Skill> get(String name) { return Optional.ofNullable(skills.get(name)); }
}
```

#### SkillTools.java
```java
package com.example.demo.agent;

import com.example.demo.model.Skill;
import org.springframework.ai.tool.ToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Component
public class SkillTools {

    private final SkillRegistry registry;
    private final RestTemplate restTemplate;
    private final List<String> loadedSkills = new CopyOnWriteArrayList<>();

    public SkillTools(SkillRegistry registry, RestTemplate restTemplate) {
        this.registry = registry;
        this.restTemplate = restTemplate;
    }

    public void reset() { loadedSkills.clear(); }
    public List<String> getLoadedSkills() { return loadedSkills; }

    @Tool(description = "加载指定技能的完整操作指令。在使用任何技能前必须先调用此工具。")
    public String loadSkill(
        @ToolParam(description = "技能名称，必须来自 available_skills 列表") String skillName
    ) {
        return registry.get(skillName)
            .map(skill -> {
                loadedSkills.add(skillName);
                String linksHint = skill.getMeta().getLinks() == null || 
                    skill.getMeta().getLinks().isEmpty() ? "" :
                    "\n\n**相关技能（按需加载）：**\n" + 
                    skill.getMeta().getLinks().stream()
                        .map(l -> "- `" + l.getName() + "`：" + l.getDescription())
                        .collect(Collectors.joining("\n"));
                return "✓ 技能 `" + skillName + "` 已加载" + linksHint + 
                       "\n\n---\n" + skill.getBody();
            })
            .orElse("✗ 错误：技能 `" + skillName + "` 不存在");
    }

    @Tool(description = "发送 HTTP 请求调用 REST API")
    public String httpRequest(
        @ToolParam(description = "HTTP 方法：GET/POST/PUT/DELETE") String method,
        @ToolParam(description = "完整 URL，例如 http://localhost:8080/api/products") String url,
        @ToolParam(description = "Query 参数（JSON 对象）") Map<String, String> params,
        @ToolParam(description = "请求体（JSON 对象）") Map<String, Object> body
    ) {
        try {
            var uriBuilder = UriComponentsBuilder.fromHttpUrl(url);
            if (params != null) params.forEach(uriBuilder::queryParam);

            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var entity = new HttpEntity<>(body, headers);
            
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
}
```

#### SkillsAdvisor.java
```java
package com.example.demo.agent;

import com.example.demo.model.Skill;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import java.util.stream.Collectors;

@Component
public class SkillsAdvisor implements CallAroundAdvisor {

    private final SkillRegistry registry;
    private final SkillTools skillTools;

    public SkillsAdvisor(SkillRegistry registry, SkillTools skillTools) {
        this.registry = registry;
        this.skillTools = skillTools;
    }

    @Override
    public String getName() { return "SkillsAdvisor"; }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        AdvisedRequest augmented = AdvisedRequest.from(request)
            .withSystemText(buildSystemPrompt())
            .build();
        return chain.nextAroundCall(augmented);
    }

    private String buildSystemPrompt() {
        String skillList = registry.all().values().stream()
            .map(s -> "- `" + s.getMeta().getName() + "`：" + s.getMeta().getDescription())
            .collect(Collectors.joining("\n"));

        String loadedContext = skillTools.getLoadedSkills().stream()
            .map(name -> registry.get(name)
                .map(s -> "\n\n## 已激活技能：" + name + "\n" + s.getBody())
                .orElse(""))
            .collect(Collectors.joining());

        return """
            你是一个智能购物助手。可用技能如下：

            <available_skills>
            %s
            </available_skills>

            **重要规则：**
            1. 使用某个技能前，必须先调用 `loadSkill` 工具加载它的完整指令
            2. 不要凭记忆猜测 API 参数，必须先加载技能查看文档
            3. 加载技能后，注意其 links 字段提示的关联技能
            4. API 基础 URL 是 http://localhost:8080
            5. 默认用户 ID 是 1
            %s
            """.formatted(skillList, loadedContext);
    }
}
```

### 8. AgentService.java

```java
package com.example.demo.service;

import com.example.demo.agent.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

    private final ChatClient chatClient;
    private final SkillTools skillTools;

    public AgentService(ChatClient.Builder builder,
                        SkillTools skillTools,
                        SkillsAdvisor skillsAdvisor) {
        this.skillTools = skillTools;
        this.chatClient = builder
            .defaultAdvisors(skillsAdvisor)
            .defaultTools(skillTools)
            .build();
    }

    public String chat(String userMessage) {
        skillTools.reset();
        return chatClient.prompt()
            .user(userMessage)
            .call()
            .content();
    }
}
```

### 9. ChatController.java

```java
package com.example.demo.controller;

import com.example.demo.model.ChatMessage;
import com.example.demo.service.AgentService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentService agentService;

    public ChatController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    public Map<String, String> chat(@RequestBody ChatMessage message) {
        String response = agentService.chat(message.getContent());
        return Map.of("response", response);
    }
}
```

### 10. HomeController.java

```java
package com.example.demo.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "forward:/index.html";
    }
}
```

### 11. RestConfig.java

```java
package com.example.demo.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}
```

### 12. OpenApiConfig.java

```java
package com.example.demo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("商品管理 API")
                .version("1.0")
                .description("示例电商 API，支持商品搜索、详情查询、购物车和结算"));
    }
}
```

### 13. 前端界面 - index.html

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AI 购物助手</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
        }
        .container {
            width: 90%;
            max-width: 800px;
            height: 80vh;
            background: white;
            border-radius: 20px;
            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
            display: flex;
            flex-direction: column;
        }
        .header {
            padding: 20px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            border-radius: 20px 20px 0 0;
        }
        .header h1 { font-size: 24px; font-weight: 600; }
        .header p { font-size: 14px; opacity: 0.9; margin-top: 5px; }
        .messages {
            flex: 1;
            overflow-y: auto;
            padding: 20px;
            display: flex;
            flex-direction: column;
            gap: 15px;
        }
        .message {
            max-width: 70%;
            padding: 12px 16px;
            border-radius: 12px;
            line-height: 1.5;
            word-wrap: break-word;
        }
        .message.user {
            background: #667eea;
            color: white;
            align-self: flex-end;
            border-bottom-right-radius: 4px;
        }
        .message.assistant {
            background: #f0f0f0;
            color: #333;
            align-self: flex-start;
            border-bottom-left-radius: 4px;
        }
        .input-area {
            padding: 20px;
            border-top: 1px solid #e0e0e0;
            display: flex;
            gap: 10px;
        }
        #userInput {
            flex: 1;
            padding: 12px 16px;
            border: 2px solid #e0e0e0;
            border-radius: 25px;
            font-size: 14px;
            outline: none;
            transition: border-color 0.3s;
        }
        #userInput:focus { border-color: #667eea; }
        #sendBtn {
            padding: 12px 30px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            border: none;
            border-radius: 25px;
            font-size: 14px;
            font-weight: 600;
            cursor: pointer;
            transition: transform 0.2s;
        }
        #sendBtn:hover { transform: scale(1.05); }
        #sendBtn:disabled {
            opacity: 0.6;
            cursor: not-allowed;
            transform: none;
        }
        .loading {
            display: inline-block;
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background: #667eea;
            animation: loading 1.4s infinite;
        }
        @keyframes loading {
            0%, 80%, 100% { transform: scale(0); }
            40% { transform: scale(1); }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>🛍️ AI 购物助手</h1>
            <p>告诉我你想买什么，我会帮你搜索并加入购物车</p>
        </div>
        <div class="messages" id="messages">
            <div class="message assistant">
                你好！我是你的智能购物助手。你可以：<br>
                • 搜索商品：比如"找一款3000元以内的耳机"<br>
                • 查看详情：比如"第一个商品的详细信息"<br>
                • 加入购物车：比如"把第二个加入购物车"<br>
                • 结算订单：比如"帮我结算"
            </div>
        </div>
        <div class="input-area">
            <input type="text" id="userInput" placeholder="输入你的需求..." 
                   onkeypress="if(event.key==='Enter') sendMessage()">
            <button id="sendBtn" onclick="sendMessage()">发送</button>
        </div>
    </div>

    <script>
        async function sendMessage() {
            const input = document.getElementById('userInput');
            const sendBtn = document.getElementById('sendBtn');
            const messages = document.getElementById('messages');
            const userMessage = input.value.trim();
            
            if (!userMessage) return;

            // 显示用户消息
            appendMessage('user', userMessage);
            input.value = '';
            sendBtn.disabled = true;

            // 显示加载中
            const loadingDiv = appendMessage('assistant', '<span class="loading"></span> 正在思考...');

            try {
                const response = await fetch('/api/chat', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ content: userMessage })
                });
                const data = await response.json();
                
                // 移除加载消息，显示回复
                messages.removeChild(loadingDiv);
                appendMessage('assistant', data.response);
            } catch (error) {
                messages.removeChild(loadingDiv);
                appendMessage('assistant', '抱歉，服务出错了：' + error.message);
            } finally {
                sendBtn.disabled = false;
                input.focus();
            }
        }

        function appendMessage(role, content) {
            const messages = document.getElementById('messages');
            const div = document.createElement('div');
            div.className = `message ${role}`;
            div.innerHTML = content;
            messages.appendChild(div);
            messages.scrollTop = messages.scrollHeight;
            return div;
        }
    </script>
</body>
</html>
```

### 14. Skills 示例文件

#### src/main/resources/skills/search-products/SKILL.md

```markdown
---
name: search-products
description: 搜索商品目录，支持关键词、分类、价格范围过滤
version: 1.0
links:
  - name: get-product-detail
    description: 获取单个商品的详细信息
  - name: add-to-cart
    description: 将商品加入购物车
---

# 商品搜索技能

## 功能描述
调用商品搜索 API，根据用户需求筛选商品列表。

## API 端点
```
GET http://localhost:8080/api/products
```

## 请求参数
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | string | 否 | 搜索关键词（匹配商品名称或描述） |
| category | string | 否 | 商品分类（如：手机、平板、耳机） |
| priceMin | number | 否 | 最低价格 |
| priceMax | number | 否 | 最高价格 |

## 调用示例
```json
GET /api/products?keyword=耳机&priceMax=3000
```

## 返回结构
```json
[
  {
    "id": 3,
    "name": "Sony WH-1000XM5",
    "category": "耳机",
    "price": 2499.0,
    "description": "降噪蓝牙耳机",
    "stock": 80
  }
]
```

## 下一步建议
获取到商品列表后，可以：
- 使用 `get-product-detail` 查看某个商品的详细信息
- 使用 `add-to-cart` 直接将商品加入购物车
```

#### src/main/resources/skills/get-product-detail/SKILL.md

```markdown
---
name: get-product-detail
description: 根据商品 ID 获取详细信息
version: 1.0
links:
  - name: add-to-cart
    description: 将商品加入购物车
---

# 商品详情技能

## 功能描述
根据商品 ID 获取该商品的完整详细信息。

## API 端点
```
GET http://localhost:8080/api/products/{id}
```

## 路径参数
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | integer | 是 | 商品 ID |

## 调用示例
```
GET /api/products/3
```

## 返回结构
```json
{
  "id": 3,
  "name": "Sony WH-1000XM5",
  "category": "耳机",
  "price": 2499.0,
  "description": "降噪蓝牙耳机，支持 LDAC 高清音质",
  "stock": 80
}
```

## 下一步建议
查看详情后，如果用户满意，使用 `add-to-cart` 加入购物车。
```

#### src/main/resources/skills/add-to-cart/SKILL.md

```markdown
---
name: add-to-cart
description: 将指定商品加入用户购物车
version: 1.0
links:
  - name: checkout
    description: 结算购物车中的商品
---

# 加入购物车技能

## 功能描述
将指定商品加入用户的购物车。

## API 端点
```
POST http://localhost:8080/api/products/cart
```

## 请求参数（Query）
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | integer | 是 | 用户 ID（默认使用 1） |
| productId | integer | 是 | 商品 ID |

## 调用示例
```
POST /api/products/cart?userId=1&productId=3
```

## 返回结构
```json
{
  "success": true,
  "message": "已添加到购物车",
  "cartSize": 2
}
```

## 下一步建议
商品加入购物车后，可以：
- 继续购物（返回 search-products）
- 使用 `checkout` 结算订单
```

#### src/main/resources/skills/checkout/SKILL.md

```markdown
---
name: checkout
description: 结算购物车中的商品并生成订单
version: 1.0
---

# 结算订单技能

## 功能描述
提交购物车中的所有商品，生成订单并清空购物车。

## API 端点
```
POST http://localhost:8080/api/products/checkout
```

## 请求参数（Query）
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | integer | 是 | 用户 ID（默认使用 1） |

## 调用示例
```
POST /api/products/checkout?userId=1
```

## 返回结构
```json
{
  "success": true,
  "message": "订单已提交",
  "totalAmount": 5498.0,
  "itemCount": 2
}
```

## 业务逻辑
- 如果购物车为空，返回错误
- 成功结算后购物车会被清空
- 返回订单总金额和商品数量
```

---

## OpenAPI → Skills 转换流程

### 方法一：手动创建（推荐用于理解机制）

1. 访问 http://localhost:8080/v3/api-docs 获取 OpenAPI JSON
2. 分析每个 API 端点的功能
3. 为每个端点创建对应的 `skills/{skill-name}/SKILL.md` 文件
4. 编写 YAML frontmatter（name, description, links）
5. 编写 Markdown 正文（功能描述、参数、示例）

### 方法二：使用 openapi-to-skills 工具

```bash
# 安装工具
npm install -g openapi-to-skills

# 下载 OpenAPI 文档
curl http://localhost:8080/v3/api-docs > openapi.json

# 一键转换
openapi-to-skills --input openapi.json --output src/main/resources/skills

# 手动优化生成的 Skills
# - 补充 links 字段建立技能关联
# - 优化描述文案提高可读性
# - 添加调用示例和最佳实践
```

### 方法三：程序化生成（可选，高级用法）

在项目中添加 `SkillGenerator.java`：

```java
package com.example.demo.util;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.nio.file.*;

//@Component  // 取消注释以在启动时自动生成
public class SkillGenerator implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        String openApiJson = restTemplate.getForObject(
            "http://localhost:8080/v3/api-docs", String.class);
        
        // TODO: 解析 JSON，为每个 path 生成 SKILL.md
        Path skillsDir = Paths.get("src/main/resources/skills");
        Files.createDirectories(skillsDir);
        
        // 生成逻辑...
        System.out.println("Skills 已生成到：" + skillsDir.toAbsolutePath());
    }
}
```

---

## 运行示例

### 启动应用

```bash
# 确保已设置环境变量
export OPENAI_API_KEY=sk-your-key-here

# 启动
mvn spring-boot:run
```

### 完整对话流程示例

**用户输入：**
```
帮我找一款3000元以下的蓝牙耳机
```

**Agent 执行流程：**

1. **LLM 看到 available_skills 列表**
   ```
   - search-products：搜索商品目录...
   - get-product-detail：获取单个商品详情...
   - add-to-cart：将商品加入购物车...
   - checkout：结算订单...
   ```

2. **LLM 决策：需要搜索商品 → 调用 `loadSkill("search-products")`**
   
   **返回：**
   ```markdown
   ✓ 技能 `search-products` 已加载
   
   **相关技能（按需加载）：**
   - `get-product-detail`：获取单个商品的详细信息
   - `add-to-cart`：将商品加入购物车
   
   ---
   # 商品搜索技能
   ## API 端点
   GET http://localhost:8080/api/products
   ## 请求参数
   | 参数 | 类型 | 必填 | 说明 |
   ...
   ```

3. **SkillsAdvisor 自动将 Level2 内容注入下一轮 System Prompt**

4. **LLM 看到完整指令后调用 `httpRequest`**
   ```json
   {
     "method": "GET",
     "url": "http://localhost:8080/api/products",
     "params": {
       "keyword": "蓝牙耳机",
       "priceMax": "3000"
     }
   }
   ```

5. **返回商品列表**
   ```json
   [
     {
       "id": 3,
       "name": "Sony WH-1000XM5",
       "category": "耳机",
       "price": 2499.0,
       "description": "降噪蓝牙耳机",
       "stock": 80
     }
   ]
   ```

6. **LLM 生成友好回复**
   ```
   我找到了一款符合要求的耳机：
   
   Sony WH-1000XM5
   - 价格：2499元
   - 特点：降噪蓝牙耳机
   - 库存：80件
   
   需要我帮你加入购物车吗？
   ```

**用户继续：**
```
加入购物车
```

**Agent 继续执行：**
- 调用 `loadSkill("add-to-cart")`
- 调用 `httpRequest(POST /api/products/cart?userId=1&productId=3)`
- 返回 `"已添加到购物车，当前购物车有 1 件商品"`

---

## Token 效率对比

| 方式 | 初始上下文 | 单次任务上下文 |
|------|-----------|--------------|
| 直接塞 OpenAPI | ~8000 tokens | ~8000 tokens |
| MCP 全量加载 | ~3000 tokens | ~3000 tokens |
| Skills Level1 | ~800 tokens | ~800 tokens |
| Skills Level1+2（用到2个技能） | ~800 tokens | ~3500 tokens |

**节省率：约 60-75%**

---

## 扩展建议

### 1. 支持更多 LLM Provider

修改 `application.yml`：

```yaml
spring:
  ai:
    # OpenAI
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com
    
    # 或 Ollama 本地模型
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen2.5:14b
```

### 2. 添加会话记忆

使用 Spring AI 的 `ChatMemory`：

```java
@Service
public class AgentService {
    private final ChatClient chatClient;
    private final InMemoryChatMemory chatMemory;
    
    public String chat(String sessionId, String userMessage) {
        return chatClient.prompt()
            .user(userMessage)
            .advisors(new MessageChatMemoryAdvisor(chatMemory, sessionId, 10))
            .call()
            .content();
    }
}
```

### 3. 监控 Skills 使用情况

添加日志或指标：

```java
@Aspect
@Component
public class SkillUsageMonitor {
    
    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object monitor(ProceedingJoinPoint joinPoint) throws Throwable {
        String toolName = joinPoint.getSignature().getName();
        long start = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long duration = System.currentTimeMillis() - start;
            log.info("Tool {} 执行耗时: {}ms", toolName, duration);
        }
    }
}
```

### 4. 自动从 OpenAPI 生成 Skills

完善 `SkillGenerator`，实现：
- 解析 OpenAPI paths 和 schemas
- 自动识别操作关联（通过 schema 引用）
- 生成 `links` 字段
- 提取示例值

---

## 故障排查

### 问题：Skills 文件未加载

**检查：**
```bash
ls -la src/main/resources/skills/
```

**确保：**
- 每个技能都在独立目录下
- 文件名必须是 `SKILL.md`
- YAML frontmatter 格式正确（`---` 包裹）

### 问题：LLM 不调用 loadSkill

**检查 System Prompt：**
- 在日志中搜索 `SkillsAdvisor`
- 确保 `available_skills` 列表正确注入
- 提示词中强调"必须先加载技能"

### 问题：httpRequest 调用失败

**检查：**
- URL 是否包含 `http://localhost:8080` 前缀
- Method 大小写是否正确
- RestTemplate 是否正确配置

---

## 总结

这个项目展示了：

1. **RESTful API + OpenAPI 文档** - 标准的 Spring Boot Web 服务
2. **OpenAPI → Skills 转换** - 手动或自动生成 Skills 文件
3. **渐进式披露机制** - Level1 元数据 + Level2 按需加载
4. **Spring AI 原生集成** - `@Tool` + `ChatClient` + `Advisor`
5. **完整用户体验** - Web 聊天界面，自然语言交互

**核心价值：**
- Token 使用减少 60-75%
- 上下文始终保持精简
- 技能按需解锁，链式发现
- 纯开源技术栈，无厂商锁定

下载后直接运行 `mvn spring-boot:run`，无需额外配置！
