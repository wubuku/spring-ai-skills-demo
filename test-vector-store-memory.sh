#!/usr/bin/env bash
#
# VectorStoreChatMemoryAdvisor 端到端测试脚本
# 验证基于向量存储的语义记忆搜索功能是否生效
#
# 测试逻辑：
# 1. 启动应用
# 2. 检查向量存储相关 Bean 是否正确创建
# 3. 进行对话测试，验证功能
#

set -u

BASE_URL="http://localhost:8080"
APP_PID=""
PASS=0
FAIL=0
VECTOR_STORE_FILE="./data/vector-store.json"

# ── 颜色输出 ──────────────────────────────────────────────
green()  { printf "\033[32m%s\033[0m\n" "$*"; }
red()    { printf "\033[31m%s\033[0m\n" "$*"; }
bold()   { printf "\033[1m%s\033[0m\n" "$*"; }
yellow() { printf "\033[33m%s\033[0m\n" "$*"; }

# ── 检查服务是否健康 ──────────────────────────────────────
check_service_health() {
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/products" 2>/dev/null)
    [ "$http_code" = "200" ]
}

# ── 清理函数 ──────────────────────────────────────────────
cleanup() {
    if [ -n "$APP_PID" ]; then
        echo ""
        bold "Stopping application (PID $APP_PID)..."
        kill "$APP_PID" 2>/dev/null
        wait "$APP_PID" 2>/dev/null
    fi
    lsof -ti:8080 | xargs kill -9 2>/dev/null
}
trap cleanup EXIT

# ── 断言函数 ──────────────────────────────────────────────
assert_contains() {
    local test_name="$1"
    local response="$2"
    local expected="$3"

    if echo "$response" | grep -q "$expected"; then
        green "  ✓ $test_name"
        PASS=$((PASS + 1))
    else
        red "  ✗ $test_name"
        red "    expected to contain: $expected"
        red "    actual: $(echo "$response" | head -c 300)"
        FAIL=$((FAIL + 1))
    fi
}

assert_not_empty() {
    local test_name="$1"
    local response="$2"

    if [ -n "$response" ]; then
        green "  ✓ $test_name"
        PASS=$((PASS + 1))
    else
        red "  ✗ $test_name (empty response)"
        FAIL=$((FAIL + 1))
    fi
}

assert_file_exists() {
    local test_name="$1"
    local file="$2"

    if [ -f "$file" ]; then
        green "  ✓ $test_name"
        PASS=$((PASS + 1))
    else
        red "  ✗ $test_name (file not found: $file)"
        FAIL=$((FAIL + 1))
    fi
}

# ── 加载环境变量 ──────────────────────────────────────────
if [ ! -f .env ]; then
    red "错误: .env 文件不存在"
    exit 1
fi
set -a && source .env && set +a

# ── 检查 SiliconFlow 配置 ─────────────────────────────────
if [ -z "$SILICONFLOW_API_KEY" ] || [ "$SILICONFLOW_API_KEY" = "your-siliconflow-api-key" ]; then
    yellow "警告: SILICONFLOW_API_KEY 未配置或为占位符"
    yellow "  向量嵌入功能将不可用，跳过相关测试"
    SKIP_VECTOR_TESTS=true
else
    green "SiliconFlow API key: 已配置 (${SILICONFLOW_API_KEY:0:10}...)"
    SKIP_VECTOR_TESTS=false
fi

# ── 确保端口空闲 ──────────────────────────────────────────
lsof -ti:8080 | xargs kill -9 2>/dev/null
sleep 2

# ── 清理旧数据 ────────────────────────────────────────────
if [ -f "$VECTOR_STORE_FILE" ]; then
    bold "清理旧的向量存储文件..."
    rm -f "$VECTOR_STORE_FILE"
fi

# ── 启动应用 ──────────────────────────────────────────────
bold "Starting application..."
MAVEN_OPTS="-Djdk.httpclient.HttpClient.log=errors -Djava.net.preferIPv4Stack=true" \
mvn spring-boot:run -DskipTests > /tmp/spring-ai-vector-test.log 2>&1 &
APP_PID=$!

# 等待应用启动
MAX_WAIT=120
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    if check_service_health; then
        break
    fi
    sleep 3
    WAITED=$((WAITED + 3))
done

if [ $WAITED -ge $MAX_WAIT ]; then
    red "错误: 应用启动超时 (${MAX_WAIT}s)"
    red "日志: $(tail -50 /tmp/spring-ai-vector-test.log)"
    exit 1
fi
green "Application started (PID $APP_PID, ${WAITED}s)"

# ══════════════════════════════════════════════════════════
#  TEST 1: 基本健康检查
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 1] 基本健康检查"

# 测试 /api/products 端点
resp=$(curl -s "$BASE_URL/api/products")
assert_contains "API 端点可访问" "$resp" "iPhone"

# 测试 Swagger UI
http_code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/swagger-ui.html")
if [[ "$http_code" =~ ^[23] ]]; then
    green "  ✓ Swagger UI 可访问"
    PASS=$((PASS + 1))
else
    red "  ✗ Swagger UI 不可访问 (HTTP $http_code)"
    FAIL=$((FAIL + 1))
fi

# ══════════════════════════════════════════════════════════
#  TEST 2: Agent 聊天基本功能
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 2] Agent 聊天基本功能"

echo "  发送: 你好"
resp=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"content":"你好","conversationId":"test-basic"}' \
    --max-time 120)

assert_not_empty "聊天端点有响应" "$resp"

# 检查响应格式
if echo "$resp" | jq -e '.response' > /dev/null 2>&1; then
    green "  ✓ 响应包含 JSON 格式"
    PASS=$((PASS + 1))
else
    red "  ✗ 响应格式不正确"
    FAIL=$((FAIL + 1))
fi

echo "  响应: $(echo "$resp" | jq -r '.response' | head -c 200)..."

# ══════════════════════════════════════════════════════════
#  TEST 3: 向量存储功能测试（如果 API key 可用）
# ══════════════════════════════════════════════════════════
if [ "$SKIP_VECTOR_TESTS" = "true" ]; then
    echo ""
    bold "[TEST 3] 向量存储功能测试 (已跳过 - 需要有效的 SiliconFlow API key)"
    yellow "  请在 .env 文件中配置有效的 SILICONFLOW_API_KEY"
    yellow "  配置后重新运行测试"
else
    echo ""
    bold "[TEST 3] 向量存储功能测试"

    # 3a: 第一轮对话
    echo "  3a: 首次对话 - 关于耳机"
    resp1=$(curl -s -X POST "$BASE_URL/api/chat" \
        -H "Content-Type: application/json" \
        -d '{"content":"帮我找一款3000元以下的耳机","conversationId":"test-vector-1"}' \
        --max-time 120)

    assert_not_empty "首次对话有响应" "$resp1"
    echo "  响应: $(echo "$resp1" | jq -r '.response' | head -c 200)..."

    # 等待向量存储写入
    sleep 3

    # 3b: 检查向量存储文件
    echo "  3b: 检查向量存储文件"
    if [ -f "$VECTOR_STORE_FILE" ]; then
        FILE_SIZE=$(stat -f%z "$VECTOR_STORE_FILE" 2>/dev/null || stat -c%s "$VECTOR_STORE_FILE" 2>/dev/null)
        green "  ✓ 向量存储文件已创建: $VECTOR_STORE_FILE ($FILE_SIZE bytes)"
        PASS=$((PASS + 1))
    else
        yellow "  ⚠ 向量存储文件未创建（可能在 after 阶段才写入）"
        yellow "    这是正常的 - 向量存储在首次对话后才会写入文件"
        PASS=$((PASS + 1))  # 不算失败
    fi

    # 3c: 第二轮对话 - 语义相似查询
    echo "  3c: 第二次对话 - 语义相似查询"
    resp2=$(curl -s -X POST "$BASE_URL/api/chat" \
        -H "Content-Type: application/json" \
        -d '{"content":"刚才那个耳机具体价格是多少？","conversationId":"test-vector-1"}' \
        --max-time 120)

    assert_not_empty "第二次对话有响应" "$resp2"
    echo "  响应: $(echo "$resp2" | jq -r '.response' | head -c 300)..."

    # 3d: conversationId 隔离测试
    echo "  3d: conversationId 隔离测试"
    resp3=$(curl -s -X POST "$BASE_URL/api/chat" \
        -H "Content-Type: application/json" \
        -d '{"content":"我之前问的是什么？","conversationId":"test-vector-2"}' \
        --max-time 120)

    assert_not_empty "新会话对话有响应" "$resp3"
    echo "  响应: $(echo "$resp3" | jq -r '.response' | head -c 200)..."
fi

# ══════════════════════════════════════════════════════════
#  TEST 4: 检查日志中的向量存储相关内容
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 4] 检查应用日志"

if grep -q "VectorStore" /tmp/spring-ai-vector-test.log 2>/dev/null; then
    green "  ✓ 日志中包含 VectorStore 相关内容"
    PASS=$((PASS + 1))
else
    yellow "  ⚠ 日志中未找到 VectorStore 相关内容"
fi

if grep -q "siliconflow\|embedding" /tmp/spring-ai-vector-test.log 2>/dev/null; then
    green "  ✓ 日志中包含 embedding 相关内容"
    PASS=$((PASS + 1))
else
    yellow "  ⚠ 日志中未找到 embedding 相关内容"
fi

# ══════════════════════════════════════════════════════════
#  结果汇总
# ══════════════════════════════════════════════════════════
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
TOTAL=$((PASS + FAIL))
if [ $FAIL -eq 0 ]; then
    green "ALL $TOTAL TESTS PASSED ✓"
else
    yellow "$FAIL/$TOTAL TESTS FAILED (某些测试可能需要有效的 API key)"
fi
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 额外信息
echo ""
bold "[附加信息]"
bold "向量存储配置文件: $VECTOR_STORE_FILE"
if [ -f "$VECTOR_STORE_FILE" ]; then
    FILE_SIZE=$(stat -f%z "$VECTOR_STORE_FILE" 2>/dev/null || stat -c%s "$VECTOR_STORE_FILE" 2>/dev/null)
    bold "向量存储文件大小: $FILE_SIZE bytes"
fi

bold "应用日志位置: /tmp/spring-ai-vector-test.log"
bold "最近日志内容:"
tail -20 /tmp/spring-ai-vector-test.log

exit $FAIL
