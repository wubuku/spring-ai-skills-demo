#!/usr/bin/env bash
#
# 添加或更新 ag-ui-4j 子模块
#
# 用法:
#   ./update-ag-ui.sh           # 添加或更新子模块
#   ./update-ag-ui.sh --commit  # 添加/更新后自动提交 gitlink
#   ./update-ag-ui.sh <路径>    # 指定子模块路径
#

set -e

# 配置
SUBMODULE_URL="https://github.com/Work-m8/ag-ui-4j.git"
SUBMODULE_PATH="${1:-ag-ui-4j}"
SUBMODULE_BRANCH="main"

# 解析参数
AUTO_COMMIT=false
if [ "$1" = "--commit" ] || [ "$2" = "--commit" ]; then
    AUTO_COMMIT=true
    # 如果第一个参数是 --commit，使用默认路径
    if [ "$1" = "--commit" ]; then
        SUBMODULE_PATH="ag-ui-4j"
    fi
fi

green()  { printf "\033[32m%s\033[0m\n" "$*"; }
yellow() { printf "\033[33m%s\033[0m\n" "$*"; }
red()    { printf "\033[31m%s\033[0m\n" "$*"; }
bold()   { printf "\033[1m%s\033[0m\n" "$*"; }
cyan()   { printf "\033[36m%s\033[0m\n" "$*"; }

# 检查是否在 git 仓库中
if [ ! -d ".git" ]; then
    red "错误: 当前目录不是 git 仓库"
    exit 1
fi

# 检查子模块是否已存在
if git config --file .gitmodules --get-regexp "path" 2>/dev/null | grep -q "^submodule\.${SUBMODULE_PATH//\//\\.}\.path ${SUBMODULE_PATH}$"; then
    bold "子模块已存在，更新到最新版本..."
    echo ""

    # 更新子模块
    git submodule update --init --remote --merge "$SUBMODULE_PATH"

    # 获取最新提交信息
    cd "$SUBMODULE_PATH"
    LATEST_COMMIT=$(git log -1 --oneline)
    LATEST_DATE=$(git log -1 --format="%ci")
    cd - > /dev/null

    green "✓ 子模块已更新到最新版本"
    echo ""
    echo "  路径: $SUBMODULE_PATH"
    echo "  分支: $SUBMODULE_BRANCH"
    echo "  最新提交: $LATEST_COMMIT"
    echo "  提交时间: $LATEST_DATE"

    # 检查是否有变更需要提交
    if git diff --quiet "$SUBMODULE_PATH" 2>/dev/null; then
        echo ""
        cyan "子模块指针未变化，无需提交"
    else
        echo ""
        yellow "子模块指针已更新，需要提交："
        echo ""
        cyan "# Git 子模块说明："
        echo "#   - .gitmodules: 子模块配置文件（记录 URL、路径、分支）"
        echo "#   - $SUBMODULE_PATH: gitlink 指针（记录子模块的特定 commit SHA）"
        echo "#   - 两者都需要提交，其他开发者才能获取相同版本"
        echo ""
        echo "  git add .gitmodules $SUBMODULE_PATH"
        echo "  git commit -m \"chore: 更新子模块 $SUBMODULE_PATH 到最新版本\""
        echo ""

        if [ "$AUTO_COMMIT" = true ]; then
            bold "自动提交中..."
            git add .gitmodules "$SUBMODULE_PATH"
            git commit -m "chore: 更新子模块 $SUBMODULE_PATH 到最新版本"
            green "✓ 已提交"
        fi
    fi
else
    bold "子模块不存在，添加新子模块..."
    echo ""

    # 添加子模块
    git submodule add -b "$SUBMODULE_BRANCH" "$SUBMODULE_URL" "$SUBMODULE_PATH"

    # 初始化并更新子模块
    git submodule update --init "$SUBMODULE_PATH"

    # 获取最新提交信息
    cd "$SUBMODULE_PATH"
    LATEST_COMMIT=$(git log -1 --oneline)
    LATEST_DATE=$(git log -1 --format="%ci")
    cd - > /dev/null

    green "✓ 子模块添加成功"
    echo ""
    echo "  URL: $SUBMODULE_URL"
    echo "  路径: $SUBMODULE_PATH"
    echo "  分支: $SUBMODULE_BRANCH"
    echo "  最新提交: $LATEST_COMMIT"
    echo "  提交时间: $LATEST_DATE"
    echo ""
    yellow "需要提交 gitlink 指针到主仓库："
    echo ""
    cyan "# Git 子模块说明："
    echo "#   - .gitmodules: 子模块配置文件（记录 URL、路径、分支）"
    echo "#   - $SUBMODULE_PATH: gitlink 指针（160000 模式，记录子模块的特定 commit SHA）"
    echo "#   - 类似 npm 的 package-lock.json，锁定子模块的特定版本"
    echo "#   - 其他开发者 clone 后运行 'git submodule update --init' 即可获得相同版本"
    echo ""
    echo "  git add .gitmodules $SUBMODULE_PATH"
    echo "  git commit -m \"chore: 添加子模块 $SUBMODULE_PATH\""
    echo ""

    if [ "$AUTO_COMMIT" = true ]; then
        bold "自动提交中..."
        git add .gitmodules "$SUBMODULE_PATH"
        git commit -m "chore: 添加子模块 $SUBMODULE_PATH"
        green "✓ 已提交"
    fi
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
bold "子模块状态:"
git submodule status
echo ""
cyan "其他开发者同步子模块："
echo "  git submodule update --init --recursive"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
