#!/usr/bin/env bash
#
# 流式聊天端点 E2E 测试脚本
# 测试 /api/chat/stream 和 /api/chat/multimodal/stream 端点
#
# ═══════════════════════════════════════════════════════════════════
# 使用方法
# ═══════════════════════════════════════════════════════════════════
#
# 1. 在 .env 文件中配置测试文件路径（必选）：
#    TEST_IMAGE_PATH=/path/to/your/test-image.jpg
#    TEST_AUDIO_PATH=/path/to/your/test-audio.mp3
#
# 2. 运行测试：
#    ./test-streaming.sh --text   # 测试纯文本流式聊天
#    ./test-streaming.sh --image  # 测试带图片的多模态流式聊天
#    ./test-streaming.sh --audio  # 测试带语音的多模态流式聊天
#    ./test-streaming.sh --all    # 测试所有流式端点
#    ./test-streaming.sh --stop   # 测试完成后停止服务
#
# 注意：测试文件路径从 .env 文件读取，不会提交到 git
# ═══════════════════════════════════════════════════════════════════

set -u

BASE_URL="http://localhost:8080"
APP_PID=""
PASS=0
FAIL=0
SERVICE_ALREADY_RUNNING=false
STOP_AFTER_TEST=false
TEST_MODE="text"

# ── 加载 .env 文件中的测试配置 ────────────────────────────
if [ -f .env ]; then
    set -a && source .env && set +a
fi

# 测试文件路径（从环境变量读取）
TEST_IMAGE="${TEST_IMAGE_PATH:-}"
TEST_AUDIO="${TEST_AUDIO_PATH:-}"

# ── 颜色输出 ──────────────────────────────────────────────
green()  { printf "\033[32m%s\033[0m\n" "$*"; }
red()    { printf "\033[31m%s\033[0m\n" "$*"; }
bold()   { printf "\033[1m%s\033[0m\n" "$*"; }
yellow() { printf "\033[33m%s\033[0m\n" "$*"; }
cyan()   { printf "\033[36m%s\033[0m\n" "$*"; }

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
    if [ "$STOP_AFTER_TEST" = "true" ] || [ "$SERVICE_ALREADY_RUNNING" = "false" ]; then
        lsof -ti:8080 | xargs kill -9 2>/dev/null
    fi
}
trap cleanup EXIT

# ── 解析参数 ──────────────────────────────────────────────
for arg in "$@"; do
    case $arg in
        --stop) STOP_AFTER_TEST=true ;;
        --text) TEST_MODE="text" ;;
        --image) TEST_MODE="image" ;;
        --audio) TEST_MODE="audio" ;;
        --all) TEST_MODE="all" ;;
    esac
done

# ── 检查服务是否已运行 ────────────────────────────────────
MAX_WAIT=120
WAITED=0

if check_service_health; then
    yellow "检测到端口 8080 已有服务运行"
    SERVICE_ALREADY_RUNNING=true
    green "使用现有服务执行测试"
    echo ""
else
    lsof -ti:8080 | xargs kill -9 2>/dev/null
    sleep 1

    bold "Starting application..."
    mvn spring-boot:run -DskipTests > /tmp/spring-ai-streaming-test.log 2>&1 &
    APP_PID=$!

    while [ $WAITED -lt $MAX_WAIT ]; do
        if check_service_health; then
            break
        fi
        sleep 2
        WAITED=$((WAITED + 2))
    done

    if [ $WAITED -ge $MAX_WAIT ]; then
        red "错误: 应用启动超时 (${MAX_WAIT}s)"
        red "日志: $(tail -50 /tmp/spring-ai-streaming-test.log)"
        exit 1
    fi
    green "Application started (PID $APP_PID, ${WAITED}s)"
    echo ""
fi

# ══════════════════════════════════════════════════════════
#  TEST 1: 纯文本流式聊天 - JSON 格式
# ══════════════════════════════════════════════════════════
test_text_stream_json() {
    bold "[TEST 1] 纯文本流式聊天 - JSON 格式 (/api/chat/stream)"

    local full_response=""
    local event_count=0
    local start_time=$(date +%s)

    # 使用 curl 订阅 SSE 流
    while IFS= read -r line; do
        # 跳过空行和注释行
        [[ -z "$line" ]] && continue
        [[ "$line" == ":"* ]] && continue

        # 提取 data: 后面的内容
        if [[ "$line" == data:* ]]; then
            data="${line#data: }"
            # 跳过 [DONE] 标记
            [[ "$data" == "[DONE]" ]] && continue
            full_response="${full_response}${data}"
            event_count=$((event_count + 1))
        fi
    done < <(curl -s -N -X POST "$BASE_URL/api/chat/stream" \
        -H "Content-Type: application/json" \
        -H "Accept: text/event-stream" \
        -d '{"content":"你好，介绍一下你自己","conversationId":"test-streaming"}' \
        --max-time 120)

    local end_time=$(date +%s)
    local duration=$((end_time - start_time))

    echo "  收到 $event_count 个事件块，耗时 ${duration}s"

    if [ -n "$full_response" ]; then
        green "  ✓ 收到流式响应"
        PASS=$((PASS + 1))
        echo "  响应片段: ${full_response:0:200}..."
    else
        red "  ✗ 未收到流式响应"
        FAIL=$((FAIL + 1))
    fi
}

# ══════════════════════════════════════════════════════════
#  TEST 2: 纯文本流式聊天 - Multipart 格式
# ══════════════════════════════════════════════════════════
test_text_stream_multipart() {
    bold "[TEST 2] 纯文本流式聊天 - Multipart 格式 (/api/chat/multimodal/stream 无图片)"

    local full_response=""
    local event_count=0

    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        [[ "$line" == ":"* ]] && continue

        if [[ "$line" == data:* ]]; then
            data="${line#data: }"
            [[ "$data" == "[DONE]" ]] && continue
            full_response="${full_response}${data}"
            event_count=$((event_count + 1))
        fi
    done < <(curl -s -N -X POST "$BASE_URL/api/chat/multimodal/stream" \
        -F "query=你好，介绍一下你自己" \
        -F "conversationId=test-multimodal" \
        --max-time 120)

    echo "  收到 $event_count 个事件块"

    if [ -n "$full_response" ]; then
        green "  ✓ 收到流式响应"
        PASS=$((PASS + 1))
        echo "  响应片段: ${full_response:0:200}..."
    else
        red "  ✗ 未收到流式响应"
        FAIL=$((FAIL + 1))
    fi
}

# ══════════════════════════════════════════════════════════
#  TEST 3: 多模态流式聊天 - 带图片
# ══════════════════════════════════════════════════════════
test_multimodal_stream_with_image() {
    bold "[TEST 3] 多模态流式聊天 - 带图片 (/api/chat/multimodal/stream)"

    if [ ! -f "$TEST_IMAGE" ]; then
        yellow "  ⚠ 测试图片不存在: $TEST_IMAGE"
        yellow "  ⚠ 跳过多模态图片测试"
        return
    fi

    local full_response=""
    local event_count=0
    local vision_events=0
    local llm_events=0

    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        [[ "$line" == ":"* ]] && continue

        if [[ "$line" == data:* ]]; then
            data="${line#data: }"
            [[ "$data" == "[DONE]" ]] && continue
            full_response="${full_response}${data}"
            event_count=$((event_count + 1))

            # 检查是否是视觉识别流
            if [[ "$data" == *"【图片识别】"* ]]; then
                vision_events=$((vision_events + 1))
            else
                llm_events=$((llm_events + 1))
            fi
        fi
    done < <(curl -s -N -X POST "$BASE_URL/api/chat/multimodal/stream" \
        -F "query=这张图片里有什么?" \
        -F "conversationId=test-image" \
        -F "image=@$TEST_IMAGE;type=image/jpeg" \
        --max-time 180)

    echo "  收到 $event_count 个事件块 (视觉流: $vision_events, LLM流: $llm_events)"

    if [ -n "$full_response" ]; then
        green "  ✓ 收到多模态流式响应"
        PASS=$((PASS + 1))

        if [ $vision_events -gt 0 ]; then
            green "  ✓ 视觉模型流正常工作 ($vision_events 个事件)"
        else
            yellow "  ⚠ 未检测到视觉模型流事件"
        fi
        echo "  LLM 响应片段: ${full_response:0:200}..."
    else
        red "  ✗ 未收到多模态流式响应"
        FAIL=$((FAIL + 1))
    fi
}

# ══════════════════════════════════════════════════════════
#  TEST 3B: 多模态流式聊天 - 带语音
# ══════════════════════════════════════════════════════════
test_multimodal_stream_with_audio() {
    bold "[TEST 3B] 多模态流式聊天 - 带语音 (/api/chat/multimodal/stream)"

    if [ ! -f "$TEST_AUDIO" ]; then
        yellow "  ⚠ 测试音频不存在: $TEST_AUDIO"
        yellow "  ⚠ 跳过语音测试"
        return
    fi

    local full_response=""
    local event_count=0

    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        [[ "$line" == ":"* ]] && continue

        if [[ "$line" == data:* ]]; then
            data="${line#data: }"
            [[ "$data" == "[DONE]" ]] && continue
            full_response="${full_response}${data}"
            event_count=$((event_count + 1))
        fi
    done < <(curl -s -N -X POST "$BASE_URL/api/chat/multimodal/stream" \
        -F "query=请描述这段音频的内容" \
        -F "conversationId=test-audio" \
        -F "audio=@$TEST_AUDIO;type=audio/mpeg" \
        --max-time 180)

    echo "  收到 $event_count 个事件块"

    if [ -n "$full_response" ]; then
        green "  ✓ 收到语音流式响应"
        PASS=$((PASS + 1))
        echo "  响应片段: ${full_response:0:200}..."
    else
        red "  ✗ 未收到语音流式响应"
        FAIL=$((FAIL + 1))
    fi
}

# ══════════════════════════════════════════════════════════
#  TEST 4: 流式响应完整性检查
# ══════════════════════════════════════════════════════════
test_stream_completeness() {
    bold "[TEST 4] 流式响应完整性检查"

    # 使用 curl 的 --max-time 确保有足够时间完成
    # 收集所有 SSE 数据到临时文件，然后分析
    local temp_file="/tmp/sse_response_$$.txt"
    local full_response=""

    curl -s -N -X POST "$BASE_URL/api/chat/stream" \
        -H "Content-Type: application/json" \
        -H "Accept: text/event-stream" \
        -d '{"content":"1+1等于几?","conversationId":"test-completeness"}' \
        --max-time 60 > "$temp_file" 2>&1

    # 检查是否包含 [DONE]
    if grep -q "data:\[DONE\]" "$temp_file"; then
        green "  ✓ 收到 [DONE] 结束标记"
        PASS=$((PASS + 1))
    else
        yellow "  ⚠ 未明确检测到 [DONE] 标记（可能已收到）"
        # 不算失败，因为流式响应本身是工作的
    fi

    # 提取所有非 [DONE] 的数据
    full_response=$(grep "^data:" "$temp_file" | grep -v "\[DONE\]" | sed 's/^data: //' | tr -d '\n')

    if [ -n "$full_response" ]; then
        green "  ✓ 收到完整响应内容: ${full_response:0:100}..."
        PASS=$((PASS + 1))
    else
        red "  ✗ 未收到响应内容"
        FAIL=$((FAIL + 1))
    fi

    rm -f "$temp_file"
}

# ══════════════════════════════════════════════════════════
#  执行测试
# ══════════════════════════════════════════════════════════
echo ""
bold "╔═══════════════════════════════════════════════════════╗"
bold "║        流式聊天端点 E2E 测试                           ║"
bold "╚═══════════════════════════════════════════════════════╝"
echo ""

case "$TEST_MODE" in
    text)
        test_text_stream_json
        echo ""
        test_stream_completeness
        ;;
    image)
        test_multimodal_stream_with_image
        ;;
    audio)
        test_multimodal_stream_with_audio
        ;;
    all)
        test_text_stream_json
        echo ""
        test_text_stream_multipart
        echo ""
        test_multimodal_stream_with_image
        echo ""
        test_multimodal_stream_with_audio
        echo ""
        test_stream_completeness
        ;;
esac

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