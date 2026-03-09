#!/usr/bin/env bash
#
# PetStore 回归测试脚本 - 启动应用并验证完整 Agent Skills 流程
# 用法: ./test-petstore.sh
# 前置条件: .env 文件已配置（参见 README.md）
#

set -u

BASE_URL="http://localhost:8080"
APP_PID=""
PASS=0
FAIL=0
MAX_WAIT=60

green()  { printf "\033[32m%s\033[0m\n" "$*"; }
red()    { printf "\033[31m%s\033[0m\n" "$*"; }
bold()   { printf "\033[1m%s\033[0m\n" "$*"; }

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

# 加载环境变量
if [ ! -f .env ]; then
    red "错误: .env 文件不存在，请参考 README.md 创建"
    exit 1
fi
set -a && source .env && set +a

# 确保端口空闲
lsof -ti:8080 | xargs kill -9 2>/dev/null
sleep 1

# 启动应用
bold "Starting application..."
mvn spring-boot:run -DskipTests > /tmp/spring-ai-petstore-test.log 2>&1 &
APP_PID=$!

# 等待启动
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    if curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v3/store/inventory" 2>/dev/null | grep -q "200"; then
        break
    fi
    sleep 2
    WAITED=$((WAITED + 2))
done

if [ $WAITED -ge $MAX_WAIT ]; then
    red "错误: 应用启动超时 (${MAX_WAIT}s)"
    red "日志: $(tail -20 /tmp/spring-ai-petstore-test.log)"
    exit 1
fi
green "Application started (PID $APP_PID, ${WAITED}s)"
echo ""

# ══════════════════════════════════════════════════════════
#  TEST 1: PetStore REST API
# ══════════════════════════════════════════════════════════
bold "[TEST 1] PetStore REST API - 直接端点验证"

resp=$(curl -s "$BASE_URL/api/v3/store/inventory")
assert_contains "GET /api/v3/store/inventory 返回库存" "$resp" "available"

resp=$(curl -s "$BASE_URL/api/v3/pet/findByStatus?status=available")
assert_contains "GET /pet/findByStatus 返回 available 宠物" "$resp" "available"

resp=$(curl -s "$BASE_URL/api/v3/pet/1")
assert_contains "GET /pet/1 返回宠物详情" "$resp" '"id"'

resp=$(curl -s -X POST "$BASE_URL/api/v3/pet" \
    -H "Content-Type: application/json" \
    -d '{"name":"Buddy","photoUrls":["http://example.com/buddy.jpg"],"status":"available"}')
assert_contains "POST /pet 添加新宠物" "$resp" "Buddy"

resp=$(curl -s "$BASE_URL/api/v3/user/user1")
assert_contains "GET /user/user1 返回用户" "$resp" "user1"

resp=$(curl -s -X POST "$BASE_URL/api/v3/store/order" \
    -H "Content-Type: application/json" \
    -d '{"petId":1,"quantity":1,"status":"placed","complete":false}')
assert_contains "POST /store/order 下单" "$resp" "placed"

echo ""
# ══════════════════════════════════════════════════════════
#  TEST 2: Agent 聊天 - 完整 Skills 流程
#  验证: loadSkill → readSkillReference → httpRequest
# ══════════════════════════════════════════════════════════
bold "[TEST 2] Agent 聊天 - PetStore Skills 完整流程"
echo "  (调用 LLM API，可能需要 30-90 秒...)"

resp=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"message":"帮我查找所有状态为 available 的宠物"}' \
    --max-time 120)
assert_not_empty "聊天端点有响应" "$resp"
assert_contains "返回包含 response 字段" "$resp" '"response"'
assert_contains "返回宠物列表信息" "$resp" "available\|宠物\|pet\|Pet"

echo ""
echo "  (第二个 Agent 测试，可能需要 30-90 秒...)"
resp=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"message":"帮我查一下 petId 为 1 的宠物详情"}' \
    --max-time 120)
assert_not_empty "查询宠物详情有响应" "$resp"
assert_contains "返回宠物详情" "$resp" '"response"'

echo ""
echo "  (第三个 Agent 测试 - 下单，可能需要 30-90 秒...)"
resp=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"message":"帮我为 petId=2 的宠物下一个订单，数量为1"}' \
    --max-time 120)
assert_not_empty "下宠物订单有响应" "$resp"
assert_contains "下单操作返回响应" "$resp" '"response"'

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
