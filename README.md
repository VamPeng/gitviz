# GitViz

Kotlin + Compose Desktop + JGit 写的 git 可视化客户端，目前是第一版骨架（只读）。

## 功能
- 打开本地仓库（启动目录是仓库会自动打开，也可以点「打开仓库」选择）
- 左侧引用树：本地分支、远程、标签，点击跳到对应提交
- 工具栏搜索：同时搜提交、作者、哈希、分支和标签
- 带分支图的提交历史：分支、远程分支、标签、HEAD 标记
- 下方详情：点提交查看改动文件和增删行数，点文件看 diff（统一或并排，带行号）、文件历史和 Blame
- 浅色和深色主题，配色是石墨橙，左下角切换（目前只读，写操作是占位）
- 默认加载最近 5000 个提交（`GitRepo.MAX_COMMITS`）

## 运行
需要 JDK 17–21（Android Studio 自带的 JBR 21 就行）。

```bash
./gradlew run                              # 打开 gitviz 项目目录本身的仓库
./gradlew run --args="/path/to/your/repo"  # 指定仓库
./gradlew test                             # 跑测试
```

或者用 IntelliJ IDEA 打开本目录，运行 `gitviz.MainKt`。

调试写操作用练习仓库，不要用 gitviz 本身。脚本会生成带分支、合并冲突、储藏和未提交改动的仓库，`origin` 是本地裸仓库，推送不会碰到 GitHub，弄坏了重新跑一次就恢复：

```bash
./scripts/playground.sh
./gradlew run --args="/tmp/gitviz-playground"
```

打包安装包：`./gradlew packageDistributionForCurrentOS`

## 结构
| 文件 | 作用 |
|---|---|
| `GitRepo.kt` | git 读取层（JGit）：提交、引用、改动文件、diff |
| `GraphLayout.kt` | 分支图布局：给每个提交分配列，算出线怎么连 |
| `DiffLines.kt` | 给 diff 逐行分类（文件头、增、删、上下文） |
| `GraphCell.kt` | 画每一行的分支图 |
| `RefTree.kt` | 把引用名按 `/` 拆成树 |
| `Sidebar.kt` | 左侧引用树面板 |
| `Theme.kt` | 浅色和深色配色、字号、字体 |
| `Components.kt` | 图标、按钮、头像这些通用组件 |
| `CommitList.kt` | 提交列表 |
| `CommitDetail.kt` | 提交详情、diff、文件历史、Blame |
| `Main.kt` | 窗口、工具栏、状态栏 |

界面原型在 `docs/prototype/`，改界面前先看那里。`ScreenshotTest` 会把主界面渲染成图片存到 `build/screens/`。

git 层和界面层是分开的，以后换界面框架也能保留 `GitRepo` 和 `GraphLayout`。
