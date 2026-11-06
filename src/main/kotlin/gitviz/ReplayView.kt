package gitviz

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.roundToInt

@Composable internal fun ReplayView(directory: File, refs: List<RefTarget>, onShowFileHistory: (String) -> Unit, modifier: Modifier) {
    var branches by remember(directory) { mutableStateOf(emptySet<String>()) }
    var refresh by remember(directory) { mutableStateOf(0) }
    var loaded by remember(directory, branches, refresh) { mutableStateOf<Result<ReplaySession>?>(null) }
    var cancelled by remember(directory, branches, refresh) { mutableStateOf(false) }
    var loadingJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(directory, branches, refresh) {
        var owned: ReplaySession? = null
        val job = currentCoroutineContext().job
        loadingJob = job
        try {
            withContext(Dispatchers.IO) { owned = ReplaySession.open(directory, branches) { !job.isActive } }
            loaded = Result.success(checkNotNull(owned))
            loadingJob = null
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loaded = Result.failure(e) }
        finally { if (loadingJob === job) loadingJob = null; withContext(NonCancellable + Dispatchers.IO) { owned?.close() } }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Timeline replay", "时间轴回放"), fontSize = TypeScale.Title, modifier = Modifier.weight(1f))
            TextButton(onClick = { refresh++ }) { Text(t("Refresh snapshot", "刷新快照")) }
            if (loadingJob != null) TextButton(onClick = { cancelled = true; loadingJob?.cancel() }) { Text(t("Cancel", "取消")) }
        }
        BranchFilterBar(refs, branches, false, { branches = it })
        Text(t("Read-only replay in ancestry order. Labels are today's references, not historical branch names. HEAD and files stay unchanged.", "按祖先顺序只读回放。标签是当前引用，不代表历史分支名；HEAD 和文件保持不变。"), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.padding(12.dp))
        HorizontalDivider()
        val state = loaded
        when {
            cancelled -> Message(t("Replay loading cancelled. Refresh to retry.", "已取消加载，刷新可重试。"), Modifier.fillMaxSize())
            state == null -> Message(t("Loading replay snapshot…", "正在加载回放快照…"), Modifier.fillMaxSize())
            state.isFailure -> Message(state.exceptionOrNull()?.message.orEmpty(), Modifier.fillMaxSize())
            state.getOrThrow().commits.isEmpty() -> Message(t("No commits to replay", "没有可回放的提交"), Modifier.fillMaxSize())
            else -> key(state.getOrThrow()) { ReplayContents(state.getOrThrow(), onShowFileHistory, Modifier.fillMaxWidth().weight(1f)) }
        }
    }
}

@Composable internal fun ReplayContents(session: ReplaySession, onShowFileHistory: (String) -> Unit, modifier: Modifier) {
    var position by remember(session) { mutableStateOf(0) }
    var playing by remember(session) { mutableStateOf(false) }
    var interval by remember { mutableStateOf(750L) }
    var frame by remember(session) { mutableStateOf<Pair<Int, RepoState>?>(null) }
    var selected by remember(session, position) { mutableStateOf(session.commits[position]) }
    val list = rememberLazyListState()
    LaunchedEffect(session, position) {
        val next = withContext(Dispatchers.Default) { session.frame(position) }
        frame = position to next
        list.scrollToItem(0)
    }
    LaunchedEffect(playing, interval, position, frame?.first) {
        if (playing && frame?.first == position) {
            if (position == session.commits.lastIndex) playing = false
            else { delay(interval); position++ }
        }
    }
    fun seek(index: Int) { playing = false; position = index.coerceIn(session.commits.indices) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = position > 0, onClick = { seek(position - 1) }) { Text(t("Previous", "上一步")) }
            TextButton(onClick = { if (!playing && position == session.commits.lastIndex) position = 0; playing = !playing }) { Text(if (playing) t("Pause", "暂停") else t("Play", "播放")) }
            TextButton(enabled = position < session.commits.lastIndex, onClick = { seek(position + 1) }) { Text(t("Next", "下一步")) }
            Segmented(listOf(1500L to "0.5×", 750L to "1×", 375L to "2×"), interval, onSelect = { interval = it })
            Text("${position + 1} / ${session.commits.size}", fontSize = TypeScale.Small)
            Text(formatTime(session.commits[position].timeSeconds), color = Palette.Muted, fontSize = TypeScale.Small)
        }
        Slider(value = position.toFloat(), onValueChange = { seek(it.roundToInt()) }, valueRange = 0f..session.commits.lastIndex.coerceAtLeast(1).toFloat(), enabled = session.commits.size > 1,
            colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.Line), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        val view = frame
        if (view == null) Message(t("Preparing graph…", "正在绘制提交图…"), Modifier.fillMaxSize()) else {
            if (position + 1 > GitRepo.PAGE_SIZE) Text(t("Showing the most recent ${GitRepo.PAGE_SIZE} revealed commits.", "图中显示最近展开的 ${GitRepo.PAGE_SIZE} 个提交。"), fontSize = TypeScale.Small, modifier = Modifier.padding(horizontal = 12.dp))
            Column(Modifier.fillMaxWidth().weight(1f)) {
                CommitList(view.second, view.second.result.commits.indices.toList(), selected, list, { playing = false; selected = it }, Modifier.fillMaxWidth().weight(0.5f))
                HorizontalDivider()
                CommitDetail(session.repo, selected, { id -> session.indexById[id]?.let(::seek) }, Modifier.fillMaxWidth().weight(0.5f), onShowFileHistory)
            }
        }
    }
}
