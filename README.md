# GitViz

Kotlin + Compose Desktop + JGit 写的 git 可视化客户端，还在开发中。

> **功能路线：[docs/ROADMAP.md](docs/ROADMAP.md)**。功能分为基本、重要、非必要三层，每项都标了当前进度和优先级。做新功能前先看这里，做完后更新状态。

## 功能
- 教学模式：设置中开启，在操作反馈里查看和复制对应 Git 命令与说明；复杂本地恢复单独解释
- 统计和洞察：提交热力图、贡献者和热点文件；完整本地历史去重扫描，可选时间范围、刷新或取消
- 交互式 rebase：从提交右键或命令面板进入，编辑线性区间的顺序、说明、合并和删除；先隔离预览，再执行，可继续、中止或整次恢复
- 仓库工具 Beta（功能菜单末尾的扳手图标，页内标注「开发中」）：Worktree 创建／打开／移除，已配置 Submodule 初始化／更新，LFS 状态和手动下载，subtree 添加／更新，以及引用对比
- 打开本地仓库（启动目录是仓库会自动打开，也可以点「打开仓库」选择）
- 左侧引用树：本地分支、远程、标签，点击跳到对应提交
- 工具栏搜索：同时搜提交、作者、哈希、分支和标签
- 带分支图的提交历史：分支、远程分支、标签、HEAD 标记
- 底部浮层详情：默认选中第一条提交；首次上滑显示，切换提交直接刷新；顶部箭头展开覆盖整个主区域/收起、拖动顶边调高，列表尾部留白避免遮挡。点文件看 diff（统一或并排，带行号）、文件历史和 Blame
- 浅色和深色主题，配色是石墨橙，左下角切换
- 本地改动：暂存、取消暂存、丢弃、提交、修改上一次提交，文件有变化时自动刷新工作区
- 拉取和推送：调用系统的 git，沿用现有的 SSH 和钥匙串配置
- 操作反馈：蓝色进度、绿色成功、黄色需处理、红色失败；成功 5 秒后收起（悬停或展开详情时保留），错误手动关闭，详情可展开和复制，失败可按提示重试或处理冲突
- 身份验证：要密码时由 git 和 ssh 弹出 macOS 系统对话框，密码不经过应用，也不保存
- 仓库：工具栏切换最近的仓库、打开或克隆新仓库
- 操作保护与撤销：操作前保存恢复点，默认保留 30 天／2 GiB；需要核对影响、处理中断或调整容量时提供临时恢复处理入口
- Blame 追溯：来源路径／行号、上一版本区间、合并父选择、重命名和删除前历史，保留前后导航位置
- 引用对比：从「仓库工具 → 引用对比」进入；双向独有提交独立分页，固定解析时 OID；文件支持共同祖先或端点比较，支持交换方向与刷新
- 获取与拉取：独立 fetch，pull 支持 merge／rebase／ff-only；先获取再预览集成，策略按仓库保存
- 键盘：列表 j/k、上下键、Enter；Cmd/Ctrl+F 搜索、+R 刷新、+Enter 提交、+Z 请求撤销、+Shift+P 命令面板
- Diff 高亮：Kotlin、Java、JavaScript、TypeScript、JSON、Python；后台按完整旧／新源码分词，单侧超过 1 MiB 或 20,000 行回退纯文本，缓存上限 32 MiB
- 部分暂存：支持 hunk／单行暂存与取消暂存，统一和并排视图共享选择，工作区内容保持不变
- 分支拖拽：从侧边栏或图标签拖到目标分支，选择合并／变基并预览；切换分支和写操作统一记录
- 操作预览：merge、rebase、cherry-pick、reset 在隔离仓库演练；查看图形和三层文件差异，执行前检查预览是否过期
- Rebase：保留合并拓扑，暂停后继续、跳过、保留空提交或中止；支持重启恢复处理和整次撤销
- 设置：主题、提交列表行高、默认 diff 视图
- 文件：按目录浏览工作区，包括被 .gitignore 忽略的文件，标出每个文件的 git 状态；双击用系统程序打开
- 储藏：新建（包括未跟踪的文件）、查看、应用、弹出、删除
- 右键提交：检出、新建分支、打标签、合并、Cherry-pick、回滚、重置、复制
- 右键分支：切换、合并、重命名、删除；双击分支切换
- 解决冲突：显示共同祖先和双方来源，按块选择或全文编辑，切换文件保留草稿；保存前检查外部修改，二进制和删除冲突支持整文件处理
- 提交历史每页加载 2000 条（`GitRepo.PAGE_SIZE`），滚动到底部时继续加载

## 第三层功能的使用边界

AI、时间轴回放、托管平台、Reflog 和恢复记录页暂时隐藏日常入口，保留实现及数据，待打磨后再开放。撤销、自动备份及异常时必要的恢复处理入口继续可用。下述时间轴、平台和 AI 说明保留作后续开放参考。

各功能的设计、验证与剩余联调项见 [docs/PLAN.md](docs/PLAN.md)。时间轴最多载入 100,000 条提交，图中显示最近展开的 2,000 条；图上的分支名代表当前引用。交互式 rebase 只编辑当前本地分支的线性区间，最多 500 条，基准提交本身不参与编辑。

仓库工具只在手动操作时写入。Worktree 移除不会删除分支，也不允许移除含忽略文件或其他本地改动的目录；存在 `assume-unchanged`／`skip-worktree` 索引标志时，即使表面干净也拒绝移除，需先检查文件并处理标志。该目录生命周期不支持普通撤销。Submodule 更新到父仓库暂存区记录的提交，默认不递归；已初始化子模块更新前保留 `refs/gitviz/submodule-before/…`，恢复需在子模块内处理。Subtree 默认 squash，并使用现有恢复记录。LFS 需要系统安装 `git-lfs`，不自动安装或下载；对象缓存可用状态按文件存在及大小检查，不表示内容哈希已校验。

托管平台需要选择服务地址和项目，私有项目需填写令牌；令牌只保留在当前页面会话，离开页面或更换主机会清除。平台页只在点击刷新或翻页时访问服务，不自动读取其他应用的账户。

AI 需要手动开启，填写模型、完整 API 端点和令牌，选择 Responses API 或 Chat Completions 兼容协议。连接仅保存在应用会话中，更换端点会清除令牌。准备快照不会发送请求；检查／编辑输入并勾选发送确认后，点击生成才会发送到所选服务，可能产生服务费用。提交草稿仅使用暂存内容；分支摘要可从引用对比页进入。单次最多 200 个差异路径、每个文本 blob 64 KiB、输入 120,000 字符、分支提交摘要 100 条，省略部分会明确标注。已有草稿需确认替换，结果不会自动执行 Git 操作。

GitHub／GitLab、AI 协议、超时与取消已完成本地测试；真实账户／模型服务尚未联调。本机未安装 git-lfs，真实 LFS 下载尚未验证。

## 运行
需要 JDK 17–21（Android Studio 自带的 JBR 21 就行）和 Git 2.50+；旧版 Git 仍可浏览，但写操作会提示升级，以保证引用恢复的锁内校验。

```bash
./gradlew classes                          # 只编译，不启动界面
./gradlew test                             # 无窗口测试，包括离屏截图
```

macOS 日常界面调试使用下面的 Dev 安装流程，统一从 `/Applications/GitViz Dev.app` 启动。不要直接打开构建目录中的副本或额外启动 Java 界面进程。

开发运行和开发安装包使用蓝色 `DEV` 图标，窗口名为 `GitViz Dev`；正式安装版使用原来的米白琥珀色图标和 `GitViz` 名称。图标文件位于 `design/icon/`、`packaging/icons/` 和 `src/main/resources/`。

调试写操作用练习仓库，不要用 gitviz 本身。脚本会生成带分支、合并冲突、储藏和未提交改动的仓库，`origin` 是本地裸仓库，推送不会碰到 GitHub，弄坏了重新跑一次就恢复：

```bash
./scripts/playground.sh
open "/Applications/GitViz Dev.app" --args "/tmp/gitviz-playground"
```

启动参数仅在 Dev 尚未运行时生效；已有窗口可通过「打开仓库」选择练习仓库。

打包安装包：`./gradlew packageDistributionForCurrentOS`

macOS 安装独立开发版（可以和正式版同时运行；复制前先正常退出旧 Dev 并确认进程结束）：

```bash
./gradlew createDistributable -Pdev=true
ditto "build/compose/packages.noindex/dev/main/app/GitViz Dev.app" "/Applications/GitViz Dev.app"
open "/Applications/GitViz Dev.app"
```

打包产物统一放在 `build/compose/packages.noindex/`，避免 Spotlight 把构建副本也列成应用入口；日常只使用“应用程序”里的正式版和开发版。

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
| `CommitHistoryLayout.kt` | 提交列表底层、可展开和拖动的底部详情浮层 |
| `CommitDetail.kt` | 提交详情、diff、文件历史、Blame |
| `GitCli.kt` | 调用系统 git 执行所有写操作 |
| `GitProcess.kt`、`Operations.kt` | 字节输入输出、执行结果核对、仓库写入协调与操作事务 |
| `RecoveryStore.kt`、`RecoveryReferences.kt`、`RecoveryIndexLock.kt` | 持久化恢复点、引用旧值校验、index／配置锁和中断恢复 |
| `OperationPreview.kt`、`ExpectedTopology.kt`、`Rebase.kt` | 隔离演练、未验证的后续拓扑和 rebase 暂停流程 |
| `ReferenceComparison.kt`、`BlameTrace.kt` | 固定 OID 的引用对比与来源行连续追溯 |
| `PatchDocument.kt`、`DiffHighlight.kt` | 完整补丁与部分暂存、完整源码分词和高亮缓存 |
| `Commands.kt`、`BranchDrag.kt` | 共享命令可用状态、快捷键和分支拖拽 |
| `ChangesView.kt` | 本地改动和提交说明 |
| `StashView.kt` | 储藏列表和内容 |
| `FileTree.kt`、`FilesView.kt` | 读工作区目录和每个文件的 git 状态、文件页的界面 |
| `Conflicts.kt`、`ConflictView.kt` | 解析冲突标记、解决冲突的界面 |
| `Settings.kt` | 用户选项、最近的仓库、设置页 |
| `GitMenus.kt` | 提交和分支的右键菜单、确认对话框 |
| `NetBanner.kt` | 通用操作进度、结果、警告和失败反馈，以及完整输出和复制 |
| `Teaching.kt` | 关键操作命令记录、解释与敏感参数脱敏 |
| `Insights.kt`、`InsightsView.kt` | 历史统计、贡献者和热点文件 |
| `Replay.kt`、`ReplayView.kt` | 固定历史快照和只读时间轴播放 |
| `InteractiveRebase.kt`、`InteractiveRebaseView.kt` | 线性提交编辑、受控 todo、隔离预览和恢复接入 |
| `Worktrees.kt`、`Submodules.kt`、`Lfs.kt`、`Subtrees.kt`、`RepositoryToolsView.kt` | 扩展仓库工具及其操作边界 |
| `Forge.kt`、`ForgeView.kt`、`ServiceHttp.kt` | 托管平台只读适配与有超时、大小限制和取消的 HTTP 请求 |
| `Ai.kt`、`AiView.kt` | 受限改动快照、模型协议、发送确认与草稿保护 |
| `Main.kt` | 窗口、工具栏、状态栏 |

界面原型在 `docs/prototype/`，改界面前先看那里。`ScreenshotTest` 会把主界面渲染成图片存到 `build/screens/`。

git 层和界面层是分开的，以后换界面框架也能保留 `GitRepo` 和 `GraphLayout`。
