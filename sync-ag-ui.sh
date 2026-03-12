#!/bin/bash

# 同步 ag-ui-4j 代码到 src 目录
# 这个脚本将 ag-ui-4j 子模块中的 AG-UI 相关代码复制到 src 目录

set -e

# 定义源目录和目标目录
AG_UI_SOURCE="ag-ui-4j/servers/spring/src/main/java/com/agui"
TARGET_DIR="src/main/java/com/agui"

echo "开始同步 AG-UI 代码..."
echo "源目录: $AG_UI_SOURCE"
echo "目标目录: $TARGET_DIR"
echo ""

# 创建目标目录结构
mkdir -p "$TARGET_DIR/server/spring"
mkdir -p "$TARGET_DIR/integrations/spring-ai"

# 同步 Spring Server 相关文件
echo "正在同步 Spring Server 文件..."
cp -v "$AG_UI_SOURCE/server/spring/AgUiService.java" "$TARGET_DIR/server/spring/"
cp -v "$AG_UI_SOURCE/server/spring/AgUiParameters.java" "$TARGET_DIR/server/spring/"
cp -v "$AG_UI_SOURCE/server/spring/AgUiAutoConfiguration.java" "$TARGET_DIR/server/spring/"

# 同步 Spring AI 集成文件
echo "正在同步 Spring AI 集成文件..."
cp -v "$AG_UI_SOURCE/integrations/spring-ai/SpringAIAgent.java" "$TARGET_DIR/integrations/spring-ai/"
cp -v "$AG_UI_SOURCE/integrations/spring-ai/SpringAIAgentConfiguration.java" "$TARGET_DIR/integrations/spring-ai/"
cp -v "$AG_UI_SOURCE/integrations/spring-ai/AgentStreamer.java" "$TARGET_DIR/integrations/spring-ai/"

# 同步核心包文件
echo "正在同步核心包文件..."
CORE_SOURCE="ag-ui-4j/packages/core/src/main/java/com/agui/core"
CORE_TARGET="src/main/java/com/agui/core"
mkdir -p "$CORE_TARGET"

# 递归复制所有核心类
cp -rv "$CORE_SOURCE"/* "$CORE_TARGET/"

# 同步服务器包文件
SERVER_SOURCE="ag-ui-4j/packages/server/src/main/java/com/agui/server"
SERVER_TARGET="src/main/java/com/agui/server"
mkdir -p "$SERVER_TARGET"

# 递归复制所有服务器类
cp -rv "$SERVER_SOURCE"/* "$SERVER_TARGET/"

echo ""
echo "✅ 同步完成！"
echo ""
echo "已同步的文件:"
find "$TARGET_DIR" -name "*.java" | sort
echo ""
echo "提示: 如果有冲突，请手动解决"
