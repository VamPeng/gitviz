package gitviz

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

private enum class RepositoryTool { WORKTREES, SUBMODULES, LFS, SUBTREE, COMPARE }

@Composable internal fun RepositoryToolsView(directory: File, cli: GitCli?, refs: List<RefTarget>, actions: GitActions, revision: Int, busy: Boolean,
                                            onOpen: (File) -> Unit, onOpenCommit: (String) -> Unit, modifier: Modifier = Modifier,
                                            onAi: ((CompareRequest) -> Unit)? = null) {
    var refresh by remember(directory) { mutableStateOf(0) }
    var tab by remember(directory) { mutableStateOf(if (cli == null) RepositoryTool.COMPARE else RepositoryTool.WORKTREES) }
    val tabs = if (cli == null) listOf(RepositoryTool.COMPARE) else RepositoryTool.entries
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Repository tools", "仓库工具"), fontSize = TypeScale.Heading)
            Spacer(Modifier.width(8.dp))
            Text(t("In development", "开发中"), fontSize = TypeScale.Small, color = Palette.Muted)
            Spacer(Modifier.weight(1f))
            if (tab != RepositoryTool.COMPARE) TextButton(enabled = !busy, onClick = { refresh++ }) { Text(t("Refresh", "刷新")) }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { tool ->
                val label = when (tool) {
                    RepositoryTool.WORKTREES -> "Worktrees"
                    RepositoryTool.SUBMODULES -> "Submodules"
                    RepositoryTool.LFS -> "Git LFS"
                    RepositoryTool.SUBTREE -> "Subtree"
                    RepositoryTool.COMPARE -> t("Compare refs", "引用对比")
                }
                TextButton(onClick = { tab = tool }) { Text(label, color = if (tab == tool) Palette.Accent else Palette.Muted) }
            }
        }
        HorizontalDivider()
        if (tab == RepositoryTool.COMPARE) {
            ComparisonView(directory, refs, onOpenCommit, Modifier.fillMaxSize(), onAi)
        } else if (cli != null) when (tab) {
            RepositoryTool.WORKTREES -> WorktreesView(cli, actions, revision + refresh, busy, onOpen, Modifier.fillMaxSize())
            RepositoryTool.SUBMODULES -> SubmodulesView(cli, actions, revision + refresh, busy, onOpen, Modifier.fillMaxSize())
            RepositoryTool.LFS -> LfsView(cli, actions, revision + refresh, busy, Modifier.fillMaxSize())
            RepositoryTool.SUBTREE -> SubtreesView(cli, actions, revision + refresh, busy, Modifier.fillMaxSize())
            RepositoryTool.COMPARE -> Unit
        }
    }
}

@Composable private fun SubtreesView(cli: GitCli, actions: GitActions, revision: Int, busy: Boolean, modifier: Modifier) {
    var prefix by remember(cli) { mutableStateOf("") }
    var remote by remember(cli) { mutableStateOf("") }
    var ref by remember(cli) { mutableStateOf("main") }
    var add by remember(cli) { mutableStateOf(true) }
    var squash by remember(cli) { mutableStateOf(true) }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Git subtree", fontSize = TypeScale.Title)
        Text(t("Fetch a branch or tag, then add or merge that fixed commit into a subdirectory. Creates a local commit; conflicts use the existing resolution page. No push or remote configuration change.", "获取分支或标签后，将固定提交添加或合并到子目录。会创建本地提交，冲突沿用现有解决页面；不推送，也不修改远端配置。"), fontSize = TypeScale.Small, color = Palette.Muted)
        OutlinedTextField(prefix, { prefix = it }, label = { Text(t("Subdirectory, e.g. vendor/library", "子目录，例如 vendor/library")) }, singleLine = true, modifier = Modifier.fillMaxWidth().textInputFocus(), enabled = !busy)
        OutlinedTextField(remote, { remote = it }, label = { Text(t("Source repository URL or path", "来源仓库 URL 或路径")) }, singleLine = true, modifier = Modifier.fillMaxWidth().textInputFocus(), enabled = !busy)
        OutlinedTextField(ref, { ref = it }, label = { Text(t("Source branch / tag / commit", "来源分支／标签／提交")) }, singleLine = true, modifier = Modifier.fillMaxWidth().textInputFocus(), enabled = !busy)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(add, { add = it }, enabled = !busy); Text(t("Add new subtree (off: update existing)", "添加新子树（关闭时更新已有子树）"), fontSize = TypeScale.Body)
            Spacer(Modifier.width(12.dp))
            Checkbox(squash, { squash = it }, enabled = !busy); Text(t("Squash source history", "压缩来源历史"), fontSize = TypeScale.Body)
        }
        Loaded(cli to revision, Modifier.fillMaxWidth(), { cli.subtreeAvailable() to cli.headIdentity() }) { (available, head) ->
            if (!available) Text(t("The installed Git does not include git-subtree. Install a Git distribution that provides it, then refresh.", "当前 Git 不包含 git-subtree，请安装含此工具的 Git 后刷新。"), color = Palette.Modified, fontSize = TypeScale.Small)
            else TextButton(enabled = !busy && prefix.isNotBlank() && remote.isNotBlank() && ref.isNotBlank(), onClick = {
                val request = SubtreeRequest(prefix, remote, ref, add, squash, head)
                val command = formatGitCommand(listOf("subtree", if (add) "add" else "pull", "--prefix=$prefix") + (if (squash) listOf("--squash") else emptyList()) + listOf(remote, ref))
                actions.ask(DialogSpec(t("Review subtree operation", "检查子树操作"), "$command\n\n" + t("Downloads from this source and changes the local index, files and history. A recovery point is saved first. The source tree must not contain submodules. Use Undo after completion; if an impact review is needed, follow the recovery issue action.", "将从此来源下载，并修改本地暂存区、文件和历史。操作前保存恢复点；来源树不能包含子模块。完成后可使用撤销；需要进一步核对时，会提供恢复问题处理入口。"), t("Execute", "执行")) { _, _ ->
                    actions.run(t("Updating subtree", "正在更新子树"), t("Subtree updated", "子树已更新")) { applySubtree(request) }
                })
            }) { Text(t("Review and execute…", "检查并执行…")) }
        }
    }
}

@Composable private fun LfsView(cli: GitCli, actions: GitActions, revision: Int, busy: Boolean, modifier: Modifier) {
    var remote by remember(cli) { mutableStateOf("origin") }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Git LFS", fontSize = TypeScale.Title)
        Text(t("Pointer metadata comes from the index. Objects are never downloaded while browsing. Cache presence checks size, not content hashes.", "指针信息来自暂存区，浏览时不下载对象。缓存检测仅核对大小，不验证内容哈希。"), color = Palette.Muted, fontSize = TypeScale.Small)
        Loaded(cli to revision, Modifier.weight(1f).fillMaxWidth(), { LfsClient(cli).inspect() }) { data ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(data.version ?: t("Git LFS is not installed. Install it from git-lfs.com, then refresh. GitViz does not install or initialize it automatically.", "尚未安装 Git LFS。请从 git-lfs.com 安装后刷新；GitViz 不会自动安装或初始化。"), color = if (data.version == null) Palette.Modified else Palette.Muted, fontSize = TypeScale.Small)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(remote, { remote = it }, label = { Text(t("Remote", "远端")) }, singleLine = true, modifier = Modifier.weight(1f).textInputFocus(), enabled = !busy)
                    TextButton(enabled = !busy && data.version != null && remote in data.remotes, onClick = {
                        val selected = remote
                        actions.ask(DialogSpec(t("Download LFS objects", "下载 LFS 对象"), "$selected\n${data.endpoints.joinToString("\n")}\n" + t("Download objects for the current ref and materialize its working files. Include/exclude filters are cleared for this request. The repository must be clean. Downloads may be large; object cache changes are not locally undoable.", "下载当前引用的对象并填充工作区文件。本次请求不限制包含／排除规则。仓库必须干净；下载可能较大，对象缓存变更无法本地撤销。"), t("Download", "下载")) { _, _ ->
                            actions.run(t("Downloading LFS objects", "正在下载 LFS 对象"), t("LFS download finished", "LFS 下载已完成")) { LfsClient(this).pull(data, selected) }
                        })
                    }) { Text(t("Download…", "下载…")) }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    item { Text(t("${data.files.size} pointer files", "${data.files.size} 个指针文件"), fontSize = TypeScale.Body, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(data.files, key = { it.path }) { file ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(file.path, fontSize = TypeScale.Body)
                            Text("${file.pointer.size} bytes · sha256:${file.pointer.oid}", fontSize = TypeScale.Small, fontFamily = CodeFont)
                            Text(file.working + " · " + when (file.cached) { true -> t("Cached", "已有缓存"); false -> t("Cache missing / size mismatch", "缓存缺失／大小不符"); null -> t("Custom cache location unknown", "未知自定义缓存位置") }, fontSize = TypeScale.Small, color = Palette.Muted)
                        }
                        HorizontalDivider()
                    }
                    data.attributes.forEach { (path, contents) -> item {
                        Text(t("Index attributes: ", "暂存区属性：") + path, fontSize = TypeScale.Body, modifier = Modifier.padding(top = 16.dp))
                        Text(contents, fontFamily = CodeFont, fontSize = TypeScale.Small, color = Palette.Muted)
                    } }
                }
            }
        }
    }
}

@Composable private fun SubmodulesView(cli: GitCli, actions: GitActions, revision: Int, busy: Boolean, onOpen: (File) -> Unit, modifier: Modifier) {
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("Submodules", "子模块"), fontSize = TypeScale.Title)
        Text(t("Update checks out the commit recorded in the parent index. It may download from the configured remote. Nested modules are not updated; no parent commit is made.", "更新会检出父仓库暂存区记录的提交，可能从配置的远端下载。不递归更新嵌套模块，也不提交父仓库。"), fontSize = TypeScale.Small, color = Palette.Muted)
        Loaded(cli to revision, Modifier.fillMaxWidth().weight(1f), { cli.submodules() }) { modules ->
            if (modules.isEmpty()) Message(t("No configured submodules", "没有已配置的子模块"), Modifier.fillMaxSize())
            else LazyColumn(Modifier.fillMaxSize()) {
                items(modules, key = { it.name }) { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.path, fontSize = TypeScale.Body)
                        Text("HEAD: ${item.head?.take(12) ?: t("Not initialized", "尚未初始化")} → ${item.target?.take(12) ?: "?"}", fontSize = TypeScale.Small)
                        Text(formatGitCommand(listOf(item.url)).removePrefix("git "), fontSize = TypeScale.Small, color = Palette.Muted)
                        if (item.dirty) Text(t("Local changes or an unfinished operation", "存在本地改动或未完成的操作"), color = Palette.Modified, fontSize = TypeScale.Small)
                        item.problem?.let { Text(it, color = Palette.Removed, fontSize = TypeScale.Small) }
                        Row {
                            TextButton(enabled = !busy && item.head != null && item.problem == null, onClick = { onOpen(cli.safeWorkingPath(item.path).toFile()) }) { Text(t("Open", "打开")) }
                            TextButton(enabled = !busy && item.ready, onClick = {
                                actions.ask(DialogSpec(t("Initialize / update submodule", "初始化／更新子模块"),
                                    "${item.path}\n${formatGitCommand(listOf(item.url)).removePrefix("git ")}\n${item.head?.take(12) ?: "—"} → ${item.target}\n" + t("May download objects and replace clean working files. Local changes and ignored files are protected. Previous commits are retained by a local reference; this does not use parent repository Undo.", "可能下载对象并替换干净的工作区文件。本地改动及忽略文件会受到保护。原提交保留在本地引用中；不使用父仓库的撤销功能。"), t("Update", "更新")) { _, _ ->
                                    actions.run(t("Updating submodule", "正在更新子模块"), t("Submodule updated", "子模块已更新")) { updateSubmodule(item) }
                                })
                            }) { Text(t("Initialize / update…", "初始化／更新…")) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable private fun WorktreesView(cli: GitCli, actions: GitActions, revision: Int, busy: Boolean, onOpen: (File) -> Unit, modifier: Modifier) {
    var path by remember(cli) { mutableStateOf("") }
    var branch by remember(cli) { mutableStateOf("") }
    var base by remember(cli) { mutableStateOf("HEAD") }
    var newBranch by remember(cli) { mutableStateOf(true) }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Worktrees", fontSize = TypeScale.Title)
        Text(t("Each worktree has its own files and index. Creation and removal do not have local Undo; removal keeps the branch.", "每个工作树有独立的文件和暂存区。创建与移除不支持本地撤销；移除后保留分支。"), color = Palette.Muted, fontSize = TypeScale.Small)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(path, { path = it }, label = { Text(t("New directory (absolute path)", "新目录（绝对路径）")) }, singleLine = true, modifier = Modifier.weight(1f).textInputFocus(), enabled = !busy)
            OutlinedTextField(branch, { branch = it }, label = { Text(t("Branch", "分支")) }, singleLine = true, modifier = Modifier.weight(1f).textInputFocus(), enabled = !busy)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(newBranch, { newBranch = it }, enabled = !busy)
            Text(t("Create branch", "新建分支"), fontSize = TypeScale.Body)
            if (newBranch) OutlinedTextField(base, { base = it }, label = { Text(t("Start at", "起点")) }, singleLine = true, modifier = Modifier.weight(1f).textInputFocus(), enabled = !busy)
            else Spacer(Modifier.weight(1f))
            TextButton(enabled = !busy && path.isNotBlank() && branch.isNotBlank(), onClick = {
                val target = path; val name = branch; val start = base; val create = newBranch
                actions.ask(DialogSpec(t("Create worktree", "创建工作树"), "$target\n$name\n" + t("Creates files in this directory.", "将在此目录创建文件。"), t("Create", "创建")) { _, _ ->
                    actions.run(t("Creating worktree", "正在创建工作树"), t("Worktree created", "工作树已创建")) { addWorktree(target, name, start, create) }
                })
            }) { Text(t("Create…", "创建…")) }
        }
        HorizontalDivider()
        Loaded(cli to revision, Modifier.weight(1f).fillMaxWidth(), { cli.worktrees() }) { trees ->
            LazyColumn(Modifier.fillMaxSize()) {
                items(trees, key = { it.path }) { tree ->
                    val current = File(tree.path).canonicalFile == cli.workTree.canonicalFile
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tree.branch?.removePrefix("refs/heads/") ?: t("Detached HEAD", "分离 HEAD"), fontSize = TypeScale.Body)
                        Text(tree.path, fontSize = TypeScale.Small, color = Palette.Muted)
                        Text(listOfNotNull(tree.head?.take(8), t("Current", "当前").takeIf { current }, t("Main", "主工作树").takeIf { tree.main }, tree.locked?.let { "Locked: $it" }, tree.prunable?.let { "Prunable: $it" }).joinToString(" · "), fontSize = TypeScale.Small)
                        Row {
                            TextButton(enabled = !busy && !current && !tree.bare && tree.prunable == null, onClick = { onOpen(File(tree.path)) }) { Text(t("Open", "打开")) }
                            TextButton(enabled = !busy && !current && !tree.main && tree.locked == null && tree.prunable == null, onClick = {
                                actions.ask(DialogSpec(t("Remove worktree", "移除工作树"), tree.path + "\n" + t("Deletes this worktree directory. The branch remains. Changes, ignored files and locked worktrees are protected. This cannot be undone in GitViz.", "删除此工作树目录，保留分支。有改动、被忽略文件或锁定时将拒绝移除。此操作无法在 GitViz 中撤销。"), t("Remove", "移除"), danger = true) { _, _ ->
                                    actions.run(t("Removing worktree", "正在移除工作树"), t("Worktree removed", "工作树已移除")) { removeWorktree(tree) }
                                })
                            }) { Text(t("Remove…", "移除…")) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
