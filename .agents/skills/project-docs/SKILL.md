---
name: project-docs
description: Create and maintain a systematic, AI-agent-friendly documentation system for this Spring Boot and Spring AI project. Triggers on "create documentation", "build documentation system", "文档体系建设", "创建文档", "完善文档", "set up docs", "document this project", or requests to organize architecture, development, API, testing, troubleshooting, or draft documents. Use when starting a documentation initiative, onboarding agents, or improving existing docs without duplicating repository facts.
---

# Project Documentation System

Use this skill to create and maintain repository-local documentation for `spring-ai-skills-demo`. Documentation is versioned with the code and should help humans and agents understand the current implementation, make scoped changes, and verify behavior.

## Core Rules

1. **Code and configuration are the source of truth.** Inspect the current Java, TypeScript, YAML, Maven, and script files before documenting behavior. Do not preserve a claim only because it exists in an older document.
2. **Navigation beats duplication.** Prefer a small hub plus focused guides and references. Link to existing integration guides, reports, and drafts instead of copying them into new documents.
3. **Document the current boundary.** Distinguish application runtime Skills under `src/main/resources/skills/` from portable agent workflows under `.agents/skills/`.
4. **Write for the repository's audience.** This project is Chinese-primary. Keep user-facing project documentation in Chinese; retain English for code identifiers, protocol names, and upstream terminology where useful.
5. **Keep the change scoped.** A documentation task does not justify unrelated code refactors, mass file moves, or rewriting every historical draft.

Use progressive disclosure for documentation too:

```text
navigation hub -> focused guide -> API/config/reference detail
```

## Package Self-Containment

This directory is a portable skill package:

- Keep required instructions and references inside `.agents/skills/project-docs/`.
- Use relative links from `SKILL.md` to `references/`.
- Never depend on `/Users/...`, the source repository from which this package was copied, or undocumented machine-local files.
- Declare external tools as prerequisites only when the workflow needs them, and check that they are available.
- Read [`references/templates.md`](references/templates.md) before creating document scaffolds.
- Read [`references/checklist.md`](references/checklist.md) when auditing or validating documentation.

## Repository Documentation Map

The project already has a substantial documentation surface. Do not create every file in an idealized layout unless a real gap justifies it.

```text
AGENTS.md                         # authoritative agent navigation and constraints
CLAUDE.md                         # compatibility redirect to AGENTS.md
README.md                         # human-facing overview and runbook
COPILOTKIT_INTEGRATION.md         # integration overview
TEST_REPORT.md                    # verified test report
frontend/README.md                # frontend-specific guide
docs/
├── *.md                          # stable integration and investigation docs
└── drafts/                       # active plans, diagnoses, and working notes
.agents/skills/                   # portable agent skills; this package lives here
src/main/resources/skills/        # runtime Skills exposed to the Spring AI agent
```

Recommended layers for new documentation:

- **Hub:** update `AGENTS.md` when navigation, constraints, or top-level structure changes.
- **Guide:** use a focused file under `docs/` or a component README for setup, architecture, integration, or troubleshooting.
- **Reference:** keep endpoint details in the current owner, such as Controller annotations, Swagger/OpenAPI, runtime Skill files, or a focused API/config reference.
- **Draft:** use `docs/drafts/` for active plans and investigations.
- **Archive:** use `docs/archive/` only if the repository adopts an archive directory and index. Otherwise preserve the existing report/draft conventions.

Do not introduce `MEMORY.md`, `.cursor/rules/`, or a duplicate root agent-memory document merely because a generic template mentions them. `AGENTS.md` is authoritative and root `CLAUDE.md` is a redirect only.

## Priority Model

Use priorities to sequence multi-document work:

| Priority | Typical documents | Guidance |
|---|---|---|
| **P0** | `AGENTS.md`, `README.md`, contribution entry points | Navigation and first-run correctness |
| **P1** | Architecture, development, quality, verification, integration index | Shared engineering understanding |
| **P2** | Configuration, REST API, extension, troubleshooting guides | Detailed operational references |
| **P3** | Archive management, historical reports, templates | Improve when there is a concrete need |

These priorities are planning aids, not a requirement to create missing files. Use the existing repository documents when they already cover the subject.

## Source-of-Truth Matrix

Before editing a document, identify its owner:

| Topic | Primary source | Documentation role |
|---|---|---|
| Build and dependency versions | `pom.xml`, `frontend/package.json` | Summarize in `AGENTS.md`/README |
| Runtime configuration | `src/main/resources/application*.yml`, config classes, `.env.example` | Explain in a configuration guide or `AGENTS.md` |
| REST/SSE endpoint behavior | Controllers and DTOs | Link from guides; keep examples synchronized |
| Agent tool behavior | `agent/`, `AgUiConfig`, `SpringAIAgent`, frontend hook | Document flow and safety boundaries |
| Runtime Skills | `src/main/resources/skills/` | Treat each `SKILL.md` as API instruction source |
| AG-UI upstream boundary | `ag-ui-4j/`, copied `src/main/java/com/agui/` | Explain synchronization and local modifications |
| Test behavior | `src/test/`, `test-*.sh`, frontend scripts | Document prerequisites and limitations |
| Historical reasoning | `docs/drafts/`, investigation reports | Useful context, not current-state authority |

When two documents disagree, verify the source-of-truth file and update the stale document. Do not resolve contradictions by adding another summary.

## Workflow: Audit and Plan

### 1. Inspect the current documentation state

Start from the repository root:

```bash
rg --files -g '*.md' -g '!target/**' -g '!frontend/node_modules/**' | sort
git status --short --branch
```

Then inspect:

- `AGENTS.md`, `CLAUDE.md`, `README.md`
- relevant `docs/` and `docs/drafts/` files
- `pom.xml`, `frontend/package.json`, configuration files, and scripts
- source files that own the behavior being documented

Assess:

- What already exists?
- Which documents are current, stale, duplicated, or orphaned?
- Which facts are missing from the navigation hub?
- Which claims are contradicted by the current source?

### 2. Decide whether a plan file is warranted

For a one-file correction or a small documentation update, do not create process overhead.

For a substantive documentation initiative:

1. Create `docs/drafts/DOCUMENTATION_PLAN.md`.
2. List needed documents by P0-P3.
3. Record the source of truth and dependencies for each document.
4. Record validation commands and external prerequisites.
5. Mark completed items incrementally.

### 3. Choose the smallest correct destination

- Update `AGENTS.md` for agent navigation, constraints, or top-level directory changes.
- Update `README.md` for human quick start and feature overview.
- Use `docs/` for stable architecture, integration, configuration, API, or troubleshooting material.
- Use `docs/drafts/` for work in progress, design alternatives, diagnoses, and plans.
- Use `frontend/README.md` for frontend-only setup and implementation details.
- Keep runtime API instructions in `src/main/resources/skills/`.
- Keep reusable agent workflows in `.agents/skills/`.

## Workflow: Write

### Current-state documentation

When documenting the current architecture:

- State concrete paths and commands.
- Mention configuration/profile caveats that affect whether the application starts.
- Distinguish ordinary `AgentService` from the AG-UI `SpringAIAgent` path.
- Distinguish the static backend page from the Next.js/CopilotKit frontend.
- Explain authentication as demo-only Base64 token handling; do not call it JWT.
- Identify external dependencies such as LLM, embedding, vision, transcription, and PostgreSQL services.
- State when tests call external services and are not offline unit tests.

### Endpoint and API documentation

When an endpoint changes, inspect and update as applicable:

1. Controller mapping and DTO.
2. `src/main/resources/skills/` API instruction.
3. `SkillRegistry` API index behavior.
4. Prompt templates and tool schemas.
5. Frontend `httpRequest` validation/execution.
6. Shell, Java, or Playwright regression scripts.
7. Swagger/OpenAPI descriptions.

Never invent an API path in documentation. Use the path from the Controller or runtime Skill and verify it with the API index when relevant.

### Agent and tool documentation

Document the two tool boundaries explicitly:

- Ordinary `AgentService` registers `SkillTools`, including the backend HTTP implementation used by that path.
- AG-UI `AgUiConfig` registers only `loadSkill` and `readSkillReference`; the browser-side CopilotKit `httpRequest` performs frontend API calls and user confirmation.

When describing tool execution, account for:

- `SkillsAdvisor` progressive disclosure.
- `SpringAIAgent` manual tool execution and frontend tool results.
- `JsonArgToolCallback` compatibility behavior.
- `maxToolCalls=5`.
- API URL validation.
- authentication context propagation across asynchronous execution.

### Draft and investigation documents

A draft must be actionable and name its current status:

```markdown
# Topic

> **状态**: 规划中 / 调查中 / 已实施 / 已废弃
> **目的**: This document explains what decision or work it supports.
> **最后核对**: YYYY-MM-DD
```

Mark superseded approaches clearly. Historical drafts can contain incorrect intermediate conclusions; do not silently present them as current architecture.

## Document Specifications

### README.md

The root README is a human landing page. Keep the first screen focused:

- What the project demonstrates.
- Required prerequisites.
- Configuration and startup commands.
- Main URLs.
- Short feature list.
- Links to deeper documentation.

The current README is intentionally more detailed than the ideal template. Improve it incrementally and avoid deleting tested operational material during unrelated work.

### AGENTS.md

`AGENTS.md` is the single current-state navigation document for agents. It should answer:

- What is this project?
- Where are the important modules and resources?
- How do I build, run, and test it?
- Which code paths own the behavior?
- What boundaries and pitfalls must I respect?

Do not force this repository's `AGENTS.md` into a generic line limit if that would remove important current facts. Keep it navigable with headings and concise tables.

### Architecture and development guides

Create these only when the current docs leave a meaningful gap:

- `docs/ARCHITECTURE.md`: system context, modules, data flow, agent/tool boundaries, and key decisions.
- `docs/DEVELOPMENT.md`: environment setup, backend/frontend startup, profile selection, and development workflow.
- `docs/configuration.md`: environment variables, provider selection, database/vector store, vision, and transcription.
- `docs/rest-api.md`: endpoint reference when README/Swagger/Skills are insufficient.
- `docs/troubleshooting.md`: symptom-first diagnosis with verified commands and solutions.
- `docs/HARNESS.md`: standard build/test/E2E verification sequence.

Do not create a layer model or glossary unless the project actually needs one.

## Language and Translation

Chinese is the primary language for this project:

- Keep the Chinese document as the source of truth.
- Create an English companion only for an explicit audience or requirement.
- If both versions exist, add a short navigation link in each.
- Translate meaning and examples, not just headings.

Suggested header:

```markdown
> 中文为主；English translation: `path/to/doc-en.md` (when present)
```

## Lifecycle Management

| Type | Location | Rule |
|---|---|---|
| Live | Appropriate root/component/docs path | Has a clear purpose and current owner |
| Draft | `docs/drafts/` | Active plan, diagnosis, research, or design |
| Historical | `docs/archive/` if adopted | Read-only snapshot with date prefix |
| Reference | `docs/` or owning source directory | Stable, versioned technical detail |
| Runtime Skill | `src/main/resources/skills/` | LLM-facing API instructions |
| Agent Skill | `.agents/skills/` | Portable agent workflow |

### Archive

If an archive structure exists or is introduced:

1. Copy the document to `docs/archive/YYYY-MM-DD_<descriptive-name>.md`.
2. Add it to `docs/archive/README.md`.
3. Update links from active documents.
4. Remove the active copy only when the archived status is intentional.

Use the last relevant date, not automatically today's date.

### Draft naming

Use descriptive names:

| Bad | Better |
|---|---|
| `TASK_PROGRESS.md` | `copilotkit-tool-call-progress.md` |
| `NOTES.md` | `agui-auth-pass-through-diagnosis.md` |
| `TEMP.md` | `multimodal-vision-prompt-plan.md` |

Prefer `<topic>-<type>.md`, `<component>-<topic>.md`, or a date-prefixed historical name.

## Workflow: Validate

Run the smallest validation set that proves the documentation is correct.

### Always

```bash
git diff --check
git status --short
```

### For backend facts or code examples

```bash
mvn -DskipTests clean package
```

### For frontend commands or frontend references

```bash
cd frontend
npm run build
```

Only run this when the local frontend support files required by `package.json` and `next.config.js` exist. The current repository may contain ignored build support files; inspect their presence first.

### For behavior and endpoint claims

Use the relevant regression script after satisfying its prerequisites. Remember:

- `mvn test` includes external DeepSeek/LLM smoke tests.
- Many `test-*.sh` scripts require `.env`, a running backend, PostgreSQL, or external provider credentials.
- Multimodal tests require `TEST_IMAGE_PATH` and/or `TEST_AUDIO_PATH`.
- AG-UI browser tests require the backend, frontend on port 4000, and Playwright.

### Link validation

For local Markdown links, verify the target exists from the document's directory. A simple audit can start with:

```bash
rg -n '\]\([^https#][^)]+\)' AGENTS.md README.md docs frontend/README.md
```

Then inspect links containing spaces, anchors, renamed files, or directories manually. Do not treat a grep hit as proof that every link is valid.

### Final review

- Examples match current package versions and method signatures.
- Commands match `pom.xml`, `frontend/package.json`, scripts, and `AGENTS.md`.
- Endpoint paths match Controllers, runtime Skills, and API index behavior.
- Profile/provider/authentication claims match current source.
- No source-machine absolute paths or secrets are present.
- No duplicate current-state document contradicts `AGENTS.md`.

## Common Mistakes

| Mistake | Prevention |
|---|---|
| Giant README containing every diagnosis | Use hub -> guide -> reference |
| Treating a draft as current truth | Recheck code and mark status |
| Duplicating endpoint paths in many documents | Keep an owning source and link to it |
| Confusing runtime Skills with agent Skills | Use the two explicit paths |
| Reintroducing old confirmation or tool architecture | Check current `AgUiConfig`, `SpringAIAgent`, and frontend hook |
| Claiming tests are offline | Inspect `src/test` and scripts for external calls |
| Leaving copied absolute paths in the Skill package | Run an absolute-path scan before finishing |

## Reference Files

Read [`references/templates.md`](references/templates.md) for adaptable README, AGENTS, guide, draft, and archive scaffolds.

Read [`references/checklist.md`](references/checklist.md) for per-document and repository-specific validation checklists.

## Agent Integration

On project entry, read the root `AGENTS.md` first. Before changing documentation, audit the current source and existing docs. During a multi-file documentation change, maintain a plan in `docs/drafts/` when useful. After the change, run the relevant verification commands and review the diff.

When a change fails because of a knowledge gap, improve the owning document or navigation link before adding more code-specific assumptions.
