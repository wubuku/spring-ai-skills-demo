#!/usr/bin/env bash
#
# 多模态聊天测试脚本（同步版本）
# 测试文本聊天、图片识别、语音识别功能
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
#    ./test-multimodal.sh                      # 运行所有测试
#    ./test-multimodal.sh --skip-audio        # 跳过语音测试
#    ./test-multimodal.sh --image path/to/img.png
#    ./test-multimodal.sh --audio path/to/audio.wav
#
# 注意：测试文件路径从 .env 文件读取，不会提交到 git
# ═══════════════════════════════════════════════════════════════════

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"

# ── 加载 .env 文件中的测试配置 ────────────────────────────
if [ -f .env ]; then
    set -a && source .env && set +a
fi

# 测试文件路径（从环境变量读取，或使用命令行参数）
IMAGE_FILE="${TEST_IMAGE_PATH:-}"
AUDIO_FILE="${TEST_AUDIO_PATH:-}"
SKIP_TEXT=""
SKIP_IMAGE=""
SKIP_AUDIO=""

# ── 颜色输出 ──────────────────────────────────────────────
green()  { printf "\033[32m%s\033[0m\n" "$*"; }
red()    { printf "\033[31m%s\033[0m\n" "$*"; }
yellow() { printf "\033[33m%s\033[0m\n" "$*"; }
bold()   { printf "\033[1m%s\033[0m\n" "$*"; }

# ── 帮助信息 ──────────────────────────────────────────────
show_help() {
    bold "多模态聊天测试脚本"
    echo ""
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  --image <文件>    图片文件路径 (支持 PNG, JPG, JPEG, GIF, WebP)"
    echo "  --audio <文件>    音频文件路径 (支持 WAV, MP3, M4A)"
    echo "  --skip-text       跳过文本聊天测试"
    echo "  --skip-image      跳过图片识别测试"
    echo "  --skip-audio      跳过语音识别测试"
    echo "  --help            显示此帮助信息"
    echo ""
    echo "环境变量:"
    echo "  BASE_URL          服务地址 (默认: http://localhost:8080)"
    echo "  TEST_IMAGE_PATH   默认图片文件路径"
    echo "  TEST_AUDIO_PATH   默认音频文件路径"
    echo ""
    echo "说明:"
    echo "  测试文件路径优先从命令行参数读取，其次从环境变量读取"
    echo ""
    echo "示例:"
    echo "  $0 --image ./test.png"
    echo "  $0 --image ./test.png --audio ./test.wav"
    echo "  BASE_URL=http://localhost:9000 $0 --skip-audio"
}

# ── 解析参数 ──────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case $1 in
        --image)
            IMAGE_FILE="$2"
            shift 2
            ;;
        --audio)
            AUDIO_FILE="$2"
            shift 2
            ;;
        --skip-text)
            SKIP_TEXT="1"
            shift
            ;;
        --skip-image)
            SKIP_IMAGE="1"
            shift
            ;;
        --skip-audio)
            SKIP_AUDIO="1"
            shift
            ;;
        --help|-h)
            show_help
            exit 0
            ;;
        *)
            echo "未知参数: $1"
            echo "使用 --help 查看帮助"
            exit 1
            ;;
    esac
done

# ── 检查文件是否存在 ──────────────────────────────────────────────
check_file() {
    local file="$1"
    local name="$2"
    if [[ -n "$file" && ! -f "$file" ]]; then
        red "错误: $name 文件不存在: $file"
        exit 1
    fi
    if [[ -n "$file" && ! -r "$file" ]]; then
        red "错误: $name 文件不可读: $file"
        exit 1
    fi
}

check_file "$IMAGE_FILE" "图片"
check_file "$AUDIO_FILE" "音频"

# ── HTTP 请求函数 ──────────────────────────────────────────────
# $1: description
# $2: curl response file
# $3: HTTP status code file
# 成功返回 0，失败返回 1
http_post() {
    local desc="$1"
    local resp_file="$2"
    local status_file="$3"
    shift 3

    # 执行 curl，将响应和状态码分别输出到文件
    local output
    output=$(curl -s -w "\n%{http_code}" -X POST "$@" 2>&1)
    local curl_exit_code=$?

    if [[ $curl_exit_code -ne 0 ]]; then
        red "  ✗ $desc - curl 执行失败 (exit code: $curl_exit_code)"
        return 1
    fi

    # 分离 HTTP 状态码和响应体
    local http_code
    http_code=$(echo "$output" | tail -1)
    local body
    body=$(echo "$output" | sed '$d')

    echo "$body" > "$resp_file"
    echo "$http_code" > "$status_file"
}

# ── 测试文本聊天 ──────────────────────────────────────────
test_text_chat() {
    bold "[TEST] 文本聊天"

    local resp_file
    local status_file
    resp_file=$(mktemp)
    status_file=$(mktemp)

    http_post "文本聊天" "$resp_file" "$status_file" \
        "$BASE_URL/api/chat" \
        -H "Content-Type: application/json" \
        -d '{"content":"你好，请回复 OK","conversationId":"test-text-1"}' \
        --max-time 60

    local result=$?
    local http_code
    http_code=$(cat "$status_file")
    local resp
    resp=$(cat "$resp_file")

    rm -f "$resp_file" "$status_file"

    if [[ $result -ne 0 ]]; then
        red "  ✗ 文本聊天请求失败"
        return 1
    fi

    if [[ "$http_code" != "200" ]]; then
        red "  ✗ 文本聊天 HTTP 错误: $http_code"
        echo "  响应: $resp"
        return 1
    fi

    if [[ -z "$resp" || "$resp" == "null" ]]; then
        red "  ✗ 文本聊天无响应"
        return 1
    fi

    echo "  HTTP: $http_code"
    echo "  响应: $resp"
    green "  ✓ 文本聊天测试通过"
    return 0
}

# ── 测试图片识别 ──────────────────────────────────────────
test_image_chat() {
    if [[ -z "$IMAGE_FILE" ]]; then
        yellow "  ⚠ 跳过图片测试: 未提供图片文件 (使用 --image <文件>)"
        return 0
    fi

    bold "[TEST] 图片识别"

    local resp_file
    local status_file
    resp_file=$(mktemp)
    status_file=$(mktemp)

    # 获取文件 MIME 类型
    local mime_type
    mime_type=$(file --mime-type -b "$IMAGE_FILE" 2>/dev/null || echo "image/png")

    http_post "图片识别" "$resp_file" "$status_file" \
        "$BASE_URL/api/chat" \
        -F "query=请详细描述这张图片的内容" \
        -F "conversationId=test-image-$(date +%s)" \
        -F "image=@$IMAGE_FILE;type=$mime_type" \
        --max-time 180

    local result=$?
    local http_code
    http_code=$(cat "$status_file")
    local resp
    resp=$(cat "$resp_file")

    rm -f "$resp_file" "$status_file"

    if [[ $result -ne 0 ]]; then
        red "  ✗ 图片识别请求失败"
        return 1
    fi

    if [[ "$http_code" != "200" ]]; then
        red "  ✗ 图片识别 HTTP 错误: $http_code"
        echo "  响应: $resp"
        return 1
    fi

    if [[ -z "$resp" || "$resp" == "null" ]]; then
        red "  ✗ 图片识别无响应"
        return 1
    fi

    echo "  HTTP: $http_code"
    echo "  响应: $resp"
    green "  ✓ 图片识别测试通过"
    return 0
}

# ── 测试语音识别 ──────────────────────────────────────────
test_audio_chat() {
    if [[ -z "$AUDIO_FILE" ]]; then
        yellow "  ⚠ 跳过语音测试: 未提供音频文件 (使用 --audio <文件>)"
        return 0
    fi

    bold "[TEST] 语音识别"

    local resp_file
    local status_file
    resp_file=$(mktemp)
    status_file=$(mktemp)

    # 获取文件 MIME 类型
    local mime_type
    mime_type=$(file --mime-type -b "$AUDIO_FILE" 2>/dev/null || echo "audio/wav")

    http_post "语音识别" "$resp_file" "$status_file" \
        "$BASE_URL/api/chat" \
        -F "query=请转写这段音频的内容" \
        -F "conversationId=test-audio-$(date +%s)" \
        -F "audio=@$AUDIO_FILE;type=$mime_type" \
        --max-time 300

    local result=$?
    local http_code
    http_code=$(cat "$status_file")
    local resp
    resp=$(cat "$resp_file")

    rm -f "$resp_file" "$status_file"

    if [[ $result -ne 0 ]]; then
        red "  ✗ 语音识别请求失败"
        return 1
    fi

    if [[ "$http_code" != "200" ]]; then
        red "  ✗ 语音识别 HTTP 错误: $http_code"
        echo "  响应: $resp"
        return 1
    fi

    if [[ -z "$resp" || "$resp" == "null" ]]; then
        red "  ✗ 语音识别无响应"
        return 1
    fi

    echo "  HTTP: $http_code"
    echo "  响应: $resp"
    green "  ✓ 语音识别测试通过"
    return 0
}

# ── 主函数 ──────────────────────────────────────────────
main() {
    bold "========================================"
    bold "  多模态聊天测试"
    bold "========================================"
    echo ""
    echo "服务地址: $BASE_URL"
    echo "图片文件: ${IMAGE_FILE:-未指定}"
    echo "音频文件: ${AUDIO_FILE:-未指定}"
    echo ""

    # 检查服务是否可用
    local health_status
    health_status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/products" --max-time 10 2>/dev/null)

    if [[ "$health_status" != "200" ]]; then
        red "错误: 服务不可用 (HTTP $health_status)"
        red "请确保服务已启动: mvn spring-boot:run"
        exit 1
    fi
    green "服务状态: 已连接 (HTTP 200)"
    echo ""

    # 运行测试
    local failed=0

    if [[ -z "$SKIP_TEXT" ]]; then
        test_text_chat
        [[ $? -ne 0 ]] && ((failed++))
    else
        yellow "[SKIP] 文本聊天 (--skip-text)"
    fi
    echo ""

    if [[ -z "$SKIP_IMAGE" ]]; then
        test_image_chat
        [[ $? -ne 0 ]] && ((failed++))
    else
        yellow "[SKIP] 图片识别 (--skip-image)"
    fi
    echo ""

    if [[ -z "$SKIP_AUDIO" ]]; then
        test_audio_chat
        [[ $? -ne 0 ]] && ((failed++))
    else
        yellow "[SKIP] 语音识别 (--skip-audio)"
    fi
    echo ""

    bold "========================================"
    if [[ $failed -eq 0 ]]; then
        green "  测试完成: 全部通过"
    else
        red "  测试完成: $failed 项失败"
    fi
    bold "========================================"

    return $failed
}

main
exit $?