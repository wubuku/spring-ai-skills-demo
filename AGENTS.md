# AGENTS.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Project Overview

A Spring Boot demo showcasing **Spring AI Skills progressive disclosure** — an AI shopping assistant agent that lazily loads tool instructions (Skills) on demand, reducing LLM token usage by 60-75% compared to injecting full OpenAPI specs upfront.

The agent exposes a product management REST API (search, detail, cart, checkout) with Swagger UI, and a web chat interface where users interact in natural language. The LLM sees only a brief skill catalog (Level 1 metadata) and calls `loadSkill` to fetch full API instructions (Level 2) only when needed.

## Build and Run

Requires: JDK 17+, Maven 3.8+, an OpenAI-compatible API key.

```bash
# Set API key before running
export OPENAI_API_KEY=<your-key>

# Build
mvn clean package

# Run
mvn spring-boot:run

# Run skipping tests
mvn spring-boot:run -DskipTests
```

Endpoints after startup:
- Chat UI: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

## Architecture

### Agent Pipeline (request flow)

1. User sends message via `POST /api/chat` → `ChatController` → `AgentService.chat()`
2. `AgentService` resets loaded skills, then calls Spring AI `ChatClient`
3. `SkillsAdvisor` (a `CallAroundAdvisor`) intercepts the call and injects a dynamic system prompt containing:
   - **Level 1**: compact list of all registered skill names + descriptions (from `SkillRegistry`)
   - **Level 2**: full instruction bodies of any skills already loaded in this turn
4. LLM decides which skill to load → calls `SkillTools.loadSkill(name)` → receives full Markdown instructions + related skill hints
5. LLM then calls `SkillTools.httpRequest(...)` to invoke the actual REST API on localhost
6. Response flows back to the user

### Streaming Support

The agent supports SSE (Server-Sent Events) streaming for real-time responses:

- **`POST /api/chat/stream`** - Text streaming endpoint (JSON request)
- **`POST /api/chat/multimodal/stream`** - Multimodal streaming endpoint (multipart/form-data)

Both endpoints use `Flux<String>` from Project Reactor to stream tokens as they are generated.

### Key Components

- **`agent/SkillRegistry`** — Reads `src/main/resources/skills/*/SKILL.md` files at startup, parsing YAML frontmatter (name, description, links) and Markdown body. Central store for all skills.
- **`agent/SkillTools`** — Spring AI `@Tool`-annotated methods: `loadSkill` (progressive disclosure gate) and `httpRequest` (generic HTTP caller the LLM uses to invoke REST endpoints).
- **`agent/SkillsAdvisor`** — `CallAroundAdvisor` that builds the system prompt with Level 1 catalog + Level 2 loaded content. Runs at `HIGHEST_PRECEDENCE`.
- **`service/AgentService`** — Wires `ChatClient` with the advisor and tools; resets skill state per request (stateless turns). Also provides `streamChat()` for SSE streaming.
- **`service/MultimodalAgentService`** — Handles multimodal input (images + audio) by converting to text before passing to AgentService. Supports streaming via `streamChat()`.
- **`controller/ChatController`** — Provides `/api/chat/stream` SSE endpoint for text streaming.
- **`controller/MultimodalChatController`** — Provides `/api/chat/multimodal/stream` SSE endpoint for multimodal streaming.
- **`service/ProductService`** — In-memory product catalog and cart (no database). Pre-loaded with sample data.

### Confirm-Before-Mutate Mode

When `app.confirm-before-mutate=true`, non-GET `httpRequest` calls return `[CONFIRM_REQUIRED]` + a `` ```http-request `` code block (JSON metadata) instead of executing. The system prompt instructs the LLM to describe the operation and pass through the code block. The frontend (`index.html`) detects this block, shows confirm/cancel buttons, and executes the request client-side on confirmation.

### Skills Format

Each skill lives in `src/main/resources/skills/<skill-name>/SKILL.md` with:
- YAML frontmatter (`---` delimited): `name`, `description`, `version`, optional `links` (related skills)
- Markdown body: API endpoint, parameters, examples, next-step suggestions

Skills form a directed graph via `links`, enabling chain discovery (e.g., search → detail → cart → checkout).

## Language and Style

- Project language is **Chinese** (comments, UI text, Swagger descriptions, skill content). Maintain Chinese for user-facing strings.
- Uses Lombok (`@Data`, `@AllArgsConstructor`, `@NoArgsConstructor`) for model classes.
- Spring AI version is **1.0.0-M6** (milestone release) managed via BOM. The `@Tool` / `@ToolParam` annotations and `ChatClient` / `CallAroundAdvisor` APIs are from this version — check Spring AI milestone docs if APIs seem unfamiliar.
