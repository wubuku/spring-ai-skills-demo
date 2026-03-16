#!/bin/bash

# JWT 透传测试脚本 - 改进版
# 使用 Python 正确解析 SSE JSON

set -e

TOKEN=$(echo -n "user1:password1" | base64)
BASE_URL="http://localhost:8080"

echo "=============================================="
echo "  JWT 透传到 BoundedElastic 线程测试"
echo "=============================================="

echo ""
echo "=== 步骤1: 添加商品到购物车 (带 JWT) ==="
ADD_ITEM_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/products/cart?productId=1" \
    -H "Authorization: Bearer $TOKEN")

echo "添加商品响应: $ADD_ITEM_RESPONSE"

echo ""
echo "=== 步骤2: 确认购物车有商品 ==="
cart_before=$(curl -s "${BASE_URL}/api/products/cart" \
    -H "Authorization: Bearer $TOKEN")
echo "购物车内容: $cart_before"

# 提取关键信息用于验证
item_count=$(echo "$cart_before" | grep -o '"itemCount":[0-9]*' | grep -o '[0-9]*' || echo "0")
echo "购物车商品数量: $item_count"

echo ""
echo "=== 步骤3: 通过 /api/agui 端点查询购物车 (SSE) ==="
AGUI_REQUEST='{"messages":[{"role":"user","content":"我的购物车已经有产品了，请直接使用 httpRequest 工具查询购物车信息。你不需要另行获取登录凭证，调用时会透传访问令牌。"}]}'

# 保存原始 SSE 响应
SSE_RAW_FILE="/tmp/sse_response_$$.txt"
SSE_PARSED_FILE="/tmp/sse_parsed_$$.txt"

# 执行请求并保存原始响应和 HTTP 状态码
# 添加 --compressed 让 curl 自动处理 gzip 响应，避免 HTTP_CODE 包含乱码
HTTP_CODE=$(curl -s --compressed -o "$SSE_RAW_FILE" -w "%{http_code}" -X POST "${BASE_URL}/api/agui" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "$AGUI_REQUEST" \
    --max-time 120 2>&1)

# 检查 HTTP 状态码是否为 403
if [ "$HTTP_CODE" = "403" ]; then
    echo "❌ 错误：收到 403 Forbidden - JWT 未正确透传到 boundedElastic 线程"
    echo "HTTP 状态码: $HTTP_CODE"
    echo "响应内容:"
    cat "$SSE_RAW_FILE"
    rm -f "$SSE_RAW_FILE" "$SSE_PARSED_FILE"
    exit 1
fi

echo "HTTP 状态码: $HTTP_CODE"

echo ""
echo "--- 原始 SSE 响应 (前 2000 字符) ---"
head -c 2000 "$SSE_RAW_FILE"
echo ""

echo ""
echo "--- 解析 SSE 内容 (使用 Python) ---"

# 使用 Python 解析 SSE JSON，提取所有 delta 内容
python3 - "$SSE_RAW_FILE" "$SSE_PARSED_FILE" << 'PYTHON_SCRIPT'
import json
import sys

# 获取传入的文件路径
sse_raw_file = sys.argv[1]
sse_parsed_file = sys.argv[2]

# 读取原始 SSE 文件
with open(sse_raw_file, "r", encoding="utf-8") as f:
    raw_content = f.read()

total_content = ""
event_count = 0

# 逐行处理 SSE 响应
for line in raw_content.split('\n'):
    # 只处理 data: 开头的行
    if line.startswith('data: '):
        # 提取 JSON 部分 (去掉 "data: " 前缀)
        json_data = line[6:]  # 去掉 "data: "

        # 跳过空行和结束标记
        if not json_data or json_data == '[DONE]':
            continue

        try:
            # 解析 JSON
            data = json.loads(json_data)
            event_count += 1

            # 提取 delta 字段
            delta = data.get('delta', '')
            if delta:
                total_content += delta

                # 打印前几个事件用于调试
                if event_count <= 5:
                    print(f"[事件 {event_count}] 内容片段: {delta}")

        except json.JSONDecodeError:
            continue

print(f"\n--- 解析结果 ---")
print(f"SSE 事件总数: {event_count}")
print(f"合并后的内容:")
print(total_content)

# 保存结果供 bash 使用
with open(sse_parsed_file, "w", encoding="utf-8") as f:
    f.write(total_content)

# 同时保存事件数量
with open(sse_parsed_file + "_count.txt", "w") as f:
    f.write(str(event_count))
PYTHON_SCRIPT

# 读取 Python 解析结果
total_content=$(cat "$SSE_PARSED_FILE")
event_count=$(cat "${SSE_PARSED_FILE}_count.txt")

echo ""
echo "=== 步骤4: 最终检查购物车 ==="
cart_after=$(curl -s "${BASE_URL}/api/products/cart" \
    -H "Authorization: Bearer $TOKEN")
echo "最终购物车内容: $cart_after"

# 清理临时文件
rm -f "$SSE_RAW_FILE" "$SSE_PARSED_FILE" /tmp/sse_event_count_$$.txt

echo ""
echo "=============================================="
echo "  测试结果分析"
echo "=============================================="

# ══════════════════════════════════════════════════════════
#  结果汇总 - 直接打印响应让用户判断
# ══════════════════════════════════════════════════════════

echo ""
echo "=============================================="
echo "  测试完成 - 请自行判断结果"
echo "=============================================="
echo ""
echo "验证要点:"
echo "  1. SSE 事件数量: $event_count"
echo "  2. HTTP 状态码: $HTTP_CODE"
echo "  3. 购物车商品数量: $item_count"
echo ""
echo "请根据上述信息以及上方的 Agent 完整响应自行判断:"
echo "  - 如果响应中包含购物车数据（商品数量、总金额），说明 JWT 透传成功"
echo "  - 如果响应中提到 403/需要认证/无法访问等，说明 JWT 透传失败"
echo ""

# 清理临时文件
rm -f "$SSE_RAW_FILE" "$SSE_PARSED_FILE" /tmp/sse_event_count_$$.txt

exit 0
