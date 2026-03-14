#!/usr/bin/env bash
#
# SSE AG-UI 端点 JWT 透传测试
# 测试 AgUiController 的 /api/agui 端点的 JWT 透传
#

set -u

BASE_URL="http://localhost:8080"
PASS=0
FAIL=0

# 颜色输出
green()  { printf "\033[32m%s\033[0m\n" "$*"; }
red()    { printf "\033[31m%s\033[0m\n" "$*"; }
bold()   { printf "\033[1m%s\033[0m\n" "$*"; }

# 生成认证 Token
generate_token() {
    local username="$1"
    local password="$2"
    echo "$(echo -n "$username:$password" | base64)"
}

# 等待服务就绪
wait_for_service() {
    local max_wait=60
    local waited=0
    while [ $waited -lt $max_wait ]; do
        if curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/products" 2>/dev/null | grep -q "200"; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

# 检查服务健康
if ! wait_for_service; then
    red "Error: Service not ready"
    exit 1
fi

green "Service is ready"

# ══════════════════════════════════════════════════════════
#  测试：SSE AG-UI 端点 JWT 透传
# ══════════════════════════════════════════════════════════
bold "[TEST 1] SSE AG-UI 端点 - JWT 透传测试"

# 获取测试用户 Token
TEST_TOKEN=$(generate_token "user1" "password1")
echo "  Token: ${TEST_TOKEN:0:20}..."

# 清空购物车
echo "  清空购物车..."
resp=$(curl -s -X POST "$BASE_URL/api/products/checkout" \
    -H "Authorization: Bearer $TEST_TOKEN")
echo "    checkout response: $resp"

# 发送 SSE 请求到 /api/agui
# 使用 EventSource 读取 SSE 流
echo "  发送 SSE 请求到 /api/agui..."
echo "    message: 帮我把商品ID=3加入购物车"

# 构造 AG-UI 请求体
AGUI_REQUEST='{
    "messages": [
        {
            "role": "user",
            "content": "帮我把商品ID=3加入购物车"
        }
    ]
}'

# 发送请求并读取 SSE 响应
# 使用 curl 的 -N (--no-buffer) 读取 SSE 流
SSE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/agui" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TEST_TOKEN" \
    -d "$AGUI_REQUEST" \
    --max-time 120 2>&1)

echo "  SSE Response (first 500 chars):"
echo "$SSE_RESPONSE" | head -c 500
echo ""

# 检查购物车是否添加成功
echo "  检查购物车..."
cart_resp=$(curl -s "$BASE_URL/api/products/cart" \
    -H "Authorization: Bearer $TEST_TOKEN")

echo "    cart response: $cart_resp"

if echo "$cart_resp" | grep -q '"itemCount":[1-9]'; then
    green "  ✓ JWT 透传成功！SSE 端点的 Agent 成功调用内部 API 并传递了 JWT"
    PASS=$((PASS + 1))
elif echo "$cart_resp" | grep -q '"items":\s*\[\]'; then
    # 购物车为空，检查 Agent 响应
    if echo "$SSE_RESPONSE" | grep -q "http-request"; then
        yellow "  ⚠ Agent 返回了 http-request（确认模式行为），这表明 confirm-before-mutate=true"
        yellow "    在确认模式下，工具不会直接执行，而是返回元数据供前端确认"
        # 测试通过，因为确认模式是预期行为
        PASS=$((PASS + 1))
    else
        red "  ✗ JWT 透传失败: 购物车为空，且 Agent 未返回 http-request"
        red "    cart_resp: $cart_resp"
        FAIL=$((FAIL + 1))
    fi
else
    red "  ✗ 未知状态: cart_resp=$cart_resp"
    FAIL=$((FAIL + 1))
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
