# GitViz

Kotlin + Compose Desktop + JGit 写的 git 可视化客户端，目前是第一版骨架（只读）。

## 功能
- 打开本地仓库（启动目录是仓库会自动打开，也可以点「打开仓库」选择）
- 带分支图的提交历史：分支、远程分支、标签、HEAD 标记
- 点提交查看改动文件，点文件查看 diff
- 默认加载最近 5000 个提交（`GitRepo.MAX_COMMITS`）

## 运行
需要 JDK 17–21（Android Studio 自带的 JBR 21 就行）。

```bash
./gradlew run                              # 打开 gitviz 项目目录本身的仓库
./gradlew run --args="/path/to/your/repo"  # 指定仓库
./gradlew test                             # 跑测试
```

或者用 IntelliJ IDEA 打开本目录，运行 `gitviz.MainKt`。

打包安装包：`./gradlew packageDistributionForCurrentOS`

## 结构
| 文件 | 作用 |
|---|---|
| `GitRepo.kt` | git 读取层（JGit）：提交、引用、改动文件、diff |
| `GraphLayout.kt` | 分支图布局：给每个提交分配列，算出线怎么连 |
| `DiffLines.kt` | 给 diff 逐行分类（文件头、增、删、上下文） |
| `GraphCell.kt` | 画每一行的分支图 |
| `Main.kt` | 窗口和界面 |

git 层和界面层是分开的，以后换界面框架也能保留 `GitRepo` 和 `GraphLayout`。
