# Documentation Quality Checklist — spring-ai-skills-demo

Use this checklist when creating or reviewing documentation in this repository. It is a review aid, not a reason to create every suggested document.

## Pre-Creation

- [ ] Current documentation state was audited.
- [ ] Documentation gaps, stale claims, duplicates, and orphaned files were identified.
- [ ] A `docs/drafts/DOCUMENTATION_PLAN.md` was created when the work spans multiple documents.
- [ ] Documents were ordered by P0-P3 where prioritization helps.
- [ ] Each planned document has a source of truth and a validation approach.

## README.md

- [ ] One-line project description with accurate keywords.
- [ ] Feature list is concise and current.
- [ ] Quick start has no more than the necessary steps.
- [ ] Prerequisites match `pom.xml`, `frontend/package.json`, Docker, and `.env.example`.
- [ ] Commands were tested or explicitly marked as requiring external services.
- [ ] Main URLs use the actual ports.
- [ ] Documentation navigation links point to existing files.
- [ ] Chinese-primary wording is preserved.
- [ ] Existing operational detail was not deleted without reason.

## AGENTS.md

- [ ] Project overview matches current source.
- [ ] Important directories and documentation files are indexed.
- [ ] Build, test, frontend, and verification commands are accurate.
- [ ] Module responsibilities match actual packages.
- [ ] Agent constraints identify critical paths and boundaries.
- [ ] `CLAUDE.md` remains a redirect only.
- [ ] Runtime Skills and agent Skills are clearly distinguished.
- [ ] Current docs and top-level directory changes are reflected.
- [ ] Links use repository-relative paths.

## Architecture and Development Guides

- [ ] Purpose and audience are stated.
- [ ] Current module and request flow are described.
- [ ] Spring Boot, Spring AI, AG-UI, frontend, database, and external-provider boundaries are explicit when relevant.
- [ ] Code examples match current method names and APIs.
- [ ] Configuration examples do not include secrets.
- [ ] Commands have been run or have their prerequisites documented.
- [ ] Related docs are linked instead of duplicated.

## REST/API Documentation

- [ ] Base URL is documented.
- [ ] Authentication behavior is explained accurately.
- [ ] Each documented endpoint has method, path, purpose, parameters, and response expectations.
- [ ] Protected and public endpoints are distinguished.
- [ ] SSE content type and event shape are documented when relevant.
- [ ] Paths match Controllers and runtime Skill files.
- [ ] Examples use current field names and parameter encoding.
- [ ] Error behavior is documented only when verified.

## Troubleshooting and Investigation

- [ ] Organized by symptom or user-visible failure.
- [ ] Startup, provider, database, API, streaming, tool, and frontend symptoms are separated when relevant.
- [ ] Each issue has symptoms, diagnosis, and verified solution steps.
- [ ] External dependency failures are distinguished from code failures.
- [ ] Historical or superseded approaches are labeled.
- [ ] Logs and commands do not expose credentials.

## Draft and Archive

- [ ] Draft filename describes its topic and purpose.
- [ ] Draft is under `docs/drafts/`.
- [ ] Draft has status, purpose, and last verification date when appropriate.
- [ ] Completed or superseded work is marked clearly.
- [ ] Archive files use `YYYY-MM-DD_` when an archive convention exists.
- [ ] Archive index and active links are updated.

## Agent Skill Package (`.agents/skills/`)

- [ ] `SKILL.md` has valid YAML frontmatter with `name` and `description`.
- [ ] The package is self-contained.
- [ ] All references use relative paths.
- [ ] No source-machine or user-home absolute paths remain.
- [ ] No secrets, `.env` values, build output, or generated data are included.
- [ ] The skill does not confuse agent Skills with runtime Skills.
- [ ] Trigger wording is specific enough to avoid accidental activation.
- [ ] Instructions match this repository's current commands and architecture.

## Language

- [ ] Chinese is the source-of-truth language for user-facing project docs.
- [ ] English translations are created only for a real audience or explicit requirement.
- [ ] Translated versions have reciprocal navigation links.
- [ ] Examples and technical meaning remain aligned across languages.

## Link Validation

- [ ] Relative links resolve from the document's directory.
- [ ] No dead links point to moved or deleted documents.
- [ ] Links with spaces, non-ASCII names, anchors, and directories were checked.
- [ ] External links are preserved only when they add real value.

## Repository-Specific Validation

- [ ] `git diff --check` passes.
- [ ] `git status --short` shows only intended changes.
- [ ] Commands match `pom.xml`, `frontend/package.json`, scripts, and `AGENTS.md`.
- [ ] API paths match Controllers, Skill files, and the AG-UI API index when applicable.
- [ ] Claims about profiles, providers, ports, authentication, or tool execution were checked against current source.
- [ ] `mvn test` is not described as offline because the current tests call external LLM APIs.
- [ ] The frontend is not described as running on port 3000/3001 when `frontend/package.json` says 4000.
- [ ] Submodule state was checked when AG-UI documentation or copied sources are involved.
