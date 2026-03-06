#!/usr/bin/env bash
#
# 回归测试脚本 - 启动应用并验证所有端点
# 用法: ./test.sh
# 前置条件: .env 文件已配置（参见 README.md）
#

set -u

BASE_URL="http://localhost:8080"
APP_PID=""
PASS=0
FAIL=0

# ── 颜色输出 ──────────────────────────────────────────────
green()  { printf "\033[32m%s\033[0m\n" "$*"; }
red()    { printf "\033[31m%s\033[0m\n" "$*"; }
bold()   { printf "\033[1m%s\033[0m\n" "$*"; }

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
        red "    actual: $(echo "$response" | head -c 200)"
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
    red "错误: .env 文件不存在，请参考 README.md 创建"
    exit 1
fi
set -a && source .env && set +a

# ── 确保端口空闲 ──────────────────────────────────────────
lsof -ti:8080 | xargs kill -9 2>/dev/null
sleep 1

# ── 启动应用 ──────────────────────────────────────────────
bold "Starting application..."
mvn spring-boot:run -DskipTests > /tmp/spring-ai-test.log 2>&1 &
APP_PID=$!

# 等待应用启动
MAX_WAIT=60
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    if curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/products" 2>/dev/null | grep -q "200"; then
        break
    fi
    sleep 2
    WAITED=$((WAITED + 2))
done

if [ $WAITED -ge $MAX_WAIT ]; then
    red "错误: 应用启动超时 (${MAX_WAIT}s)"
    red "日志: $(tail -20 /tmp/spring-ai-test.log)"
    exit 1
fi
green "Application started (PID $APP_PID, ${WAITED}s)"
echo ""

# ══════════════════════════════════════════════════════════
#  TEST 1: REST API
# ══════════════════════════════════════════════════════════
bold "[TEST 1] REST API - 商品搜索"

resp=$(curl -s "$BASE_URL/api/products")
assert_contains "GET /api/products 返回商品列表" "$resp" "iPhone 15"
assert_contains "包含 Sony 耳机" "$resp" "Sony WH-1000XM5"
assert_contains "包含 MacBook" "$resp" "MacBook Air M3"

resp=$(curl -s -G "$BASE_URL/api/products" --data-urlencode "keyword=耳机" --data-urlencode "priceMax=3000")
assert_contains "关键词+价格过滤" "$resp" "Sony WH-1000XM5"

echo ""
bold "[TEST 2] REST API - 商品详情"

resp=$(curl -s "$BASE_URL/api/products/3")
assert_contains "GET /api/products/3 返回 Sony" "$resp" "Sony WH-1000XM5"
assert_contains "包含价格字段" "$resp" "2499"

echo ""
bold "[TEST 3] REST API - 购物车 & 结算"

resp=$(curl -s -X POST "$BASE_URL/api/products/cart?userId=1&productId=3")
assert_contains "加入购物车成功" "$resp" '"success":true'
assert_contains "包含 cartSize" "$resp" "cartSize"

resp=$(curl -s -X POST "$BASE_URL/api/products/checkout?userId=1")
assert_contains "结算成功" "$resp" '"success":true'
assert_contains "包含 totalAmount" "$resp" "totalAmount"

resp=$(curl -s -X POST "$BASE_URL/api/products/checkout?userId=1")
assert_contains "空购物车结算失败" "$resp" '"success":false'

echo ""
bold "[TEST 4] REST API - Swagger & OpenAPI"

resp=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/swagger-ui.html")
# swagger-ui.html 会 302 重定向
assert_contains "Swagger UI 可访问" "$resp" "3"

resp=$(curl -s "$BASE_URL/v3/api-docs")
assert_contains "OpenAPI JSON 可访问" "$resp" "openapi"

# ══════════════════════════════════════════════════════════
#  TEST 5: Agent 聊天 (需要 LLM API)
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 5] Agent 聊天 - 完整 Skills 流程"
echo "  (调用 LLM API，可能需要 30-60 秒...)"

resp=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"content":"帮我找一款3000元以下的耳机"}' \
    --max-time 90)
assert_not_empty "聊天端点有响应" "$resp"
assert_contains "返回包含 response 字段" "$resp" '"response"'
# Agent 应该搜索商品并返回 Sony 耳机相关内容
assert_contains "推荐了耳机相关商品" "$resp" "Sony\|耳机\|2499\|WH-1000XM5"

# ══════════════════════════════════════════════════════════
#  结果汇总
# ══════════════════════════════════════════════════════════
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
TOTAL=$((PASS + FAIL))
if [ $FAIL -eq 0 ]; then
    green "ALL $TOTAL TESTS PASSED ✓"
else
    red "$FAIL/$TOTAL TESTS FAILED"
fi
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

exit $FAIL
