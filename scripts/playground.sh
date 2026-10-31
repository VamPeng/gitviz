#!/usr/bin/env bash
# 生成调试写操作用的练习仓库，弄坏了重新跑一次就恢复。
# origin 是本地的裸仓库，推送不会碰到 GitHub。
#
#   ./scripts/playground.sh                  生成到 /tmp/gitviz-playground
#   ./scripts/playground.sh /some/empty/dir  生成到指定目录
#   ./gradlew run --args="/tmp/gitviz-playground"
set -euo pipefail

WORK="${1:-/tmp/gitviz-playground}"
ORIGIN="$WORK-origin.git"
UPSTREAM="$WORK-upstream"
MARKER=".git/gitviz-playground"

# 只删自己生成过的目录，防止传错路径把别的东西删了
if [ -f "$WORK/$MARKER" ]; then
    rm -rf "$WORK" "$ORIGIN"
elif [ -e "$ORIGIN" ] || { [ -e "$WORK" ] && [ -n "$(ls -A "$WORK")" ]; }; then
    echo "拒绝覆盖：$WORK 或 $ORIGIN 已存在，且不是这个脚本生成的" >&2
    exit 1
fi

day=0
commit() { # commit <作者> <说明>
    day=$((day + 1))
    local date
    date="2026-10-$(printf %02d "$day")T10:00:00+08:00"
    GIT_AUTHOR_DATE="$date" GIT_COMMITTER_DATE="$date" \
        git -c user.name="$1" -c user.email="$1@example.com" commit -q -m "$2"
}
write() { # write <文件> <内容>
    mkdir -p "$(dirname "$1")"
    printf '%b' "$2" > "$1"
    git add "$1"
}

git init -q --bare --initial-branch=main "$ORIGIN"
git init -q --initial-branch=main "$WORK"
cd "$WORK"
touch "$MARKER"
git config commit.gpgsign false
git config tag.gpgsign false
git config user.name "Playground"
git config user.email "playground@example.com"
git remote add origin "$ORIGIN"

# 主线和一个已合并的分支，让分支图有分叉和合并
write README.md "# Playground\n\n练习仓库，随便改。\n"
write src/app.kt "fun main() {\n    println(\"hello\")\n}\n"
commit Alice "Initial commit"
write src/config.kt "val retries = 3\nval timeout = 30\n"
commit Alice "Add config"
git tag v0.1.0

git checkout -q -b fix/timeout
write src/config.kt "val retries = 3\nval timeout = 60\n"
commit Bob "Raise timeout"
git checkout -q main
write README.md "# Playground\n\n练习仓库，随便改。\n\n## 用法\n\n运行 app.kt。\n"
commit Alice "Document usage"
day=$((day + 1))
GIT_AUTHOR_DATE="2026-10-0${day}T10:00:00+08:00" GIT_COMMITTER_DATE="2026-10-0${day}T10:00:00+08:00" \
    git -c user.name=Alice -c user.email=Alice@example.com merge -q --no-ff --no-edit fix/timeout
write src/app.kt "fun main() {\n    println(\"hello, world\")\n}\n"
commit Alice "Greet the world"
git tag -a v0.2.0 -m "第二个版本"

# 和 main 改同一行：把它合并进 main 会冲突
git checkout -q -b feature/conflict HEAD~1
write src/app.kt "fun main() {\n    println(\"hi there\")\n}\n"
commit Bob "Change greeting"

# 普通功能分支，合并不会冲突，也还没推送
git checkout -q -b feature/logging main
write src/log.kt "fun log(message: String) = println(message)\n"
commit Carol "Add logger"
write src/app.kt "fun main() {\n    log(\"hello, world\")\n}\n"
commit Carol "Use logger"

git checkout -q main
git push -q origin main fix/timeout feature/conflict --tags

# 远程多一个本地没有的提交：这时推送 main 会被拒绝，要先拉取
git clone -q "$ORIGIN" "$UPSTREAM"
(
    cd "$UPSTREAM"
    git config commit.gpgsign false
    write CHANGELOG.md "## 0.2.0\n\n- 问候语改了\n"
    day=20
    commit Dana "Add changelog"
    git push -q origin main
)
rm -rf "$UPSTREAM"
git fetch -q origin main
git update-ref refs/remotes/origin/main "$(git rev-parse FETCH_HEAD)"
git branch -q --set-upstream-to=origin/main main

# 本地比远程多一个提交，和上面合起来就是分叉
write src/config.kt "val retries = 5\nval timeout = 60\n"
day=21
commit Alice "Retry more"

# 一条储藏
printf '\n// 储藏里的实验代码\n' >> src/app.kt
git -c user.name=Playground -c user.email=playground@example.com stash push -q -m "试验新的问候语"

# 工作区：已暂存、未暂存、未跟踪各有一些
printf '\n更多说明。\n' >> README.md
git add README.md
printf 'val verbose = true\n' >> src/config.kt
echo "notes" > notes.txt

echo "练习仓库已生成：$WORK"
echo "运行：./gradlew run --args=\"$WORK\""
