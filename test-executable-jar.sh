#!/usr/bin/env bash
#
# Build and smoke-test the executable Spring Boot JAR without external AI services.
#
# Optional environment variables:
#   EXECUTABLE_JAR_PORT=18081
#   SKIP_PACKAGE=true

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/spring-ai-skills-jar.XXXXXX")"
LOG_FILE="$RUNTIME_DIR/application.log"
APP_PID=""
MOCK_LLM_PID=""

free_port() {
  node -e '
    const server = require("node:net").createServer();
    server.listen(0, "127.0.0.1", () => {
      console.log(server.address().port);
      server.close();
    });
  '
}

PORT="${EXECUTABLE_JAR_PORT:-$(free_port)}"
if [[ -n "${EXECUTABLE_JAR_MOCK_LLM_PORT:-}" ]]; then
  MOCK_LLM_PORT="$EXECUTABLE_JAR_MOCK_LLM_PORT"
else
  while :; do
    MOCK_LLM_PORT="$(free_port)"
    [[ "$MOCK_LLM_PORT" != "$PORT" ]] && break
  done
fi

cleanup() {
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  if [[ -n "$MOCK_LLM_PID" ]] && kill -0 "$MOCK_LLM_PID" 2>/dev/null; then
    kill "$MOCK_LLM_PID" 2>/dev/null || true
    wait "$MOCK_LLM_PID" 2>/dev/null || true
  fi
  rm -rf "$RUNTIME_DIR"
}
trap cleanup EXIT

for required_port in "$PORT" "$MOCK_LLM_PORT"; do
  if [[ -n "$(lsof -ti:"$required_port" -sTCP:LISTEN 2>/dev/null || true)" ]]; then
    printf 'test-executable-jar.sh: port %s is already occupied\n' "$required_port" >&2
    exit 1
  fi
done

cd "$ROOT_DIR"
if [[ "${SKIP_PACKAGE:-false}" != "true" ]]; then
  mvn -DskipTests package
fi

JAR_FILE=""
for candidate in target/spring-ai-skills-demo-*.jar; do
  [[ -f "$candidate" ]] || continue
  if [[ -z "$JAR_FILE" || "$candidate" -nt "$JAR_FILE" ]]; then
    JAR_FILE="$candidate"
  fi
done
if [[ -z "$JAR_FILE" ]]; then
  printf 'test-executable-jar.sh: executable JAR not found under target/\n' >&2
  exit 1
fi

node -e '
  const http = require("node:http");
  const port = Number(process.argv[1]);
  http.createServer((request, response) => {
    request.resume();
    request.on("end", () => {
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({
        id: "chatcmpl-skill-jar-smoke",
        object: "chat.completion",
        created: 0,
        model: "smoke-model",
        choices: [{
          index: 0,
          message: {
            role: "assistant",
            content: "nested reference loaded"
          },
          finish_reason: "stop"
        }],
        usage: {
          prompt_tokens: 1,
          completion_tokens: 1,
          total_tokens: 2
        }
      }));
    });
  }).listen(port, "127.0.0.1");
' "$MOCK_LLM_PORT" >"$RUNTIME_DIR/mock-llm.log" 2>&1 &
MOCK_LLM_PID="$!"

env \
  SERVER_PORT="$PORT" \
  SPRING_PROFILES_ACTIVE=default \
  SKILL_LOCATIONS=classpath*:skills \
  LLM_PROVIDER=openai \
  OPENAI_API_KEY=dummy \
  OPENAI_BASE_URL="http://127.0.0.1:$MOCK_LLM_PORT" \
  OPENAI_MODEL=smoke-model \
  RAG_ENABLED=false \
  VECTOR_MEMORY_ENABLED=false \
  SPRING_DATASOURCE_URL="jdbc:h2:file:$RUNTIME_DIR/chat-memory;DB_CLOSE_ON_EXIT=FALSE" \
  KNOWLEDGE_VECTOR_STORE_FILE="$RUNTIME_DIR/knowledge-vector-store.json" \
  CHAT_MEMORY_VECTOR_STORE_FILE="$RUNTIME_DIR/chat-memory-vector-store.json" \
  java -jar "$JAR_FILE" >"$LOG_FILE" 2>&1 &
APP_PID="$!"

for _ in $(seq 1 90); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    tail -100 "$LOG_FILE" >&2 || true
    printf 'test-executable-jar.sh: application exited before becoming ready\n' >&2
    exit 1
  fi
  if curl -fsS --max-time 3 "http://localhost:$PORT/api/skills" \
      >"$RUNTIME_DIR/skills.json" 2>/dev/null; then
    break
  fi
  sleep 2
done

if [[ ! -s "$RUNTIME_DIR/skills.json" ]]; then
  tail -100 "$LOG_FILE" >&2 || true
  printf 'test-executable-jar.sh: application did not become ready\n' >&2
  exit 1
fi

curl -fsS --max-time 10 "http://localhost:$PORT/api/skills/api-index" \
  >"$RUNTIME_DIR/api-index.json"

jq -e 'length == 6' "$RUNTIME_DIR/skills.json" >/dev/null
jq -e 'length == 24' "$RUNTIME_DIR/api-index.json" >/dev/null

grep -q '技能加载完成，共加载 6 个技能' "$LOG_FILE"
grep -q 'API 索引构建完成，共 24 个端点' "$LOG_FILE"

curl -fsS --max-time 30 \
  -H 'Content-Type: application/json' \
  -d '{
    "method":"GET",
    "url":"/api/v3/pet/42",
    "statusCode":200,
    "responseBody":"{\"id\":42,\"name\":\"BootJarPet\",\"status\":\"available\"}"
  }' \
  "http://localhost:$PORT/api/explain-result" \
  >"$RUNTIME_DIR/explanation.txt"

grep -q 'nested reference loaded' "$RUNTIME_DIR/explanation.txt"
grep -q '直接匹配到 API 描述: GET /api/v3/pet/42' "$LOG_FILE"

printf 'Executable JAR smoke test passed: 6 Skills, 24 API entries, nested reference read (port %s)\n' "$PORT"
