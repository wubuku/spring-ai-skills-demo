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
#  TEST 6: 确认模式 (confirm-before-mutate)
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 6] 确认模式 - 前端执行场景"

if ! command -v jq &>/dev/null; then
    red "  ⚠ 跳过 TEST 6: 需要安装 jq"
    FAIL=$((FAIL + 1))
else
    # 停止当前应用
    bold "  Restarting with confirm-before-mutate=true..."
    kill "$APP_PID" 2>/dev/null
    wait "$APP_PID" 2>/dev/null
    APP_PID=""
    lsof -ti:8080 | xargs kill -9 2>/dev/null
    sleep 2

    # 以确认模式重启
    mvn spring-boot:run -DskipTests \
        -Dspring-boot.run.arguments="--app.confirm-before-mutate=true" \
        > /tmp/spring-ai-test.log 2>&1 &
    APP_PID=$!

    WAITED=0
    while [ $WAITED -lt $MAX_WAIT ]; do
        if curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/products" 2>/dev/null | grep -q "200"; then
            break
        fi
        sleep 2
        WAITED=$((WAITED + 2))
    done

    if [ $WAITED -ge $MAX_WAIT ]; then
        red "  错误: 确认模式应用启动超时"
        FAIL=$((FAIL + 1))
    else
        green "  Application restarted in confirm mode (${WAITED}s)"

        # 6a: REST API 仍然正常工作（确认模式不影响直接 API 调用）
        resp=$(curl -s -X POST "$BASE_URL/api/products/cart?userId=1&productId=3")
        assert_contains "确认模式下 REST API 仍可用" "$resp" '"success":true'
        # 清空购物车
        curl -s -X POST "$BASE_URL/api/products/checkout?userId=1" > /dev/null

        # 6b: Agent 聊天 - 变更操作应返回 http-request 代码块
        echo "  (调用 LLM API，可能需要 30-90 秒...)"
        resp=$(curl -s -X POST "$BASE_URL/api/chat" \
            -H "Content-Type: application/json" \
            -d '{"content":"请把商品ID为3的商品加入购物车（用户ID=1）"}' \
            --max-time 120)

        assert_not_empty "确认模式聊天端点有响应" "$resp"
        assert_contains "响应包含 http-request 代码块" "$resp" "http-request"

        # 6c: 从响应中提取 http-request 代码块中的 JSON
        chat_text=$(echo "$resp" | jq -r '.response // empty')
        request_json=$(echo "$chat_text" | sed -n '/```http-request/,/```/{/```/d;p}')

        if [ -n "$request_json" ]; then
            green "  ✓ 成功提取请求元数据"
            PASS=$((PASS + 1))

            # 解析 JSON 并构造 curl 请求（模拟前端执行）
            req_method=$(echo "$request_json" | jq -r '.method')
            req_url=$(echo "$request_json" | jq -r '.url')
            req_params=$(echo "$request_json" | jq -r '.params // {} | to_entries | map("\(.key)=\(.value)") | join("&")')
            req_body=$(echo "$request_json" | jq -r '.body // empty')

            full_url="$BASE_URL$req_url"
            if [ -n "$req_params" ]; then
                full_url="${full_url}?${req_params}"
            fi

            # 执行请求
            if [ -n "$req_body" ] && [ "$req_body" != "null" ]; then
                exec_resp=$(curl -s -X "$req_method" "$full_url" \
                    -H "Content-Type: application/json" \
                    -d "$req_body")
            else
                exec_resp=$(curl -s -X "$req_method" "$full_url" \
                    -H "Content-Type: application/json")
            fi

            assert_contains "前端执行请求成功" "$exec_resp" '"success":true'
        else
            red "  ✗ 未能提取 http-request 元数据"
            red "    response: $(echo "$chat_text" | head -c 300)"
            FAIL=$((FAIL + 1))
        fi
    fi
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
    red "$FAIL/$TOTAL TESTS FAILED"
fi
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

exit $FAIL
