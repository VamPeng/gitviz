package gitviz

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

@Composable internal fun ComparisonView(directory: File, refs: List<RefTarget>, onOpenCommit: (String) -> Unit, modifier: Modifier) {
    val names = refs.mapNotNull { r -> when (r.label.kind) { RefKind.BRANCH -> "refs/heads/${r.label.name}"; RefKind.REMOTE -> "refs/remotes/${r.label.name}"; RefKind.TAG -> "refs/tags/${r.label.name}"; else -> null } }.distinct()
    var a by remember(directory) { mutableStateOf("HEAD") }
    var b by remember(directory) { mutableStateOf(names.firstOrNull { name -> refs.none { it.label.isCurrent && name == "refs/heads/${it.label.name}" } } ?: "HEAD") }
    var request by remember(directory) { mutableStateOf(CompareRequest(a, b)) }
    var choose by remember { mutableStateOf<Boolean?>(null) }
    var loaded by remember(directory, request) { mutableStateOf<Result<ComparisonSession>?>(null) }
    LaunchedEffect(directory, request) {
        var owned: ComparisonSession? = null
        try {
            withContext(Dispatchers.IO) { owned = ComparisonSession.open(directory, request) }
            loaded = Result.success(checkNotNull(owned))
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loaded = Result.failure(e) }
        finally { withContext(NonCancellable + Dispatchers.IO) { owned?.close() } }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TextButton(onClick = { choose = true }) { Text("A: $a") }
            TextButton(onClick = { choose = false }) { Text("B: $b") }
            TextButton(onClick = { request = CompareRequest(a, b, generation = request.generation + 1) }) { Text(t("Compare / refresh", "对比／刷新")) }
            TextButton(enabled = loaded?.getOrNull()?.let { a == it.request.leftLabel && b == it.request.rightLabel } == true, onClick = {
                val old = a; a = b; b = old
                val session = loaded?.getOrNull()
                request = if (session == null) CompareRequest(a, b, generation = request.generation + 1)
                    else CompareRequest(session.b, session.a, session.request.rightLabel, session.request.leftLabel, request.generation + 1)
            }) { Text(t("Swap", "交换方向")) }
        }
        choose?.let { left -> GitMenuPopup((listOf("HEAD") + names).map { name -> MenuItem(name) { if (left) a = name else b = name } }, { choose = null }) }
        val result = loaded
        when {
            result == null -> Message(t("Resolving comparison…", "正在解析引用对比…"), Modifier.weight(1f).fillMaxWidth())
            result.isFailure -> Message(result.exceptionOrNull()?.message.orEmpty(), Modifier.weight(1f).fillMaxWidth())
            else -> key(result.getOrThrow()) { ComparisonContents(result.getOrThrow(), onOpenCommit, Modifier.weight(1f).fillMaxWidth()) }
        }
    }
}

@Composable private fun ComparisonContents(session: ComparisonSession, onOpenCommit: (String) -> Unit, modifier: Modifier) {
    var endpoint by remember { mutableStateOf(session.bases.isEmpty()) }
    var base by remember { mutableStateOf(session.bases.singleOrNull()) }
    var choosingBase by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(DiffMode.Unified) }
    var selected by remember(endpoint, base) { mutableStateOf<FileChange?>(null) }
    Column(modifier) {
        Text("${session.request.leftLabel} (${session.a.take(8)}) ↔ ${session.request.rightLabel} (${session.b.take(8)})", fontSize = TypeScale.Small, modifier = Modifier.padding(horizontal = 12.dp))
        Row(Modifier.fillMaxWidth().weight(0.4f)) {
            CompareCommitList(session, true, onOpenCommit, Modifier.weight(1f).fillMaxHeight())
            VerticalDivider()
            CompareCommitList(session, false, onOpenCommit, Modifier.weight(1f).fillMaxHeight())
        }
        HorizontalDivider()
        Row {
            TextButton(enabled = session.bases.isNotEmpty(), onClick = { endpoint = !endpoint }) { Text(if (endpoint) t("Files: A → B", "文件：A → B") else t("Files: merge-base → B", "文件：共同祖先 → B")) }
            if (session.bases.isEmpty()) Text(t("No common ancestor; comparing endpoints", "无共同祖先，使用端点对比"), fontSize = TypeScale.Small, modifier = Modifier.padding(12.dp))
            if (!endpoint && session.bases.size > 1) TextButton(onClick = { choosingBase = true }) { Text(base?.take(8) ?: t("Choose merge-base…", "选择共同祖先…")) }
            Segmented(listOf(DiffMode.Unified to t("Unified", "统一"), DiffMode.Split to t("Split", "并排")), mode, onSelect = { mode = it })
        }
        if (choosingBase) GitMenuPopup(session.bases.map { oid -> MenuItem(oid) { base = oid } }, { choosingBase = false })
        val content = Modifier.fillMaxWidth().weight(0.6f)
        if (!endpoint && base == null && session.bases.isNotEmpty()) Message(t("Multiple common ancestors: choose one before comparing files", "有多个共同祖先，请选择后对比文件"), content)
        else Loaded(Triple(session, endpoint, base), content, { session.files(endpoint, base) }) { files ->
            Row(content) {
                LazyColumn(Modifier.width(250.dp).fillMaxHeight()) { items(files) { file ->
                    Text("${file.displayPath}  +${file.additions} −${file.deletions}", fontSize = TypeScale.Small, modifier = Modifier.fillMaxWidth().rowHighlight(selected == file).clickable { selected = file }.padding(8.dp))
                } }
                VerticalDivider()
                val file = selected ?: files.firstOrNull()
                val diff = Modifier.weight(1f).fillMaxHeight()
                if (file == null) Message(t("No file changes", "没有文件变化"), diff)
                else Loaded(session to file, diff, { SourceDiff(parseDiffLines(session.diff(file)), session.sources(file)) }) { loaded -> DiffBody(loaded.lines, mode, diff, sources = loaded.sources) }
            }
        }
    }
}

@Composable private fun CompareCommitList(session: ComparisonSession, left: Boolean, onOpen: (String) -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var commits by remember { mutableStateOf(emptyList<CommitInfo>()) }
    var more by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        if (busy || !more) return
        busy = true
        try { val page = withContext(Dispatchers.IO) { session.load(left) }; commits = commits + page.commits; more = page.hasMore }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = t("Refresh comparison: ", "请刷新对比：") + e.message; more = false }
        finally { busy = false }
    }
    LaunchedEffect(session, left) { load() }
    Column(modifier) {
        Text(t("Only ${if (left) "A" else "B"}: ", "仅 ${if (left) "A" else "B"}：") + session.counts[if (left) 0 else 1], fontSize = TypeScale.Body, modifier = Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(commits, key = { it.id }) { c -> Text("${c.shortId}  ${c.shortMessage}", fontSize = TypeScale.Small, modifier = Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(8.dp)) }
            error?.let { item { Text(it, color = Palette.Removed) } }
            if (more) item { TextButton(enabled = !busy, onClick = { scope.launch { load() } }) { Text(if (busy) t("Loading…", "正在读取…") else t("Load more", "加载更多")) } }
        }
    }
}
