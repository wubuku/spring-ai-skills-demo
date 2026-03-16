#!/usr/bin/env bash
#
# 回归测试脚本 - 启动应用并验证所有端点
# 用法: ./test.sh [--stop]
#   --stop  测试完成后停止已存在的服务（默认保留）
# 前置条件: .env 文件已配置（参见 README.md）
#

set -u

BASE_URL="http://localhost:8080"
APP_PID=""
PASS=0
FAIL=0
SERVICE_ALREADY_RUNNING=false
STOP_AFTER_TEST=false

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
    # 只有在指定 --stop 或者是我们自己启动的服务时才清理
    if [ "$STOP_AFTER_TEST" = "true" ] || [ "$SERVICE_ALREADY_RUNNING" = "false" ]; then
        lsof -ti:8080 | xargs kill -9 2>/dev/null
    fi
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

# 生成认证 Token（base64(username:password)）
generate_token() {
    local username="$1"
    local password="$2"
    echo "$(echo -n "$username:$password" | base64)"
}

# ── 解析参数 ──────────────────────────────────────────────
for arg in "$@"; do
    case $arg in
        --stop) STOP_AFTER_TEST=true ;;
    esac
done

# ── 加载环境变量 ──────────────────────────────────────────
if [ ! -f .env ]; then
    red "错误: .env 文件不存在，请参考 README.md 创建"
    exit 1
fi
set -a && source .env && set +a

# ── 检查服务是否已运行 ────────────────────────────────────
MAX_WAIT=60
WAITED=0

if check_service_health; then
    yellow "检测到端口 8080 已有服务运行"
    SERVICE_ALREADY_RUNNING=true
    green "使用现有服务执行测试"
    echo ""
else
    # ── 确保端口空闲 ──────────────────────────────────────
    lsof -ti:8080 | xargs kill -9 2>/dev/null
    sleep 1

    # ── 启动应用 ──────────────────────────────────────────
    bold "Starting application..."
    # 强制 JDK HttpClient 使用 HTTP/1.1，规避 HTTP/2 EOF 问题
    MAVEN_OPTS="-Djdk.httpclient.HttpClient.log=errors -Djava.net.preferIPv4Stack=true" \
    mvn spring-boot:run -DskipTests > /tmp/spring-ai-test.log 2>&1 &
    APP_PID=$!

    # 等待应用启动
    while [ $WAITED -lt $MAX_WAIT ]; do
        if check_service_health; then
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
fi

# ══════════════════════════════════════════════════════════
#  认证设置
# ══════════════════════════════════════════════════════════
bold "[AUTH] 获取测试用户 Token"

# 使用 user1 用户进行测试
TEST_TOKEN=$(generate_token "user1" "password1")

# 验证 Token
resp=$(curl -s -X GET "$BASE_URL/api/auth/verify" \
    -H "Authorization: Bearer $TEST_TOKEN")
if echo "$resp" | grep -q "valid"; then
    green "  ✓ 获取 Token 成功: user1"
else
    red "  ✗ Token 验证失败: $resp"
    FAIL=$((FAIL + 1))
fi
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
bold "[TEST 3] REST API - 购物车 & 结算（需要认证）"

# 使用认证后的 API 调用（不再需要 userId 参数）
resp=$(curl -s -X POST "$BASE_URL/api/products/cart?productId=3" \
    -H "Authorization: Bearer $TEST_TOKEN")
assert_contains "加入购物车成功" "$resp" '"success":true'
assert_contains "包含 cartSize" "$resp" "cartSize"

resp=$(curl -s -X POST "$BASE_URL/api/products/checkout" \
    -H "Authorization: Bearer $TEST_TOKEN")
assert_contains "结算成功" "$resp" '"success":true'
assert_contains "包含 totalAmount" "$resp" "totalAmount"

# 再次结算应该失败（购物车已清空）
resp=$(curl -s -X POST "$BASE_URL/api/products/checkout" \
    -H "Authorization: Bearer $TEST_TOKEN")
assert_contains "空购物车结算失败" "$resp" '"success":false'

# 测试未认证访问应该失败（返回 403）
resp=$(curl -s -w "%{http_code}" -o /dev/null -X POST "$BASE_URL/api/products/cart?productId=3")
assert_contains "未认证访问应失败" "$resp" "403"

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
#  TEST 5B: JWT Token 透传测试 (需要 LLM API)
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 5B] Agent 聊天 - JWT Token 透传测试"
echo "  (验证 JWT 透传到内部 REST API 调用)"
echo "  (调用 LLM API，可能需要 30-90 秒...)"

# 先添加商品到购物车，确保有内容可查
resp=$(curl -s -X POST "$BASE_URL/api/products/cart?productId=3" \
    -H "Authorization: Bearer $TEST_TOKEN")
echo "  预先添加商品: $resp"

# 通过 Agent 调用查看购物车技能（查询操作，不需要确认）
# 在确认模式下，查询操作可以直接执行
resp=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TEST_TOKEN" \
    -d '{"content":"查看我的购物车内容"}' \
    --max-time 120)

assert_not_empty "带 JWT 的聊天端点有响应" "$resp"

# 验证购物车是否能被查询到（通过 JWT 透传调用了内部 API）
cart_resp=$(curl -s "$BASE_URL/api/products/cart" \
    -H "Authorization: Bearer $TEST_TOKEN")

if echo "$cart_resp" | grep -q "itemCount.*[1-9]"; then
    green "  ✓ JWT 透传成功！Agent 调用内部 API 时正确传递了用户认证"
    PASS=$((PASS + 1))
else
    red "  ✗ JWT 透传可能失败: 购物车未被正确查询"
    red "    cart_resp: $(echo "$cart_resp" | head -c 200)"
    FAIL=$((FAIL + 1))
fi

# ══════════════════════════════════════════════════════════
#  TEST 6: API 结果解释 (explain-result)
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 6] API 结果解释 - explain-result 端点"

# 6a: 测试成功响应的解释
echo "  (调用 LLM API，可能需要 10-30 秒...)"
resp=$(curl -s -X POST "$BASE_URL/api/explain-result" \
    -H "Content-Type: application/json" \
    -d '{
        "method": "GET",
        "url": "/api/products",
        "statusCode": 200,
        "responseBody": "[{\"id\":1,\"name\":\"iPhone 15\",\"price\":6999}]"
    }' \
    --max-time 60)
assert_not_empty "explain-result 端点有响应" "$resp"
assert_contains "解释结果包含 Markdown 格式" "$resp" "✅\|❌\|操作"

# 6b: 测试路径参数匹配（关键功能）
echo "  (测试路径参数匹配，可能需要 10-30 秒...)"
resp=$(curl -s -X POST "$BASE_URL/api/explain-result" \
    -H "Content-Type: application/json" \
    -d '{
        "method": "GET",
        "url": "/api/products/123",
        "statusCode": 200,
        "responseBody": "{\"id\":123,\"name\":\"Test Product\",\"price\":999}"
    }' \
    --max-time 60)
assert_not_empty "路径参数匹配有响应" "$resp"
# 应该能够匹配到 Skill 中定义的 /api/products/{id}

# 6c: 测试错误响应的解释
echo "  (测试错误响应解释，可能需要 10-30 秒...)"
resp=$(curl -s -X POST "$BASE_URL/api/explain-result" \
    -H "Content-Type: application/json" \
    -d '{
        "method": "POST",
        "url": "/api/products/checkout",
        "queryParams": {"userId": "999"},
        "statusCode": 400,
        "responseBody": "{\"success\":false,\"message\":\"购物车为空\"}"
    }' \
    --max-time 60)
assert_not_empty "错误响应解释有响应" "$resp"
assert_contains "错误解释包含失败标识" "$resp" "❌\|失败\|错误"

# ══════════════════════════════════════════════════════════
#  TEST 7: 确认模式 (confirm-before-mutate)
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 7] 确认模式 - 前端执行场景"

if ! command -v jq &>/dev/null; then
    red "  ⚠ 跳过 TEST 6: 需要安装 jq"
    FAIL=$((FAIL + 1))
elif [ "$SERVICE_ALREADY_RUNNING" = "true" ]; then
    yellow "  ⚠ 跳过 TEST 6: 使用现有服务无法测试确认模式重启"
    yellow "    (如需测试确认模式，请先停止现有服务后重新运行)"
else
    # 停止当前应用
    bold "  Restarting with confirm-before-mutate=true..."
    kill "$APP_PID" 2>/dev/null
    wait "$APP_PID" 2>/dev/null
    APP_PID=""
    lsof -ti:8080 | xargs kill -9 2>/dev/null
    sleep 2

    # 以确认模式重启
    MAVEN_OPTS="-Djdk.httpclient.HttpClient.log=errors -Djava.net.preferIPv4Stack=true" \
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

        # 重新获取 Token（因为应用重启了）
        TEST_TOKEN=$(generate_token "user1" "password1")

        # 6a: REST API 仍然正常工作（确认模式不影响直接 API 调用）
        resp=$(curl -s -X POST "$BASE_URL/api/products/cart?productId=3" \
            -H "Authorization: Bearer $TEST_TOKEN")
        assert_contains "确认模式下 REST API 仍可用" "$resp" '"success":true'
        # 清空购物车
        curl -s -X POST "$BASE_URL/api/products/checkout" \
            -H "Authorization: Bearer $TEST_TOKEN" > /dev/null

        # 6b: Agent 聊天 - 变更操作应返回 http-request 代码块
        echo "  (调用 LLM API，可能需要 30-90 秒...)"
        resp=$(curl -s -X POST "$BASE_URL/api/chat" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $TEST_TOKEN" \
            -d '{"content":"帮我使用 add-to-cart 技能把商品ID=3加入购物车"}' \
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
            # 优先使用 queryParams，其次使用 params
            req_params=$(echo "$request_json" | jq -r '.queryParams // .params // {} | to_entries | map("\(.key)=\(.value)") | join("&")')
            req_body=$(echo "$request_json" | jq -r '.body // empty')

            # 调试：显示原始 URL 和参数
            echo "    DEBUG: req_url=$req_url req_params=$req_params"

            # 如果 URL 已经是完整路径，则不再拼接 BASE_URL
            if [[ "$req_url" == http://* ]] || [[ "$req_url" == https://* ]]; then
                full_url="$req_url"
            else
                full_url="$BASE_URL$req_url"
            fi
            if [ -n "$req_params" ]; then
                full_url="${full_url}?${req_params}"
            fi
            echo "    DEBUG: full_url=$full_url"

            # 执行请求（模拟前端执行，需要携带认证头）
            if [ -n "$req_body" ] && [ "$req_body" != "null" ]; then
                exec_resp=$(curl -s -X "$req_method" "$full_url" \
                    -H "Content-Type: application/json" \
                    -H "Authorization: Bearer $TEST_TOKEN" \
                    -d "$req_body")
            else
                exec_resp=$(curl -s -X "$req_method" "$full_url" \
                    -H "Content-Type: application/json" \
                    -H "Authorization: Bearer $TEST_TOKEN")
            fi
            echo "    DEBUG: exec_resp=$exec_resp"

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
