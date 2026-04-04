# 给 Spring AI 应用增加流式多模态通路

用户提问：

> 结合我提供的 Spring  AI 应用的部分代码，给我一个如何添加流式输出通路的操作指引。

---

先给你一个总览：你现在的应用是「同步多模态 + 同步文本」，我们要在不破坏现有能力（RAG、Skills、记忆、多模态）的前提下，加一条「流式多模态通路」。

下面我会按**循序渐进 5 步**来带你做，尽量写成“照抄能跑”的 cookbook。

---

## 0. 先认识一下你现在的结构（复习）

根据你给的代码，现在是这样的：

- `AgentService` 里封装了一个带 RAG、Skills、记忆的 `ChatClient`，对外暴露**同步**方法 `chat(String userMessage, String conversationId)`。[^3]
- `MultimodalAgentService`：
    - 如果有图片：用 `visionChatClient` 调 `describeImage(...)`，把图片转成文字描述。[^2]
    - 如果有音频：用 `TranscriptionModel.transcribe(audio)` 把语音转成文字。[^2]
    - 把「图片描述 + 语音转写 + 用户文本」拼成一个大 prompt，最后丢给 `agentService.chat(...)`，还是**同步**。[^2]
- `MultimodalChatController`：
    - `/api/chat` 接收 `multipart/form-data` 的文本 + 图片 + 音频，调用 `multimodalAgentService.chat(...)`，返回一个完整字符串。[^1]
    - `/api/chat/text` 直接调用 `agentService.chat(...)`，纯文本同步。[^1]

我们要做的，就是在这个基础上：

1. 给 `AgentService` 加一个**流式方法**；
2. 给 `MultimodalAgentService` 加一个**流式多模态方法**；
3. 在 `MultimodalChatController` 新增一个 **SSE 端点**，把流推给前端。

---

## 1. 在 AgentService 里加一个流式方法

Spring AI 官方推荐的流式调用是这样：`chatClient.prompt().user(...).stream().content()` 返回 `Flux<String>`。[^4][^5][^6]

你现在的 `chat` 是这样写的（简化后）：[^3]

```java
public String chat(String userMessage, String conversationId) {
    skillTools.reset();
    return chatClient.prompt()
            .user(userMessage)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .content();
}
```


### 1.1 引入 Reactor 依赖（如果还没有）

通常 Spring AI 项目已经带了 `reactor-core`，如果没有，可以在 `pom.xml` 里确认一下：

```xml
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-core</artifactId>
</dependency>
```


### 1.2 新增一个流式方法

在 `AgentService` 里加上：[^5][^3]

```java
import reactor.core.publisher.Flux;
// 其它 import 保持不变

@Service
public class AgentService {

    private final ChatClient chatClient;
    private final SkillTools skillTools;

    // 构造器略...

    public String chat(String userMessage) {
        return chat(userMessage, "default");
    }

    public String chat(String userMessage, String conversationId) {
        skillTools.reset();
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    // ========= 新增：流式版本 =========
    public Flux<String> streamChat(String userMessage, String conversationId) {
        // 和同步版一样，先 reset 一下工具状态
        skillTools.reset();

        return chatClient
                .prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()   // ★ 关键：改用 stream()
                .content(); // ★ 返回 Flux<String>，每个元素是一小段 token
    }
}
```

到这里为止，你已经有了一个「保留所有 RAG/记忆/Skills」的**流式 LLM 接口**，只是还没接到 Controller 而已。[^5][^3]

---

## 2. 给 MultimodalAgentService 加一个「多模态流式」方法

### 2.1 现实情况先说清

- `describeImage(...)` 是同步的 `visionChatClient.prompt().call().content()`，没 streaming。[^2]
- `TranscriptionModel.transcribe(audio)` 也是同步方法，没有 streaming。[^2]
- 所以：**图片理解 + 语音转写 目前都只能“先算完，再开始流式 LLM”**。

你现在的同步方法（简化地看）是：[^2]

```java
public String chat(String query,
                   String conversationId,
                   Resource image,
                   String imageContentType,
                   Resource audio) {

    StringBuilder enrichedInput = new StringBuilder();

    if (image != null) {
        String imageDescription = describeImage(image, imageContentType, query);
        enrichedInput.append("【图片内容】").append(imageDescription).append("\n\n");
    }

    if (audio != null && transcriptionModel != null) {
        String transcription = transcriptionModel.transcribe(audio);
        enrichedInput.append("【语音转写】").append(transcription).append("\n\n");
    }

    if (query != null && !query.isBlank()) {
        enrichedInput.append("【用户输入】").append(query);
    }

    String finalInput = enrichedInput.toString();
    if (finalInput.isBlank()) {
        finalInput = "用户未提供有效输入。";
    }

    return agentService.chat(finalInput, conversationId);
}
```


### 2.2 基础版：只对 LLM 做流式（最简单，先跑起来）

在 `MultimodalAgentService` 里新增一个**签名类似**的方法，但返回 `Flux<String>`，内部仍然复用你现有的逻辑，只是最后一步改成 `agentService.streamChat(...)`。[^3][^2]

```java
import reactor.core.publisher.Flux;

@Service
public class MultimodalAgentService {

    private final AgentService agentService;
    private final ChatClient visionChatClient;
    private final TranscriptionModel transcriptionModel;

    // 构造器略...

    // ========= 新增：多模态 + LLM 流式 =========
    public Flux<String> streamChat(String query,
                                   String conversationId,
                                   Resource image,
                                   String imageContentType,
                                   Resource audio) {

        StringBuilder enrichedInput = new StringBuilder();

        // 1. 图片 → 文字描述（同步）
        if (image != null) {
            String imageDescription = describeImage(image, imageContentType, query);
            enrichedInput.append("【图片内容】").append(imageDescription).append("\n\n");
        }

        // 2. 语音 → 文字（同步）
        if (audio != null && transcriptionModel != null) {
            String transcription = transcriptionModel.transcribe(audio);
            enrichedInput.append("【语音转写】").append(transcription).append("\n\n");
        } else if (audio != null && transcriptionModel == null) {
            enrichedInput.append("【语音转写】语音转写功能未配置，无法处理音频。\n\n");
        }

        // 3. 用户文本
        if (query != null && !query.isBlank()) {
            enrichedInput.append("【用户输入】").append(query);
        }

        String finalInput = enrichedInput.toString();
        if (finalInput.isBlank()) {
            finalInput = "用户未提供有效输入。";
        }

        // 4. 交给 AgentService 的流式版本
        return agentService.streamChat(finalInput, conversationId);
    }
}
```

> 这一版的效果是：
> - 先**默默地**做完图片理解 \& 语音转写；
> - 然后开始「真正的 token 流式」返回给前端。
>
> 用户会感觉前面有一小段等待，之后就像 ChatGPT 一样一点点打字。

---

## 3. 在 MultimodalChatController 里加一个 SSE 端点

你现在的 Controller 是这样：[^1]

- `/api/chat`：`@PostMapping(consumes = MULTIPART_FORM_DATA)`，返回 `MultimodalChatResponse`（里面包了一整个 `answer` 字符串）。
- `/api/chat/text`：纯 JSON，调用 `agentService.chat(...)`。[^1]

我们要在这个 controller 里再加一个「流式版接口」，比如 `/api/chat/stream`，**仍然支持 text + image + audio**，只是返回 `SseEmitter`。

### 3.1 准备一个线程池（很关键）

`SseEmitter` 是基于 Servlet 阻塞 IO 的，你要在**独立线程**里订阅 Flux，不要阻塞请求线程。

在 `MultimodalChatController` 里增加一个字段和构造器初始化：[^1]

```java
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/chat")
public class MultimodalChatController {

    private final MultimodalAgentService multimodalAgentService;
    private final AgentService agentService;

    // 新增：线程池（简化用 Cached 版；生产环境可以用 VirtualThread 等）
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public MultimodalChatController(MultimodalAgentService multimodalAgentService,
                                    AgentService agentService) {
        this.multimodalAgentService = multimodalAgentService;
        this.agentService = agentService;
    }

    // 你原来的同步 /api/chat、/api/chat/text 方法保持不动...
}
```


### 3.2 新增一个流式多模态端点

在这个 Controller 里，加上下面这个方法（尽量照抄即可）：[^1][^2]

```java
@PostMapping(
        path = "/stream",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE // ★ SSE 关键
)
public SseEmitter chatStream(
        @RequestParam(value = "query", required = false) String query,
        @RequestParam(value = "conversationId", required = false, defaultValue = "default") String conversationId,
        @RequestPart(value = "image", required = false) MultipartFile image,
        @RequestPart(value = "audio", required = false) MultipartFile audio
) throws IOException {

    // 1. 创建 SseEmitter（0 表示不超时，也可以给个 5 分钟）
    SseEmitter emitter = new SseEmitter(0L);

    // 2. 和原来一样，把 MultipartFile 存成临时文件，再包装成 Resource
    File imageTemp = null;
    File audioTemp = null;

    FileSystemResource imageResource = null;
    String imageContentType = null;
    if (image != null && !image.isEmpty()) {
        imageTemp = File.createTempFile("img-", "-" + image.getOriginalFilename());
        image.transferTo(imageTemp);
        imageResource = new FileSystemResource(imageTemp);
        imageContentType = image.getContentType();
    }

    FileSystemResource audioResource = null;
    if (audio != null && !audio.isEmpty()) {
        audioTemp = File.createTempFile("audio-", "-" + audio.getOriginalFilename());
        audio.transferTo(audioTemp);
        audioResource = new FileSystemResource(audioTemp);
    }

    // 3. 调用我们刚才写的流式多模态方法，得到 Flux<String>
    Flux<String> tokenFlux = multimodalAgentService.streamChat(
            query,
            conversationId,
            imageResource,
            imageContentType,
            audioResource
    );

    // 4. 在独立线程里订阅 Flux，把每个 token 推给前端
    executor.submit(() -> {
        try {
            tokenFlux
                .doFinally(signalType -> {
                    // 这里统一清理临时文件
                    if (imageTemp != null && imageTemp.exists()) {
                        imageTemp.delete();
                    }
                    if (audioTemp != null && audioTemp.exists()) {
                        audioTemp.delete();
                    }
                })
                .subscribe(
                    token -> { // onNext
                        try {
                            // 这里可以直接发 token，也可以包一层 JSON，看你前端需求
                            emitter.send(SseEmitter.event()
                                    .data(token));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    },
                    error -> { // onError
                        emitter.completeWithError(error);
                    },
                    () -> {    // onComplete
                        try {
                            // 可选：发一个结束标志
                            emitter.send(SseEmitter.event().data("[DONE]"));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        } finally {
                            emitter.complete();
                        }
                    }
                );
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });

    // 5. 超时 & 错误回调
    emitter.onTimeout(() -> emitter.complete());
    emitter.onError(e -> emitter.complete());

    return emitter;
}
```

到这一步，你已经有了：

- `POST /api/chat/stream`
    - 支持：`query` + 可选 `image` + 可选 `audio`；
    - 返回：`text/event-stream` 的 SSE；
    - 每一条消息是一个 token（字符串），最后会有一个 `[DONE]`（你也可以去掉）。

---

## 4. 前端怎么用（最简单版）

假设你是浏览器前端（React / Vue 都好），可以这样：

```javascript
function startChatStream(formData) {
  const xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/chat/stream");
  xhr.setRequestHeader("Accept", "text/event-stream");

  // 把原生 EventSource 和 multipart 结合有点麻烦，
  // 这里推荐用 fetch + 读取 ReadableStream（如果你是现代浏览器）
}
```

如果你暂时只想体验一下，可以用 **纯文本版的 streaming**（后端写个 `/api/chat/text/stream`，前端用 `EventSource` 就足够），等你确认无脑可用，再给多模态也接上去。

比如纯文本 SSE 端点可以很简单地写成：

```java
@PostMapping(
        path = "/text/stream",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
)
public SseEmitter chatTextStream(@RequestBody MultimodalChatRequest request) {

    SseEmitter emitter = new SseEmitter(0L);

    Flux<String> tokenFlux = agentService.streamChat(
            request.query(),
            request.conversationId()
    );

    executor.submit(() -> {
        tokenFlux.subscribe(
            token -> {
                try {
                    emitter.send(SseEmitter.event().data(token));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            },
            emitter::completeWithError,
            emitter::complete
        );
    });

    return emitter;
}
```

前端用原生 `EventSource` 即可：

```javascript
const es = new EventSource("/api/chat/text/stream?query=你好&conversationId=default");
let full = "";

es.onmessage = (e) => {
  if (e.data === "[DONE]") {
    es.close();
    return;
  }
  full += e.data;
  render(full); // 这里更新页面
};
```


---

## 5. 常见坑 + 自检清单

1. **是否真的在用 `stream()`？**
    - 控制台里搜一下 `.stream()`，确认 `AgentService.streamChat` 里写的是：

```java
.stream().content()
```

而不是 `.call().content()`。[^6][^5]
2. Controller 上有没有：
    - `produces = MediaType.TEXT_EVENT_STREAM_VALUE`？[^4]
    - 返回类型是 `SseEmitter`（MVC 场景下）？
3. 有没有在**独立线程**里订阅 `Flux`？
    - 如果直接在 Controller 方法里 `tokenFlux.subscribe(...)`，请求线程会被占用，可能导致连接立刻断掉。
4. 异常处理：
    - `emitter.send(...)` 抛 `IOException` 时要 `completeWithError`，否则连接挂起。
5. 临时文件清理：
    - 用 `doFinally` 清理是比较优雅的方式，避免在异步线程里忘记删临时文件。

---

如果你愿意，下一步我们可以在这个基础上再**加上“阶段事件”**（比如：“正在分析图片…”，“正在转写音频…”），让前端在 ASR/视觉阶段也看到进度条，而不是静默等待；不过先把上面这个「基础版流式多模态」跑通会更踏实。

---

## 用户追问

> 你不是说过视觉模型的本身就支持流式回复吗？你之前说的只是 Spring AI 的语音转写模型不支持流式结果啊。

---

你说得完全正确，我之前的 cookbook 在这一点上写错了！现在明确纠正。

---

## 纠正：视觉模型完全支持流式

回顾一下之前讨论的结论：


| 阶段 | 流式？ | 原因 |
| :-- | :-- | :-- |
| **视觉模型**（GPT-4V / Claude Vision） | ✅ **支持** | 本质是 LLM，只是输入多了图片 |
| **ASR 语音转写** | ❌ 不支持 | seq2seq 批处理架构 |
| **LLM 回复** | ✅ 支持 | 标准 token 流 |

你当前的 `describeImage()` 是用 `.call().content()` 同步调用的，**完全可以改成流式**。真正无法流式的只有 `TranscriptionModel.transcribe(audio)`。[^1]

---

## 正确的流水线设计

```
[有图片]  → ████ 视觉模型 token 流 ████ → ████ LLM token 流 ████ → [DONE]
[有音频]  → [░░ ASR 等待 ░░] → ████ LLM token 流 ████ → [DONE]
[纯文字]  → ████ LLM token 流 ████████████████████ → [DONE]
```


---

## 关键技术：`Flux.cache()` + `Flux.concat()`

视觉模型流有一个特殊的需求：我们需要**把它的输出做两件事**：

1. 边产生边推给前端（让用户看到图片识别过程）
2. 最后收集完整文字，再作为输入送给 LLM

用 `cache()` 可以让同一个 `Flux` 被订阅两次但视觉模型**只调用一次**：

```java
// MultimodalAgentService.java 修正版

public Flux<String> streamChat(String query,
                                String conversationId,
                                Resource image,
                                String imageContentType,
                                Resource audio) {

    // ===== 路径 1：有图片 =====
    if (image != null) {
        MediaType mt = parseMediaType(imageContentType, MediaType.IMAGE_JPEG);
        Media media = new Media(mt, image);
        String visionPrompt = (query != null && !query.isBlank())
                ? "用户问题是：" + query + "\n请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。"
                : "请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。";

        // ★ 关键：视觉模型用 stream()，并用 cache() 让结果可以被复用
        Flux<String> visionTokens = visionChatClient.prompt()
                .user(u -> u.text(visionPrompt).media(media))
                .stream()
                .content()
                .cache(); // 缓存结果，视觉模型只调用一次，但可以被订阅两次

        // 第一段：把视觉模型的 token 直接推给前端
        Flux<String> visionStage = visionTokens;

        // 第二段：等视觉模型跑完，收集完整描述，再跑 LLM
        Flux<String> llmStage = visionTokens
                .reduce("", String::concat)       // 把所有 token 拼成一整段描述
                .flatMapMany(imageDescription -> {
                    // 拼成最终 prompt 送给 LLM
                    String finalInput = "【图片内容】" + imageDescription + "\n\n"
                            + (query != null && !query.isBlank() ? "【用户输入】" + query : "");
                    return agentService.streamChat(finalInput, conversationId);
                });

        // concat 保证顺序：先视觉 token 流，再 LLM token 流
        return Flux.concat(visionStage, llmStage);
    }

    // ===== 路径 2：有音频（ASR 只能同步，这个没法改） =====
    if (audio != null && transcriptionModel != null) {
        // ASR 是阻塞的，用 Mono.fromCallable 包装，避免阻塞 Reactor 线程
        return Mono.fromCallable(() -> transcriptionModel.transcribe(audio))
                .subscribeOn(Schedulers.boundedElastic()) // 阻塞操作丢到独立线程
                .flatMapMany(transcript -> {
                    String finalInput = "【语音转写】" + transcript + "\n\n"
                            + (query != null && !query.isBlank() ? "【用户输入】" + query : "");
                    return agentService.streamChat(finalInput, conversationId);
                });
    }

    // ===== 路径 3：纯文字 =====
    String finalInput = (query != null && !query.isBlank()) ? query : "用户未提供有效输入。";
    return agentService.streamChat(finalInput, conversationId);
}
```


---

## 前端会看到的效果

```
[用户上传图片：一张折线图]

t=0ms    ← "这"
t=100ms  ← "张"
t=150ms  ← "图片"          ← 视觉模型 token 流，用户看到识别过程
t=200ms  ← "展示了"
t=300ms  ← "2023年销售额..."
...
t=2s     ← "（视觉模型流结束，LLM 开始）"
t=2.1s   ← "根"            ← LLM token 流，开始生成回复
t=2.2s   ← "据"
t=2.3s   ← "图表..."
...
t=5s     ← [DONE]
```


---

## Controller 不需要改

你之前 Step 3 里写的 Controller 代码完全不用动，它只管拿 `Flux<String>` 然后 `subscribe` 推给 `SseEmitter`，流的内容怎么来是 Service 层的事。这就是**分层的好处**。[^2]

---

## 用户再追问

> Spring AI 抽象的 TranscriptionModel 确实没有提供流式结果返回的方法吗？请核查。

---

核查结果已确认。

**结论：Spring AI 的 `TranscriptionModel` 接口确实没有流式方法，且目前也不存在 `StreamingTranscriptionModel` 这个接口。**[^1]

官方文档显示 `TranscriptionModel` 的完整接口只有以下三个方法，全部同步：[^1]

```java
public interface TranscriptionModel extends Model<AudioTranscriptionPrompt, AudioTranscriptionResponse> {

    // 唯一的核心方法：同步阻塞
    AudioTranscriptionResponse call(AudioTranscriptionPrompt transcriptionPrompt);

    // 便捷方法：内部调用 call()，同步
    default String transcribe(Resource resource) { ... }

    // 便捷方法：带选项，同步
    default String transcribe(Resource resource, AudioTranscriptionOptions options) { ... }
}
```

对比一下 Spring AI 里有 streaming 版本的接口：`StreamingChatModel`（LLM 流式）、`StreamingSpeechModel`（TTS 流式），但 **ASR 方向没有对应的 `StreamingTranscriptionModel`**。

所以我之前的说法是准确的：

| Spring AI 接口 | 是否有流式版本 |
| --- | --- |
| `ChatModel` | ✅ `StreamingChatModel` |
| `SpeechModel`（TTS） | ✅ `StreamingSpeechModel` |
| `TranscriptionModel`（ASR） | ❌ **无流式版本** |

如果你需要 ASR 的流式效果，只能绕过 Spring AI 抽象层，直接调用厂商的原生接口（比如 GLM-ASR 的 `stream=true`），自己用 `WebClient` 解析 SSE，这个方案我们之前讨论过。