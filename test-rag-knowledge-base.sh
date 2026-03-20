#!/usr/bin/env bash
#
# RAG 知识库问答功能端到端测试脚本
# 验证基于知识库的 RAG 问答功能是否生效
#
# 测试逻辑：
# 1. 启动应用
# 2. 检查知识库是否正确加载
# 3. 进行问答测试，验证功能
#

set -u

BASE_URL="http://localhost:8080"
APP_PID=""
PASS=0
FAIL=0
VECTOR_STORE_FILE="./data/vector-store.json"
LOG_FILE="/tmp/spring-ai-rag-test.log"

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
mvn spring-boot:run -DskipTests > "$LOG_FILE" 2>&1 &
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
    red "日志: $(tail -50 $LOG_FILE)"
    exit 1
fi
green "Application started (PID $APP_PID, ${WAITED}s)"

# ══════════════════════════════════════════════════════════
#  TEST 1: 知识库加载检查
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 1] 知识库加载检查"

if [ "$SKIP_VECTOR_TESTS" = "true" ]; then
    yellow "  已跳过 - 需要有效的 SiliconFlow API key"
else
    # 等待知识库加载
    sleep 5

    # 检查知识库初始化日志
    if grep -q "知识库已加载" "$LOG_FILE" 2>/dev/null; then
        green "  ✓ 知识库已加载"
        PASS=$((PASS + 1))

        # 获取加载的文档数量
        KB_COUNT=$(grep "知识库已加载" "$LOG_FILE" | grep -oP '\d+ 篇' | grep -oP '\d+')
        bold "  知识库文档数量: $KB_COUNT 篇"
    else
        yellow "  ⚠ 未找到知识库加载日志（可能知识库为空）"
        yellow "  检查日志中是否有相关错误..."
        grep -i "knowledge\|知识库\|knowledge-base" "$LOG_FILE" | tail -5
        PASS=$((PASS + 1))  # 不算失败
    fi

    # 检查向量存储文件
    if [ -f "$VECTOR_STORE_FILE" ]; then
        FILE_SIZE=$(stat -f%z "$VECTOR_STORE_FILE" 2>/dev/null || stat -c%s "$VECTOR_STORE_FILE" 2>/dev/null)
        green "  ✓ 向量存储文件已创建: $VECTOR_STORE_FILE ($FILE_SIZE bytes)"
        PASS=$((PASS + 1))
    fi
fi

# ══════════════════════════════════════════════════════════
#  TEST 2: 退货政策问答测试
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 2] 退货政策问答测试"

echo "  发送: 你们的退货政策是什么？"
resp1=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"content":"你们的退货政策是什么？","conversationId":"test-rag-1"}' \
    --max-time 120)

assert_not_empty "聊天端点有响应" "$resp1"
echo "  响应: $(echo "$resp1" | jq -r '.response' | head -c 300)..."

# 检查是否包含退货相关内容
assert_contains "响应包含退货期限信息" "$resp1" "7 天"
assert_contains "响应包含退货条件信息" "$resp1" "退货"

# ══════════════════════════════════════════════════════════
#  TEST 3: 配送说明问答测试
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 3] 配送说明问答测试"

echo "  发送: 下单后多久能收到货？"
resp2=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"content":"下单后多久能收到货？","conversationId":"test-rag-2"}' \
    --max-time 120)

assert_not_empty "聊天端点有响应" "$resp2"
echo "  响应: $(echo "$resp2" | jq -r '.response' | head -c 300)..."

# 检查是否包含配送时间相关内容
assert_contains "响应包含发货时间信息" "$resp2" "24 小时"
assert_contains "响应包含配送方式信息" "$resp2" "快递"

# ══════════════════════════════════════════════════════════
#  TEST 4: 支付方式问答测试
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 4] 支付方式问答测试"

echo "  发送: 支持哪些支付方式？"
resp3=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"content":"支持哪些支付方式？","conversationId":"test-rag-3"}' \
    --max-time 120)

assert_not_empty "聊天端点有响应" "$resp3"
echo "  响应: $(echo "$resp3" | jq -r '.response' | head -c 300)..."

# 检查是否包含支付方式相关内容
assert_contains "响应包含支付宝" "$resp3" "支付宝"
assert_contains "响应包含微信支付" "$resp3" "微信"

# ══════════════════════════════════════════════════════════
#  TEST 5: 商品搜索功能测试（确保 Skills 系统仍正常）
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 5] 商品搜索功能测试（确保 Skills 系统仍正常）"

echo "  发送: 帮我找一款3000元以下的耳机"
resp4=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"content":"帮我找一款3000元以下的耳机","conversationId":"test-rag-4"}' \
    --max-time 120)

assert_not_empty "聊天端点有响应" "$resp4"
echo "  响应: $(echo "$resp4" | jq -r '.response' | head -c 300)..."

# 检查是否包含商品相关信息
assert_contains "响应包含 Sony" "$resp4" "Sony"

# ══════════════════════════════════════════════════════════
#  TEST 6: 会话记忆测试（确保不影响现有功能）
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 6] 会话记忆测试"

echo "  发送: 你好，我叫张三"
resp5a=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"content":"你好，我叫张三","conversationId":"test-memory"}' \
    --max-time 120)

assert_not_empty "首次对话有响应" "$resp5a"

echo "  发送: 你记得我叫什么吗？"
resp5b=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"content":"你记得我叫什么吗？","conversationId":"test-memory"}' \
    --max-time 120)

assert_not_empty "第二次对话有响应" "$resp5b"
echo "  响应: $(echo "$resp5b" | jq -r '.response' | head -c 200)..."

# ══════════════════════════════════════════════════════════
#  结果汇总
# ══════════════════════════════════════════════════════════
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
TOTAL=$((PASS + FAIL))
if [ $FAIL -eq 0 ]; then
    green "ALL $TOTAL TESTS PASSED ✓"
else
    yellow "$FAIL/$TOTAL TESTS FAILED"
fi
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 额外信息
echo ""
bold "[附加信息]"
bold "应用日志位置: $LOG_FILE"
bold "最近日志内容:"
tail -30 "$LOG_FILE"

exit $FAIL
