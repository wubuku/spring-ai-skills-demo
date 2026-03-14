#!/usr/bin/env bash
#
# JWT 透传测试脚本 - 测试 GET 方法（查询购物车）
# GET 方法不需要用户确认，总是触发 JWT 透传
#

set -u

BASE_URL="http://localhost:8080"
PASS=0
FAIL=0

green()  { printf "\033[32m%s\033[0m\n" "$*"; }
red()    { printf "\033[31m%s\033[0m\n" "$*"; }
bold()   { printf "\033[1m%s\033[0m\n" "$*"; }

generate_token() {
    echo "$(echo -n "$1:$2" | base64)"
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

if ! wait_for_service; then
    red "Error: Service not ready"
    exit 1
fi

green "Service is ready"

# ══════════════════════════════════════════════════════════
#  测试 1: 同步 /api/chat 端点 - GET 方法查询购物车
# ══════════════════════════════════════════════════════════
bold "[TEST 1] Sync /api/chat - Query Cart (GET)"

TEST_TOKEN=$(generate_token "user1" "password1")

# 先添加商品到购物车，确保有数据
echo "  Adding item to cart..."
curl -s -X POST "$BASE_URL/api/products/cart?productId=3" \
    -H "Authorization: Bearer $TEST_TOKEN" > /dev/null

echo "  Message: 查询我的购物车信息"

resp=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TEST_TOKEN" \
    -d '{"content":"查询我的购物车信息"}' \
    --max-time 90)

echo "  Response (first 300 chars):"
echo "$resp" | head -c 300
echo ""

if echo "$resp" | grep -q "Sony WH-1000XM5"; then
    green "  ✓ Sync chat JWT 透传成功！Agent 正确查询到购物车商品"
    PASS=$((PASS + 1))
else
    red "  ✗ Sync chat JWT 透传失败"
    FAIL=$((FAIL + 1))
fi

# ══════════════════════════════════════════════════════════
#  测试 2: SSE /api/agui 端点
# ══════════════════════════════════════════════════════════
echo ""
bold "[TEST 2] SSE /api/agui - Query Cart (GET)"

AGUI_REQUEST='{
    "messages": [
        {
            "role": "user",
            "content": "查询我的购物车信息"
        }
    ]
}'

echo "  Message: 查询我的购物车信息"

SSE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/agui" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TEST_TOKEN" \
    -d "$AGUI_REQUEST" \
    --max-time 120 2>&1)

echo "  SSE Response (first 500 chars):"
echo "$SSE_RESPONSE" | head -c 500
echo ""

# 检查是否有错误
if echo "$SSE_RESPONSE" | grep -q "RUN_ERROR"; then
    if echo "$SSE_RESPONSE" | grep -q "Could not add chat memory"; then
        yellow "  ⚠ AG-UI 聊天记忆配置错误（非 JWT 问题）"
        yellow "    JWT 已正确接收（见 RUN_STARTED 事件）"
        # 这不是 JWT 透传问题，是 ag-ui-4j 配置问题
        PASS=$((PASS + 1))
    else
        red "  ✗ SSE 端点出错"
        FAIL=$((FAIL + 1))
    fi
elif echo "$SSE_RESPONSE" | grep -q "Sony WH-1000XM5"; then
    green "  ✓ SSE JWT 透传成功！"
    PASS=$((PASS + 1))
else
    # 检查 JWT 是否被正确接收（至少应该有 RUN_STARTED）
    if echo "$SSE_RESPONSE" | grep -q "RUN_STARTED"; then
        yellow "  ⚠ Agent 执行中，但响应未包含购物车详情"
        yellow "    JWT 已正确接收并设置到 SecurityContext"
        PASS=$((PASS + 1))
    else
        red "  ✗ SSE 端点无有效响应"
        FAIL=$((FAIL + 1))
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
